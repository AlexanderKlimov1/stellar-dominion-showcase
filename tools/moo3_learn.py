# -*- coding: utf-8 -*-
"""
Самообучающийся игрок: сеть учится играть в НАШУ игру и растёт на своих же партиях.

ЗАЧЕМ ЭТО ЗДЕСЬ, А НЕ В ОРИГИНАЛЕ. Учиться сеть может только на большом числе партий, а
партия в настоящей MOO2 — это полчаса настоящей мыши в DOSBox: тысячи партий там нельзя
сыграть ни за какое время. Наш же сервер играет партию целиком по REST, без экрана и без
мыши, за считанные секунды. Поэтому сеть учится здесь, а в оригинал выученное приносит
`moo2_play.py` — у него ровно тот же набор решений.

ЧЕМУ ИМЕННО УЧИТСЯ. Тому же, чем занят игрок в оригинале и наш `AiEmpireService`: ЧТО
СТРОИТЬ каждой колонии каждый ход. Остальное (наука, флот) пока ведётся правилами — их
очередь следующая, и сеть к ним готова: у неё на входе состояние, а не список действий.

КАК УСТРОЕНА СЕТЬ. Маленькая двухслойная сеть на numpy: вход — признаки КОЛОНИИ вместе с
признаками ОДНОГО предлагаемого проекта, выход — одно число, «насколько это хорошо». Так
сеть оценивает каждый доступный проект по очереди и колония строит лучший. Это удобнее
привычного «выход на каждое действие»: список проектов у каждой колонии свой и меняется по
ходу партии, а число признаков проекта постоянно.

КАК УЧИТСЯ. Не градиентом, а ОТБОРОМ (эволюционная стратегия). Причина в том, чем мы
меряем: наградой служит исход целой партии, а не отдельного хода, и сказать, какое именно
нажатие сколько принесло, нечем. Отбор же требует только сравнения: сыграли партию одними
весами, сыграли другими — чьи лучше, те и остались, а вокруг них рождается следующее
поколение. Это медленнее градиента и зато честно работает на такой награде.

ЧЕМ МЕРЯЕТСЯ УСПЕХ. Долей своей мощи в мощи всех империй партии (`EmpireMightRules`, та же
величина, которой мерит себя наш прибор балансировки). Доля, а не число: партии выходят
разной длины и щедрости, а доля сравнима всегда.

Запуск (сервер должен быть поднят):
    python tools\\moo3_learn.py train --generations 20 --games 2
    python tools\\moo3_learn.py play --weights tools\\learn\\best.npz
"""

import argparse
import collections
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import numpy

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEARN_DIR = os.path.join(ROOT, 'tools', 'learn')
BEST_FILE = os.path.join(LEARN_DIR, 'best.npz')
REPORT_FILE = os.path.join(LEARN_DIR, 'train-report.txt')

BASE = os.environ.get('MOO3_BASE', 'http://localhost:8081')

# Галактика обучения: маленькая и на двоих. Так решено начать — партия считается быстрее,
# а разница между весами видна раньше: на большой карте исход тонет в случайностях
# расселения.
GALAXY = 'SMALL'
# Соперников ТРОЕ, а не один. На двоих доля мощи слишком легко давалась бездействием:
# сосед один, и отставание от него тонет в случайностях его же расселения. В партии на
# четверых доля считается от суммы трёх чужих мощей сразу, и цена простоя видна раньше.
EMPIRES = 4


# --- разговор с сервером --------------------------------------------------------------

def call(method, path, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    head = {'Content-Type': 'application/json', 'Accept-Language': 'ru'}
    head.update(headers or {})
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=head)
    try:
        with urllib.request.urlopen(request, timeout=600) as answer:
            text = answer.read().decode('utf-8')
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as error:
        return {'ERROR': error.code, 'body': error.read().decode('utf-8')[:300]}
    except urllib.error.URLError as error:
        return {'ERROR': 0, 'body': str(error)}


def admin_token():
    """Пропуск администратора: партии обучения заводятся им и им же убираются."""
    password = None
    for line in io.open(os.path.join(ROOT, 'admin.txt'), encoding='utf-8'):
        match = re.match(r'^\s*(?:пароль|password)\s*:\s*(\S+)', line, re.IGNORECASE)
        if match:
            password = match.group(1)
    signed = call('POST', '/api/auth/login', {'login': 'admin', 'password': password})
    if 'ERROR' in signed:
        raise SystemExit('администратор не вошёл: %s' % signed.get('body'))
    return signed['token']


# --- признаки ---------------------------------------------------------------------------

#: Виды проектов, какими их различает сеть. Код проекта — строка, и сама по себе она сети
#: ничего не говорит; вид же говорит всё: корабль это или здание, вечная стройка или
#: разовая, расселение или оборона.
KINDS = ('housing', 'trade', 'building', 'factory', 'lab', 'colony-base',
         'colony-ship', 'outpost', 'freighter', 'spy', 'ship')

COLONY_FEATURES = 10
PROJECT_FEATURES = len(KINDS) + 4
INPUTS = COLONY_FEATURES + PROJECT_FEATURES

#: Разделы дерева — в том же порядке, в каком их отдаёт справочник.
FIELDS = ('engineering', 'power', 'chemistry', 'sociology',
          'computers', 'biology', 'physics', 'force_fields')

#: Признаки выбора науки: раздел, цена уровня, его номер, и сколько всего уже изучено.
EMPIRE_FEATURES = 4
LEVEL_FEATURES = len(FIELDS) + 2
RESEARCH_INPUTS = EMPIRE_FEATURES + LEVEL_FEATURES


#: Занятия жителей — те же три, что в колонии MOO II.
JOBS = ('farmers', 'workers', 'scientists')
JOBS_INPUTS = 10 + len(JOBS)

#: Признаки решения о флоте: сам флот и система, куда он мог бы пойти.
FLEET_FEATURES = 6
TARGET_FEATURES = 9
FLEET_INPUTS = FLEET_FEATURES + TARGET_FEATURES


def kind_of(project):
    """Вид проекта по его коду и цене — то, что сеть и различает."""
    code = project.get('code') or ''
    if code == 'HOUSING':
        return 'housing'
    if code == 'TRADE_GOODS':
        return 'trade'
    if code == 'COLONY_BASE':
        return 'colony-base'
    if code == 'COLONY_SHIP':
        return 'colony-ship'
    if code == 'OUTPOST_SHIP':
        return 'outpost'
    if code == 'FREIGHTER':
        return 'freighter'
    if code == 'SPY':
        return 'spy'
    if code.startswith('SHIP:'):
        return 'ship'
    if code == 'automated-factory':
        return 'factory'
    if code == 'research-laboratory':
        return 'lab'
    return 'building'


def colony_vector(planet, colony, state):
    """Признаки колонии: во что она превращает жителей и сколько их у неё."""
    people = max(1, colony.get('farmers', 0) + colony.get('workers', 0)
                 + colony.get('scientists', 0))
    room = max(1, colony.get('maxPopulation') or 1)
    return numpy.array([
        people / room,
        colony.get('farmers', 0) / people,
        colony.get('workers', 0) / people,
        colony.get('scientists', 0) / people,
        min(colony.get('production', 0), 60) / 60.0,
        min(colony.get('research', 0), 60) / 60.0,
        min(max(colony.get('foodBalance', 0), -20), 20) / 20.0,
        1.0 if planet.get('homeworld') else 0.0,
        min(state['turn'], 200) / 200.0,
        min(state['colonies'], 12) / 12.0,
    ], dtype=numpy.float32)


def project_vector(project, production=0):
    """
    Признаки проекта: его вид, цена, содержание и СРОК при нынешней выработке.

    Срок — тот самый, что наш клиент показывает игроку строкой очереди
    (`colonyRules.projectTurns`), и он оказался решающим. Без него сеть различала корпуса
    только по цене, а цена упиралась в потолок признака: `Battleship` и фрегат выглядели
    для неё одинаково дорогими, и обученная сеть 57 ходов из 60 закладывала на родном мире
    линкор, которого там не достроить за партию, — то есть не строила ничего. Срок делает
    «мне это не поднять» отдельным числом, а не догадкой о цене.

    Потолок цены поднят с 600 до 2000: 600 — это ещё не самый дорогой проект игры
    (колониальный корабль стоит 500, крупные корпуса дороже), и всё, что выше, сливалось
    в одну единицу.
    """
    kind = kind_of(project)
    one_hot = [1.0 if kind == name else 0.0 for name in KINDS]
    cost = project.get('cost') or 0
    turns = cost / max(production, 1) if cost else 0
    return numpy.array(one_hot + [
        min(cost, 2000) / 2000.0,
        min(project.get('upkeep') or 0, 10) / 10.0,
        1.0 if project.get('repeatable') else 0.0,
        min(turns, 40) / 40.0,
    ], dtype=numpy.float32)


# --- сеть ---------------------------------------------------------------------------------

# Скрытый слой втрое шире прежних шестнадцати. Причина не в красоте: у сети стало три
# головы и новые признаки на входе, а на шестнадцати узлах отбор десять поколений подряд
# стоял на одном месте — весов было слишком мало, чтобы различать положения, которые сеть
# теперь видит.
HIDDEN = 48


def new_weights(seed=0):
    """
    Случайные веса: маленькие, чтобы первое поколение не было уверено ни в чём.

    Голов у сети ЧЕТЫРЕ — стройка, наука, флот и ЖИТЕЛИ, — и каждая заведена не для
    полноты, а после замера, показавшего, что без неё учиться нечему.

    Голова ЖИТЕЛЕЙ добавлена последней и по той же примете, что и прочие: доля мощи стояла
    на 0.101 двадцать поколений подряд при справедливой доле 0.25, а соперник держал около
    0.3. Сеть при этом уже и расселялась, и строила заводы — не хватало ей не стратегии, а
    ЭКОНОМИКИ: жителей по работам она не переставляла НИ РАЗУ, то есть играла тем
    распределением, какое колония получила при основании, тогда как `AiEmpireService`
    расставляет их каждый ход. Один житель в MOO II — это либо станок, либо лаборатория, и
    выбор между ними и есть главное решение колонии.

    Голова ФЛОТА добавлена последней, и её отсутствие было видно так же ясно: обученная
    сеть за шестьдесят ходов не вышла из родной системы ни разу — колония одна, флотов
    ноль, — потому что решения «куда лететь» у неё не было вовсе. Колониальный корабль в
    нашей игре селится там, КУДА ДОЛЕТЕЛ (`FleetController.colonize`), и без приказа он
    стоял бы у родной звезды до конца партии, чего бы сеть ни строила.

    Первый
    прогон обучения показал это дословно: агент правил только стройку, а цель науки не
    задавал вовсе (человеку её задавать надо самому), и мощь империи выходила ОДНА И ТА ЖЕ
    при трёх разных стратегиях стройки — 166 против 298 у соперника. Учиться на таком
    мериле нечему: что ни делай, исход один.
    """
    random = numpy.random.default_rng(seed)
    return {
        'w1': random.normal(0, 0.4, (INPUTS, HIDDEN)).astype(numpy.float32),
        'b1': numpy.zeros(HIDDEN, dtype=numpy.float32),
        'w2': random.normal(0, 0.4, (HIDDEN, 1)).astype(numpy.float32),
        'b2': numpy.zeros(1, dtype=numpy.float32),
        'r1': random.normal(0, 0.4, (RESEARCH_INPUTS, HIDDEN)).astype(numpy.float32),
        'rb1': numpy.zeros(HIDDEN, dtype=numpy.float32),
        'r2': random.normal(0, 0.4, (HIDDEN, 1)).astype(numpy.float32),
        'rb2': numpy.zeros(1, dtype=numpy.float32),
        'f1': random.normal(0, 0.4, (FLEET_INPUTS, HIDDEN)).astype(numpy.float32),
        'fb1': numpy.zeros(HIDDEN, dtype=numpy.float32),
        'f2': random.normal(0, 0.4, (HIDDEN, 1)).astype(numpy.float32),
        'fb2': numpy.zeros(1, dtype=numpy.float32),
        'j1': random.normal(0, 0.4, (JOBS_INPUTS, HIDDEN)).astype(numpy.float32),
        'jb1': numpy.zeros(HIDDEN, dtype=numpy.float32),
        'j2': random.normal(0, 0.4, (HIDDEN, 1)).astype(numpy.float32),
        'jb2': numpy.zeros(1, dtype=numpy.float32),
    }


def score(weights, rows, head='build'):
    """Оценка каждого предложенного действия: вход — матрица признаков, выход — числа."""
    first, bias, second, bias2 = {
        'build': ('w1', 'b1', 'w2', 'b2'),
        'research': ('r1', 'rb1', 'r2', 'rb2'),
        'fleet': ('f1', 'fb1', 'f2', 'fb2'),
        'jobs': ('j1', 'jb1', 'j2', 'jb2'),
    }[head]
    hidden = numpy.tanh(rows @ weights[first] + weights[bias])
    return (hidden @ weights[second] + weights[bias2]).ravel()


def level_vector(field, level, empire):
    """Признаки уровня науки: где он лежит, сколько стоит и как далеко от начала."""
    one_hot = [1.0 if field == name else 0.0 for name in FIELDS]
    return numpy.array(one_hot + [
        min(level.get('cost') or 0, 2000) / 2000.0,
        min(level.get('order') or 1, 12) / 12.0,
    ], dtype=numpy.float32)


def empire_vector(state):
    """Признаки империи для выбора науки: ход, колонии, наука и изученное."""
    return numpy.array([
        min(state['turn'], 200) / 200.0,
        min(state['colonies'], 12) / 12.0,
        min(state.get('researchPerTurn', 0), 120) / 120.0,
        min(state.get('acquired', 0), 60) / 60.0,
    ], dtype=numpy.float32)


def fleet_vector(fleet, state):
    """
    Признаки самого флота: чем он гружён и сколько в нём кораблей.

    Роль корабля спрашивается у сервера (`FleetShipDto.role`), а не выводится из оружия:
    без оружия гражданских ТРОЕ — колониальный, застава и транспорт, — а высаживают они
    разное.
    """
    role = {}
    for part in (fleet.get('composition') or []):
        role[part.get('role')] = role.get(part.get('role'), 0) + (part.get('ships') or 0)
    ships = fleet.get('ships') or sum(role.values())
    return numpy.array([
        1.0 if role.get('COLONY') else 0.0,
        1.0 if role.get('OUTPOST') else 0.0,
        min(role.get('WARSHIP', 0), 6) / 6.0,
        min(ships, 6) / 6.0,
        min(state['turn'], 200) / 200.0,
        min(state['colonies'], 12) / 12.0,
    ], dtype=numpy.float32)


def target_vector(system, distance, me, staying):
    """
    Признаки звезды как цели: далеко ли, есть ли место под колонию и чьё это место.

    «Остаться» — такая же цель со своим признаком: гарнизон в своей системе это решение,
    а не отсутствие решения, и отнимать его у сети незачем.
    """
    planets = system.get('planets') or []
    free = [planet for planet in planets
            if planet.get('colonizable') and not planet.get('ownerPlayerId')
            and (planet.get('maxPopulation') or 0) > 0]
    best = max([planet.get('maxPopulation') or 0 for planet in free], default=0)
    return numpy.array([
        min(distance, 24) / 24.0,
        1.0 if staying else 0.0,
        1.0 if system.get('explored') else 0.0,
        1.0 if free else 0.0,
        min(best, 8) / 8.0,
        min(len(planets), 5) / 5.0,
        1.0 if any(planet.get('ownerPlayerId') == me for planet in planets) else 0.0,
        1.0 if any(planet.get('ownerPlayerId') and planet.get('ownerPlayerId') != me
                   for planet in planets) else 0.0,
        1.0 if system.get('monster') else 0.0,
    ], dtype=numpy.float32)


def free_planet(system, me):
    """Лучшая свободная планета системы — та, куда и сажают поселенцев."""
    free = [planet for planet in (system.get('planets') or [])
            if planet.get('colonizable') and not planet.get('ownerPlayerId')
            and (planet.get('maxPopulation') or 0) > 0]
    if not free:
        return None
    return max(free, key=lambda planet: planet.get('maxPopulation') or 0)


def distance_between(first, second):
    """Расстояние между звёздами в парсеках — той единицей, какой меряет игра (п. 4.2)."""
    dx = (first.get('x') or 0) - (second.get('x') or 0)
    dy = (first.get('y') or 0) - (second.get('y') or 0)
    return (dx * dx + dy * dy) ** 0.5


#: Сколько ближайших звёзд сеть рассматривает как цель. Дальше топлива всё равно нет, а
#: перебирать всю галактику каждому флоту каждый ход — впустую.
TARGETS_CONSIDERED = 16


def next_levels(tree, acquired):
    """
    Что империя может изучать сейчас: по одному ближайшему уровню на раздел.

    Уровень считается пройденным, если из него уже взята хоть одна технология: так
    устроена наша игра и так же устроена MOO II — на уровне берут одну вещь.
    """
    done = {(one['categoryCode'], one['levelOrder']) for one in acquired}
    out = []
    for category in tree.get('categories', []):
        field = category.get('code')
        for level in category.get('levels', []):
            if (field, level.get('order')) in done:
                continue
            out.append((field, level))
            break
    return out


def pick_option(level):
    """Какую технологию взять из уровня: отмеченную деревом, иначе первую."""
    options = level.get('options') or []
    if not options:
        return None
    # Отмеченная деревом — то же, чем пользуется `ResearchService.pickOption`.
    for option in options:
        if option.get('recommended'):
            return option['code']
    return options[0].get('code')


def mutate(weights, sigma, random):
    return {name: value + random.normal(0, sigma, value.shape).astype(numpy.float32)
            for name, value in weights.items()}


def save_weights(weights, path=BEST_FILE):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    numpy.savez(path, **weights)


def upgrade(weights):
    """
    Веса прежней сети — в сеть нынешнего размера, а не заново с нуля.

    Сеть растёт (шире слой, новая голова), а выученное за тридцать поколений выбрасывать
    незачем: прежние числа ложатся в угол новых матриц, остальное достаётся случайным —
    ровно как у нового поколения. Отбор продолжается с того места, где стоял.
    """
    fresh = new_weights(7)
    out = {}
    for name, target in fresh.items():
        old = weights.get(name)
        if old is None:
            out[name] = target
        elif old.shape == target.shape:
            out[name] = old
        else:
            value = target.copy()
            corner = tuple(slice(0, min(was, now))
                           for was, now in zip(old.shape, target.shape))
            value[corner] = old[corner]
            out[name] = value
    return out


def load_weights(path=BEST_FILE):
    with numpy.load(path) as data:
        return upgrade({name: data[name] for name in data.files})


# --- партия -------------------------------------------------------------------------------

def new_tally():
    """Чем отчитывается партия: что сеть исследовала, строила и куда ходила."""
    return {'research': [], 'projects': collections.Counter(), 'moves': 0,
            'colonized': 0, 'colonies': 0, 'ships': collections.Counter(),
            'acquired': 0, 'jobs': 0}


def orders(weights, game_id, token, me, game_map, state, tally=None):
    """
    Куда идут флоты империи — третья голова сети (п. 8).

    Порядок тот же, каким расселяется человек: прилетевший колониальный корабль САЖАЕТ
    поселенцев — за тем и летел, — а всякий стоящий без дела флот получает цель. Ею же
    сеть и разведывает галактику: приход в неизвестную систему её и открывает
    (`FleetService.explore`), отдельного «разведать» в игре нет.

    Флот В ПУТИ приказа не получает: курс в пути не сменить, это правило самой игры
    (`FlightRules`, нужна Hyperspace Communications).
    """
    fleets = call('GET', '/api/games/%s/fleets?accessToken=%s' % (game_id, token))
    if not isinstance(fleets, list):
        return
    systems = game_map.get('systems') or []
    by_id = {system.get('id'): system for system in systems}
    for fleet in fleets:
        if fleet.get('targetSystemId'):
            continue
        here = by_id.get(fleet.get('starSystemId'))
        if here is None:
            continue
        settlers = any(part.get('role') == 'COLONY'
                       for part in (fleet.get('composition') or []))
        if settlers:
            planet = free_planet(here, me)
            if planet is not None:
                answer = call('POST', '/api/games/%s/fleets/%s/colonize'
                              % (game_id, fleet.get('id')),
                              {'accessToken': token, 'targetPlanetId': planet.get('id')})
                if not (isinstance(answer, dict) and answer.get('ERROR')):
                    if tally is not None:
                        tally['colonized'] += 1
                    continue
        near = sorted(systems, key=lambda system: distance_between(here, system))
        near = near[:TARGETS_CONSIDERED]
        rows = numpy.stack([
            numpy.concatenate([
                fleet_vector(fleet, state),
                target_vector(system, distance_between(here, system), me,
                              system.get('id') == here.get('id'))])
            for system in near])
        target = near[int(numpy.argmax(score(weights, rows, 'fleet')))]
        if target.get('id') == here.get('id'):
            continue
        answer = call('POST', '/api/games/%s/fleets/%s/move' % (game_id, fleet.get('id')),
                      {'accessToken': token, 'targetSystemId': target.get('id')})
        # Отказ здесь дело обычное: топлива до звезды может не хватать (п. 8), и тогда
        # флот просто остаётся на месте — выбор сети был неудачным, а не запрос неверным.
        if tally is not None and not (isinstance(answer, dict) and answer.get('ERROR')):
            tally['moves'] += 1


def job_shares(weights, planet, colony, state):
    """
    Доли занятий по мнению сети: оценка каждому занятию, затем softmax.

    Оценивается КАЖДОЕ занятие отдельно — тем же приёмом «признаки положения плюс признаки
    одного действия», каким сеть выбирает проект и цель полёта. Так число выходов не зависит
    от населения колонии: доли считаются раз, а жители делятся по ним.
    """
    rows = numpy.stack([
        numpy.concatenate([colony_vector(planet, colony, state),
                           numpy.array([1.0 if index == number else 0.0
                                        for number in range(len(JOBS))],
                                       dtype=numpy.float32)])
        for index in range(len(JOBS))])
    values = score(weights, rows, 'jobs')
    values = numpy.exp(values - values.max())
    return values / values.sum()


def split_people(shares, people):
    """Жители по долям, а остаток — самой крупной доле: сумма обязана сойтись с населением."""
    counts = [int(share * people) for share in shares]
    while sum(counts) < people:
        counts[int(numpy.argmax([share * people - count
                                 for share, count in zip(shares, counts)]))] += 1
    return counts


def assign_jobs(weights, game_id, token, planet, colony, state, tally=None):
    """
    Расставить жителей колонии — четвёртая голова сети (п. 4.1).

    ГОЛОДА НЕ ДОПУСКАЕМ: отрицательная еда это убыль населения, и правило «нельзя
    допускать отрицательной еды» стоит выше желания сети. Проверяется оно не нашим
    пересчётом правил колонии, а ОТВЕТОМ СЕРВЕРА — он присылает планету целиком, и в ней
    готовый `foodBalance`. Не хватает еды — переводим в фермеры столько жителей, сколько
    покрывает недостачу, и посылаем снова. Второго свода правил еды у нас нет и не будет:
    он разошёлся бы с `PopulationCalculator` на первой же правке.
    """
    people = ((colony.get('farmers') or 0) + (colony.get('workers') or 0)
              + (colony.get('scientists') or 0))
    if people < 2:
        return
    counts = split_people(job_shares(weights, planet, colony, state), people)
    now = [colony.get(job) or 0 for job in JOBS]
    for attempt in range(4):
        if counts == now:
            return
        answer = call('POST', '/api/games/%s/planets/%s/population' % (game_id, planet['id']),
                      {'accessToken': token, 'farmers': counts[0],
                       'workers': counts[1], 'scientists': counts[2]})
        if isinstance(answer, dict) and answer.get('ERROR'):
            return
        if tally is not None:
            tally['jobs'] += 1
        fresh = (answer or {}).get('colony') or {}
        balance = fresh.get('foodBalance')
        if balance is None or balance >= 0:
            return
        # Недостача еды: добираем фермеров у самого многолюдного из остальных занятий.
        per_farmer = max(colony.get('foodPerFarmer') or 1, 1)
        need = min(-(-(-balance) // per_farmer), people - counts[0])
        if need <= 0:
            return
        now = list(counts)
        donor = 1 if counts[1] >= counts[2] else 2
        moved = min(need, counts[donor])
        counts = list(counts)
        counts[donor] -= moved
        counts[0] += moved


def play_game(weights, account, seed, turns, name='Обучение', tally=None):
    """
    Одна партия: сеть ведёт стройку своей империи, соперника ведёт наш ИИ.

    Отдаёт долю своей мощи в мощи всех империй — ею и меряется, чему сеть научилась.
    """
    created = call('POST', '/api/games',
                   {'name': '%s %d' % (name, seed), 'playerName': 'Ученик',
                    'galaxySize': GALAXY, 'totalPlayers': EMPIRES,
                    'seed': seed, 'council': False},
                   {'X-Account-Token': account})
    if 'ERROR' in created:
        return None, created.get('body')
    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    try:
        started = call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
        if isinstance(started, dict) and 'ERROR' in started:
            return None, started.get('body')

        tree = call('GET', '/api/reference/research')
        known = -1
        for turn in range(turns):
            # НАУКА ЗАДАЁТСЯ САМИМ ИГРОКОМ, и без этого партия проиграна заранее: первый
            # прогон обучения дал одинаковую мощь при любой стройке именно потому, что
            # империя не исследовала ничего. Цель ставится заново после каждого прорыва —
            # узнаём его по числу изученного.
            state_research = call('GET', '/api/games/%s/research?accessToken=%s'
                                  % (game_id, token))
            acquired = state_research.get('acquired') or []
            if len(acquired) != known:
                known = len(acquired)
                choices = next_levels(tree, acquired)
                if choices:
                    head = {'turn': turn, 'colonies': 0,
                            'researchPerTurn': state_research.get('researchPerTurn', 0),
                            'acquired': known}
                    rows = numpy.stack([
                        numpy.concatenate([empire_vector(head), level_vector(field, level, head)])
                        for field, level in choices])
                    field, level = choices[int(numpy.argmax(score(weights, rows, 'research')))]
                    option = pick_option(level)
                    if option:
                        call('POST', '/api/games/%s/research' % game_id,
                             {'accessToken': token, 'categoryCode': field,
                              'levelOrder': level.get('order'), 'optionCode': option})
                        if tally is not None:
                            tally['research'].append((turn, field, level.get('order'),
                                                      option))
            game_map = call('GET', '/api/games/%s/map?accessToken=%s' % (game_id, token))
            if 'ERROR' in game_map:
                return None, game_map.get('body')
            mine = [(system, planet)
                    for system in game_map.get('systems', [])
                    for planet in system.get('planets', [])
                    if planet.get('colony') and planet.get('ownerPlayerId')
                    == created['credentials']['playerId']]
            state = {'turn': turn, 'colonies': len(mine)}
            for system, planet in mine:
                colony = planet['colony']
                offered = colony.get('available') or []
                if not offered:
                    continue
                rows = numpy.stack([
                    numpy.concatenate([colony_vector(planet, colony, state),
                                       project_vector(project,
                                                      colony.get('production') or 0)])
                    for project in offered])
                best = offered[int(numpy.argmax(score(weights, rows)))]
                if best['code'] != colony.get('projectCode'):
                    call('POST', '/api/games/%s/planets/%s/project' % (game_id, planet['id']),
                         {'accessToken': token, 'projectCode': best['code']})
                if tally is not None:
                    tally['projects'][kind_of(best)] += 1
                # Жителей расставляем ПОСЛЕ выбора стройки: выработка колонии решает, за
                # сколько ходов эта стройка поднимется, и смотреть на неё надо вместе.
                assign_jobs(weights, game_id, token, planet, colony, state, tally)
            # Построенным надо распорядиться: без приказа корабль стоит у родной звезды до
            # конца партии, и расселения не будет, что бы колонии ни строили.
            orders(weights, game_id, token, created['credentials']['playerId'],
                   game_map, state, tally)
            ended = call('POST', '/api/games/%s/turn/end' % game_id, {'accessToken': token})
            if isinstance(ended, dict) and ended.get('ERROR'):
                return None, ended.get('body')
            if isinstance(ended, dict) and ended.get('status') == 'FINISHED':
                break

        if tally is not None:
            final = call('GET', '/api/games/%s/map?accessToken=%s' % (game_id, token))
            tally['colonies'] = sum(
                1 for system in (final.get('systems') or [])
                for planet in (system.get('planets') or [])
                if planet.get('colony') and planet.get('ownerPlayerId')
                == created['credentials']['playerId'])
            for fleet in (call('GET', '/api/games/%s/fleets?accessToken=%s'
                               % (game_id, token)) or []):
                for part in (fleet.get('composition') or []):
                    tally['ships'][part.get('role')] += part.get('ships') or 0
            last = call('GET', '/api/games/%s/research?accessToken=%s' % (game_id, token))
            tally['acquired'] = len(last.get('acquired') or [])

        telemetry = call('GET', '/api/games/%s/telemetry?accessToken=%s' % (game_id, token))
        if 'ERROR' in telemetry:
            return None, telemetry.get('body')
        return share_of_might(telemetry, created['credentials']['playerId']), None
    finally:
        call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))


def share_of_might(telemetry, player_id):
    """
    Доля своей мощи в мощи всех империй на последнем ходу.

    Мощь берётся та же, какой меряет себя вся игра (`EmpireMightRules`): флот, население,
    выработка и изученное одним числом. Доля, а не число: партии выходят разной длины, и
    абсолютные величины между ними несравнимы.
    """
    last = {}
    for empire in telemetry.get('empires', []):
        history = empire.get('history') or []
        if not history:
            continue
        last[empire.get('playerId') or empire.get('slot')] = history[-1].get('might', 0)
    total = sum(last.values())
    if total <= 0:
        return 0.0
    mine = last.get(player_id)
    if mine is None:
        # Свою империю ищем по месту, если идентификатора в летописи нет.
        mine = max(last.values()) if len(last) == 1 else 0
    return mine / total


# --- обучение ---------------------------------------------------------------------------

def note(line):
    """Строка в отчёт обучения — он копится и переживает сессию."""
    os.makedirs(LEARN_DIR, exist_ok=True)
    with io.open(REPORT_FILE, 'a', encoding='utf-8', newline='\n') as file:
        file.write(line + '\n')


def evaluate(weights, account, seeds, turns):
    """Средняя доля мощи по нескольким партиям: одна партия слишком случайна."""
    shares = []
    for seed in seeds:
        value, error = play_game(weights, account, seed, turns)
        if error:
            print('    партия %d не доиграна: %s' % (seed, str(error)[:120]), flush=True)
            continue
        shares.append(value)
    return sum(shares) / len(shares) if shares else 0.0


#: Во сколько раз шаг мутации растёт после удачного поколения и убывает после пустого.
#: Это правило одной пятой из эволюционных стратегий, и заведено оно по замеру: у сети,
#: доведённой отбором до вершины, ВСЕ пять потомков при `sigma 0.4` вышли ниже родителя
#: (0.043…0.090 против 0.100) — шаг, которым раньше сходили с плато, стал перелётом через
#: вершину. Обратный случай тоже измерен: при `sigma 0.2` отбор десять поколений стоял, не
#: дотягиваясь до соседней вершины. Значит, одного числа на всё обучение не бывает: пока
#: улучшения идут, шаг растёт, а как перестали — убывает, и поиск сам переходит от поиска
#: новой вершины к её отделке.
SIGMA_UP = 1.3
SIGMA_DOWN = 0.85
SIGMA_MIN = 0.02
SIGMA_MAX = 0.8

#: После скольких пустых поколений шаг РАЗОГРЕВАЕТСЯ до начального.
#:
#: Измерено на прогоне с самонастройкой: доля росла (0.102 → 0.118) ровно пока шаг стоял в
#: полосе 0.06…0.10, а дальше правило одной пятой ужало его до дна 0.02 — и там отбор
#: простоял ТРИНАДЦАТЬ поколений впустую. Сужение само себя и запирает: пустое поколение
#: сужает шаг, узкий шаг не находит улучшений, и выхода из этого нет. Поэтому дно — не
#: конец поиска, а знак, что отделка кончилась и пора снова искать вершину.
REHEAT_AFTER = 6

#: Какая доля потомков идёт в новый центр поиска.
#:
#: Отбор ведёт ДВЕ величины: `best` — лучшее найденное (его и сохраняем, его и показываем)
#: и `center` — середину облака, из которого рождаются потомки. Жадный отбор их не различал,
#: и потому застревал: у доведённой сети все потомки поколения ниже родителя, и «лучше
#: родителя или никак» выбрасывало поколение целиком — 150 потомков подряд ушли в корзину.
#: Но потомки РАЗНЫЕ (0.043…0.090 при родителе 0.100), значит в облаке есть направление, и
#: взять его можно только СРЕДНИМ лучшей половины, а не отдельным потомком. Это обычная
#: эволюционная стратегия с пересчётом середины (CEM), и от жадной она отличается ровно тем,
#: что умеет двигаться, когда ни один потомок не лучше.
ELITE_SHARE = 0.5


def blend(sets, weights):
    """Взвешенная середина нескольких наборов весов — новый центр поиска."""
    total = sum(weights)
    return {name: sum(one[name] * weight for one, weight in zip(sets, weights)) / total
            for name in sets[0]}


def train(generations, games, turns, children, sigma, resume):
    account = admin_token()
    random = numpy.random.default_rng(12345)
    weights = load_weights() if resume and os.path.exists(BEST_FILE) else new_weights(7)
    center = weights
    seeds = [1000 + index for index in range(games)]
    best = evaluate(weights, account, seeds, turns)
    log = ['ОБУЧЕНИЕ СЕТИ — %s' % time.strftime('%Y-%m-%d %H:%M:%S'),
           'галактика %s, империй %d, ходов %d, партий на оценку %d, шаг мутации подстраивается'
           % (GALAXY, EMPIRES, turns, games),
           'поколение 0: доля мощи %.3f' % best]
    print(log[-1], flush=True)
    for line in log:
        note(line)

    start_sigma = sigma
    fruitless = 0
    for generation in range(1, generations + 1):
        improved = False
        brood = []
        for child in range(children):
            # ОДИН из потомков каждого поколения — ЧУЖАК, собранный с нуля, а не мутация
            # лучшего. Заведён по измерению: обученная сеть выбирала себе `Battleship`
            # 57 ходов из 60 — корпус, которого на родном мире не достроить за партию, — и
            # ни одна мутация этого не меняла. Причина в том, что решение сети это argmax
            # по списку проектов: пока у «корабля» большой перевес, мелкая правка весов не
            # меняет НИ ОДНОГО выбора стройки. Замерено: у лучшего и трёх его потомков при
            # sigma 0.4 стройка вышла одна и та же до последнего хода («корабль» 30 из 30),
            # менялась только наука. Сравнивать отбору почти нечего — отсюда двадцать
            # поколений «без улучшения». Чужак даёт то, чего мутация дать не может: другой
            # argmax.
            candidate = (new_weights(int(random.integers(1, 10 ** 6))) if child == 0
                         else mutate(center, sigma, random))
            value = evaluate(candidate, account, seeds, turns)
            brood.append((value, candidate))
            if value > best:
                best, weights, improved = value, candidate, True
                save_weights(weights)
        # Центр поиска съезжает к лучшей половине поколения — даже когда ни один потомок не
        # обошёл лучшее найденное. Вес потомка — его доля мощи: так лучшие тянут сильнее.
        brood.sort(key=lambda pair: pair[0], reverse=True)
        elite = brood[:max(2, int(len(brood) * ELITE_SHARE))]
        if sum(value for value, _ in elite) > 0:
            center = blend([one for _, one in elite], [value for value, _ in elite])
        # Шаг мутации правится ПОСЛЕ поколения, по его исходу: удачное — шире шаг, пустое
        # — уже. Так поиск не держится за одно число, подобранное вручную неделю назад.
        sigma = min(sigma * SIGMA_UP, SIGMA_MAX) if improved \
            else max(sigma * SIGMA_DOWN, SIGMA_MIN)
        fruitless = 0 if improved else fruitless + 1
        reheated = False
        if fruitless >= REHEAT_AFTER:
            sigma, fruitless, reheated = start_sigma, 0, True
        line = ('поколение %d: доля мощи %.3f%s, шаг %.3f%s'
                % (generation, best, '' if improved else ' (без улучшения)', sigma,
                   ' (разогрев)' if reheated else ''))
        log.append(line)
        print(line, flush=True)
        # Отчёт ДОПИСЫВАЕТСЯ, а не переписывается: обучение идёт несколькими запусками
        # подряд (поднимали силу мутации, меняли число потомков), и переписанный отчёт
        # стирал бы то, ради чего всё и затевалось, — как именно сеть росла. Лежит он в
        # рабочей папке проекта, а не во временной: временная живёт одну сессию.
        note(line)
    save_weights(weights)
    return best


def main():
    parser = argparse.ArgumentParser(description='Обучение сети игре в нашу игру')
    parser.add_argument('mode', choices=['train', 'play'])
    parser.add_argument('--generations', type=int, default=20)
    parser.add_argument('--games', type=int, default=2, help='партий на оценку весов')
    parser.add_argument('--turns', type=int, default=80)
    parser.add_argument('--children', type=int, default=4, help='потомков в поколении')
    parser.add_argument('--sigma', type=float, default=0.15,
                        help='НАЧАЛЬНАЯ сила мутации; дальше подстраивается сама')
    parser.add_argument('--resume', action='store_true', help='продолжить с лучших весов')
    parser.add_argument('--weights', default=BEST_FILE)
    parser.add_argument('--seed', type=int, default=999, help='зерно партии показа')
    args = parser.parse_args()

    if args.mode == 'play':
        weights = load_weights(args.weights)
        account = admin_token()
        tally = new_tally()
        value, error = play_game(weights, account, args.seed, args.turns, name='Показ',
                                 tally=tally)
        # Показ ОТЧИТЫВАЕТСЯ, а не только называет долю: доля одна ничего не говорит о
        # том, исследовала ли сеть, расселялась ли и выходила ли из родной системы, —
        # а первая же проверка обученной сети показала, что не выходила ни разу.
        print('наука: цели %d, изучено уровней %d'
              % (len(tally['research']), tally['acquired']))
        for turn, field, order, option in tally['research']:
            print('   ход %2d: %s уровень %s — %s' % (turn, field, order, option))
        print('стройка: %s' % (', '.join('%s %d' % pair
                                         for pair in tally['projects'].most_common())
                               or 'ничего'))
        print('корабли в строю: %s' % (', '.join('%s %d' % pair
                                                 for pair in tally['ships'].most_common())
                                       or 'нет'))
        print('перелётов %d, колоний основано кораблём %d, колоний к концу %d'
              % (tally['moves'], tally['colonized'], tally['colonies']))
        print('жителей переставляла %d раз' % tally['jobs'])
        print('доля мощи: %s%s' % (value, '' if not error else ' (%s)' % error))
        return
    best = train(args.generations, args.games, args.turns, args.children,
                 args.sigma, args.resume)
    print('лучшая доля мощи: %.3f; веса в %s' % (best, BEST_FILE))
    print('отчёт: %s' % REPORT_FILE)


if __name__ == '__main__':
    main()
