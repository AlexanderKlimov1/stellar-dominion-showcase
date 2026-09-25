"""
Сквозная проверка игры через REST — всё реализованное за один прогон.

Запуск при поднятом сервере:  python tools\regress.py

Отчёт кладётся рядом со скриптом в regress-report.txt и состоит из ТРЁХ частей:
входные параметры, результат и то, что прогон изменил на сервере. Строки «OK | такая-то
проверка» в файл не идут — их пятьсот, и за ними терялось единственное, что нужно:
провалы. Провалившиеся проверки печатаются полностью, с пояснением. Подробный ход
проверок включается переменной MOO3_TRACE и идёт в stderr.

Проверка создаёт свои партии И СВОИ УЧЁТНЫЕ ЗАПИСИ и удаляет за собой и то, и другое,
поэтому её можно гонять на той же базе, где идёт разработка. Записей она заводит
десятками (последний раздел нарочно упирается в предел регистраций с адреса), и без
уборки экран «Запросы на регистрацию» зарастает: однажды их набралось 1280 при двух
настоящих. Убирается по домену `@example.test` — он зарезервирован стандартом под
проверки, и ничего, кроме прогона, его здесь не заводит.
"""
import collections, io, json, math, os, re, sys, threading, time, urllib.request, urllib.error, uuid

# Печатаем по-русски, а консоль Windows отдаёт скрипту кодовую страницу, в которую
# кириллица не влезает: первое же сообщение о причине отказа роняло весь прогон
# UnicodeEncodeError — и роняло молча, вместо того чтобы эту причину назвать. Непереводимое
# заменяем, а не падаем: сообщение важнее точного вида букв.
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# Адрес сервера можно переопределить переменной окружения: когда на 8080 висит зависший
# процесс (Windows не всегда даёт его снять), проверку гоняют против запасного экземпляра
# на другом порту, не трогая скрипт.
BASE = os.environ.get('MOO3_BASE', 'http://localhost:8080')
#: Папка исходящих писем: у сервера разработки она своя (`mail-outbox-dev`), и без этого
#: две проверки письма проваливались на КАЖДОМ прогоне против него — прогон искал письмо
#: там, где его никто не кладёт. Провал, о котором известно заранее, хуже отсутствия
#: проверки: он приучает не смотреть на список провалов.
OUTBOX = os.environ.get('MOO3_OUTBOX',
                        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                     'mail-outbox'))
run_started = time.time()
report = []
failures = []
# Что прогон ИЗМЕНИЛ на сервере: убранные партии, записи, сохранения. В отчёт идёт только
# это, результат и входные параметры — остальное прогон производит для себя.
changes = []

# Пропуск учётной записи — п. 3.1: без него сервер не пускает ни в новую партию, ни в
# чужую, ни в сохранение. Ставится ниже, входом администратора: его пароль лежит рядом,
# в admin.txt, и регистрацию он не проходит.
account_token = None

# Сколько времени уходит на какой адрес. Прежде чем ускорять прогон, надо знать, где он
# стоит: на глаз это не видно вовсе — вызовов почти четыре сотни, а дороги из них единицы
# (создание галактики, прогон сотни ходов). Адреса схлопываются по шаблону —
# идентификатор партии в пути заменяется на {id}, — иначе каждая партия дала бы свою
# строку. Десяток самых дорогих печатается в конце отчёта.
timing = {}


def _timed(req, path):
    """Запрос с секундомером: ответ тот же, что и был, плюс строка в timing."""
    key = re.sub(r'/[0-9a-fA-F-]{8,}', '/{id}', path.split('?')[0])
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req) as r:
            text = r.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        return {'ERROR': e.code, 'body': json.loads(e.read().decode('utf-8'))}
    finally:
        row = timing.setdefault(key, [0, 0.0])
        row[0] += 1
        row[1] += time.perf_counter() - started


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    # Ответы просим по-русски: отчёт прогона читает русскоязычный хозяин проекта, а без
    # заголовка сервер отвечает по-английски — язык игры по умолчанию (п. 3.5).
    headers = {'Content-Type': 'application/json', 'Accept-Language': 'ru'}
    # Пропуск дублируется заголовком: по нему сервер считает предел частоты запросов
    # для игрока, а не для всего адреса.
    if isinstance(body, dict) and body.get('accessToken'):
        headers['X-Access-Token'] = body['accessToken']
    # Пропуск учётной записи — п. 3.1: нужен входам в партию, прочим запросам безвреден.
    if account_token:
        headers['X-Account-Token'] = account_token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    return _timed(req, path)


def call_lang(method, path, lang, token=None, body=None):
    """
    Тот же запрос, но на заданном языке — п. 3.5.

    Справочники игры двуязычны, и прогон просит ответы по-русски (их читает хозяин
    проекта). Но ОПОЗНАВАТЕЛЬ у технологии английский: из английского названия выведен её
    код, и ссылки внутри самих справочников (`starting_techs`, `recommended`) записаны
    по-английски. Всё, что сверяет такие ссылки, обязано спрашивать английский ответ —
    иначе сверяет русское с английским и не находит ничего.
    """
    headers = {'Content-Type': 'application/json', 'Accept-Language': lang}
    if token:
        headers['X-Access-Token'] = token
    if account_token:
        headers['X-Account-Token'] = account_token
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    return _timed(req, path)


def call_auth(method, path, token, body=None):
    """Тот же запрос, но пропуск идёт заголовком: в адресе ему больше не место."""
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Content-Type': 'application/json', 'X-Access-Token': token,
               'Accept-Language': 'ru'}
    if account_token:
        headers['X-Account-Token'] = account_token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    return _timed(req, path)


def call_account(method, path, token, body=None):
    """Запрос с пропуском учётной записи — п. 3.1: им игра узнаёт, кто вошёл."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Content-Type': 'application/json',
                                          'X-Account-Token': token})
    try:
        with urllib.request.urlopen(req) as r:
            text = r.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        return {'ERROR': e.code, 'body': json.loads(e.read().decode('utf-8') or 'null')}


#: Числа словами — из задачки регистрации (п. 3.1). Прогон решает её так же, как решил бы
#: написанный под эту игру бот: вопросов немного, слова свои, разбор — десяток строк. Это
#: и есть честная цена своей капчи, и в README о ней сказано прямо: она снимает фоновый
#: шум рассыльщиков, а от целенаправленной атаки защищают почта и пределы частоты.
CHALLENGE_WORDS = ['ноль', 'один', 'два', 'три', 'четыре', 'пять',
                   'шесть', 'семь', 'восемь', 'девять', 'десять']


def solved_challenge():
    """Берёт вопрос регистрации и решает его; пусто — задачка выключена на сервере."""
    task = call('GET', '/api/auth/challenge')
    if not task or 'id' not in task:
        return {}
    words = [w.strip('?').lower() for w in task['question'].split()]
    numbers = [CHALLENGE_WORDS.index(w) for w in words if w in CHALLENGE_WORDS]
    answer = numbers[0] + numbers[1] if 'плюс' in words else numbers[0] - numbers[1]
    return {'challengeId': task['id'], 'challengeAnswer': str(answer)}


#: Предел регистраций с адреса исчерпан — п. 3.1. Взводится первым же отказом 429 и
#: объясняет все следующие: прогон нарочно упирается в этот предел последним разделом,
#: поэтому второй прогон в тот же час до регистраций уже не доходит. Это защита, а не
#: поломка, но проверки, которым нужна свежая запись, обязаны сказать об этом словами, а
#: не падать на пустом списке.
REGISTRATIONS_SPENT = []


def register(body, expect_limit=False):
    """
    Заявка на регистрацию вместе с решённой задачкой — п. 3.1.

    `expect_limit` ставит проверка предела: она нарочно регистрируется до отказа, и
    предупреждать там не о чем — иначе обычный прогон каждый раз ругался бы на себя.
    """
    answer = call('POST', '/api/auth/register', dict(body, **solved_challenge()))
    if answer.get('ERROR') == 429 and not expect_limit and not REGISTRATIONS_SPENT:
        REGISTRATIONS_SPENT.append(answer.get('body', {}).get('message', 'отказ 429'))
        print('ВНИМАНИЕ: предел регистраций с этого адреса исчерпан '
              '(moo3.auth.registrations-per-hour). Перезапустите сервер или подождите час, '
              'иначе проверки регистрации не выполняются.')
    return answer


def registrations_spent():
    """Почему запись не завелась, если дело в пределе; иначе пусто."""
    return REGISTRATIONS_SPENT[0] if REGISTRATIONS_SPENT else ''


def probe(method, path, data=None, content_type='application/json'):
    """
    Сырой запрос: нужен код ответа и заголовки, а не разобранное тело.

    Ошибки клиента (чужой метод, чужой Content-Type, кривой путь) раньше приходили как
    500, и по телу их было не отличить от настоящей поломки. Здесь важен именно код и
    заголовок Allow, поэтому обычный call не годится — он отдаёт только тело. Retry-After
    приходит с отказом 429 защиты от подбора пароля — п. 3.1.
    """
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={'Content-Type': content_type})
    try:
        with urllib.request.urlopen(req) as r:
            return {'status': r.status, 'allow': r.headers.get('Allow', ''),
                    'retry_after': r.headers.get('Retry-After', '')}
    except urllib.error.HTTPError as e:
        return {'status': e.code, 'allow': e.headers.get('Allow', ''),
                'retry_after': e.headers.get('Retry-After', '')}


def end_turn(game_id, *tokens):
    """
    Конец хода — п. 11.1: ход считается, когда его закончили все игроки-люди партии,
    поэтому в партии на двоих закончить должны оба. ИИ ждать не заставляет.
    """
    result = None
    for token in tokens:
        result = call('POST', f'/api/games/{game_id}/turn/end', {'accessToken': token})
    return result


def ship_project(game_id, token, planet_id):
    """
    Код стройки корабля для этой колонии — п. 8. Кораблей у империи столько же, сколько
    проектов, поэтому в списке доступного они различаются кодом SHIP:<проект>, а не
    общим SHIP.
    """
    mm = call_auth('GET', f'/api/games/{game_id}/map', token)
    planet = [x for st in mm['systems'] for x in st['planets'] if x['id'] == planet_id][0]
    ships = [x['code'] for x in planet['colony']['available'] if x['code'].startswith('SHIP')]
    return ships[0] if ships else None


def build_ship(game_id, token, planet_id, *others):
    """
    Строит колонией корабль и ждёт его готовности — п. 8. Ход в партии на нескольких
    человек двигают все, поэтому остальные пропуска передаются следом.

    Базовые уровни Power и Chemistry у империи есть с первого хода (п. 9, стартовые
    технологии дерева), но спрашиваются они всё равно: проверка не должна зависеть от
    того, что именно выдаётся на старте.

    Ждётся НОВЫЙ флот, а не «хоть какой-нибудь»: у империи корабли к этому времени уже
    бывают, и «список флотов не пуст» значило бы «ничего не построили». На этом прогон и
    попался — списание получало пустой список только что построенного.
    """
    research_shipbuilding(game_id, token, *others)
    project = ship_project(game_id, token, planet_id)
    before = {one['id'] for one in call_auth('GET', f'/api/games/{game_id}/fleets', token)}
    call('POST', f'/api/games/{game_id}/planets/{planet_id}/project',
         {'accessToken': token, 'projectCode': project})
    for _ in range(30):
        end_turn(game_id, token, *others)
        fleets = call_auth('GET', f'/api/games/{game_id}/fleets', token)
        if any(one['id'] not in before for one in fleets):
            return fleets
        call('POST', f'/api/games/{game_id}/planets/{planet_id}/project',
             {'accessToken': token, 'projectCode': project})
    return []


def research_civil_ships(game_id, token, *others, limit=140):
    """
    Изучает Cold Fusion — п. 4.1, п. 8, п. 12: уровень общий, и колониальный корабль,
    застава и транспорт приходят разом.

    Без него гражданского корабля в списке стройки нет вовсе: строить технологию, которой
    у империи ещё нет, нельзя — и это правило проверяется отдельно, ниже по прогону.
    """
    option = 'colony-ship'
    acquired = call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']
    if any(x['optionCode'] == option for x in acquired):
        return True
    call('POST', f'/api/games/{game_id}/research',
         {'accessToken': token, 'categoryCode': 'power', 'levelOrder': 2, 'optionCode': option})
    for _ in range(limit):
        end_turn(game_id, token, *others)
        acquired = call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']
        if any(x['optionCode'] == option for x in acquired):
            return True
    return False


def build_civil(game_id, token, planet_id, project, role, *others, limit=40):
    """
    Строит колонией гражданский корабль и ждёт его готовности — п. 4.1, п. 8, п. 12.

    Цена у них твёрдая (застава и транспорт — 100, колониальный корабль — 500), поэтому
    ждать приходится десятки ходов: колония молодая, выработка небольшая. Возвращает флот
    с готовым кораблём или None.

    Сперва изучается сам корабль (Cold Fusion): до него его нет в списке стройки.
    """
    research_civil_ships(game_id, token, *others)
    call('POST', f'/api/games/{game_id}/planets/{planet_id}/project',
         {'accessToken': token, 'projectCode': project})
    for _ in range(limit):
        end_turn(game_id, token, *others)
        fleets = call_auth('GET', f'/api/games/{game_id}/fleets', token)
        for fleet in fleets:
            if any(ship['role'] == role and ship['ships'] > 0 for ship in fleet['composition']):
                return fleet
        # Стройку могло сбить событие или смена проекта — ставим её заново.
        call('POST', f'/api/games/{game_id}/planets/{planet_id}/project',
             {'accessToken': token, 'projectCode': project})
    return None


def research_fuel(game_id, token, *others, limit=140):
    """
    Изучает Deuterium Fuel Cells — п. 8: дальность империи растёт с 4 парсеков до 6, а с
    изученными баками (Chemistry, уровень 1) — до девяти.

    Это первый и самый дешёвый способ выбраться из стартового пузыря: в MOO II дальность
    даёт топливо, и до соседа обычно долетают именно на нём, а не на заставах.
    """
    option = 'deuterium-fuel-cells'
    acquired = call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']
    if any(x['optionCode'] == option for x in acquired):
        return True
    call('POST', f'/api/games/{game_id}/research',
         {'accessToken': token, 'categoryCode': 'chemistry', 'levelOrder': 2, 'optionCode': option})
    for _ in range(limit):
        end_turn(game_id, token, *others)
        acquired = call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']
        if any(x['optionCode'] == option for x in acquired):
            return True
    return False


def reach_towards(game_id, token, planet_id, target_id, *others, hops=6):
    """
    Раздвигает дальность империи до нужной звезды — п. 8.

    Дальность меряется от своих миров и растёт двумя способами, и оба здесь по порядку
    цены: сперва топливо (Deuterium — 250 ОИ), потом заставы. Корабль-застава садится на
    любую планету, даже негодную для жизни, и от неё топливо меряется так же, как от
    колонии, — этим в MOO II и выбираются из своего угла галактики.

    Возвращает True, если цель стала достижимой.
    """
    coords = {st['id']: (st['x'], st['y'])
              for st in call_auth('GET', f'/api/games/{game_id}/map?revealAll=true', token)['systems']}
    tx, ty = coords[target_id]

    if target_id in call_auth('GET', f'/api/games/{game_id}/ships', token)['reachableSystemIds']:
        return True
    research_fuel(game_id, token, *others)

    for _ in range(hops):
        ships = call_auth('GET', f'/api/games/{game_id}/ships', token)
        if target_id in ships['reachableSystemIds']:
            return True

        systems = call_auth('GET', f'/api/games/{game_id}/map?revealAll=true', token)['systems']
        # СИСТЕМУ ПОД ЧУДИЩЕМ ОБХОДИМ. Застава туда не ставится (сервер честно отказывает —
        # п. 11.1), да и корабль-застава гибнет, не долетев: сторож бьёт на подлёте. Игрок
        # обошёл бы такую звезду, и цепочка застав обязана делать то же — иначе прогон
        # встаёт на ровном месте. Особая звезда стоит в центре галактики и попадается на
        # пути чаще прочих, а стережёт её Страж, сильнейшее чудище игры.
        free = [st for st in systems
                if st['id'] in ships['reachableSystemIds']
                and st['planets']
                and not st.get('monster')
                and all(pl.get('ownerPlayerId') is None for pl in st['planets'])]
        if not free:
            return False
        spot = min(free, key=lambda st: (st['x'] - tx) ** 2 + (st['y'] - ty) ** 2)

        carrier = build_civil(game_id, token, planet_id, 'OUTPOST_SHIP', 'OUTPOST', *others)
        if carrier is None:
            return False

        # Ведём именно тот флот, в котором стоит корабль-застава: флотов у империи бывает
        # несколько, и «первый попавшийся» оказывался пустым как раз тогда, когда застава
        # была в другом.
        call('POST', f"/api/games/{game_id}/fleets/{carrier['id']}/move",
             {'accessToken': token, 'targetSystemId': spot['id']})
        landed = None
        for _ in range(30):
            end_turn(game_id, token, *others)
            landed = next(
                (f for f in call_auth('GET', f'/api/games/{game_id}/fleets', token)
                 if f['starSystemId'] == spot['id'] and not f.get('targetSystemId')
                 and any(sh['role'] == 'OUTPOST' and sh['ships'] > 0 for sh in f['composition'])),
                None)
            if landed:
                break
        if landed is None:
            return False

        call('POST', f"/api/games/{game_id}/fleets/{landed['id']}/outpost",
             {'accessToken': token, 'targetPlanetId': spot['planets'][0]['id']})

    return target_id in call_auth('GET', f'/api/games/{game_id}/ships', token)['reachableSystemIds']


def fly_to(game_id, token, target_id, *others, limit=40):
    """
    Ведёт единственный флот игрока к звезде и ждёт прибытия — п. 8.

    Перелёт занимает ходы, а дальше своей дальности флот за раз не улетает: до далёкой
    звезды он идёт по цепочке промежуточных, каждый раз выбирая ближайшую к цели из
    достижимых. Возвращает прибывший флот или None, если дороги нет.
    """
    coords = {st['id']: (st['x'], st['y'])
              for st in call_auth('GET', f'/api/games/{game_id}/map', token)['systems']}
    tx, ty = coords[target_id]

    def far(system_id):
        x, y = coords[system_id]
        return (x - tx) ** 2 + (y - ty) ** 2

    # Системы под чудищами прогон обходит стороной, и знает о них с раскрытой карты: он
    # проверяет встречу двух людей, а не то, как флот гибнет о сторожа. Цель обходу не
    # подлежит — если её саму стережёт чудище, лететь туда и надо.
    guarded = {st['id'] for st in
               call_auth('GET', f'/api/games/{game_id}/map?revealAll=true', token)['systems']
               if st.get('monster')} - {target_id}

    visited = set()
    chosen = None
    for _ in range(limit):
        ships = call_auth('GET', f'/api/games/{game_id}/ships', token)
        fleets = ships['fleets']
        if not fleets:
            return None
        # Ведём один и тот же флот, и выбираем его не «первым попавшимся»: у империи,
        # которая только что тянулась заставами, флотов несколько, и первым в списке
        # оказывался то застава в пути, то вовсе не тот корабль. Берём стоящий флот,
        # ближайший к цели, и дальше ходим только им.
        fleet = next((f for f in fleets if f['id'] == chosen), None)
        if fleet is None:
            standing = [f for f in fleets if not f.get('targetSystemId')]
            if not standing:
                end_turn(game_id, token, *others)
                continue
            fleet = min(standing, key=lambda f: far(f['starSystemId']))
            chosen = fleet['id']
        if fleet.get('targetSystemId'):
            end_turn(game_id, token, *others)
            continue
        if fleet['starSystemId'] == target_id:
            return fleet
        visited.add(fleet['starSystemId'])
        # Ближайшая к цели из достижимых. Обычно это шаг вперёд, но если вперёд дороги нет
        # (разрыв шире дальности), годится и шаг вбок — лишь бы не туда, где уже были:
        # иначе флот принялся бы ходить между двумя звёздами.
        hops = [x for x in ships['reachableSystemIds'] if x not in visited and x not in guarded]
        forward = [x for x in hops if far(x) < far(fleet['starSystemId'])]
        hop = target_id if target_id in ships['reachableSystemIds'] else (
            min(forward or hops, key=far) if hops else None)
        if hop is None:
            return None
        call('POST', f"/api/games/{game_id}/fleets/{fleet['id']}/move",
             {'accessToken': token, 'targetSystemId': hop})
        end_turn(game_id, token, *others)
    return None


def research_shipbuilding(game_id, token, *others, limit=40):
    """
    Изучает то, без чего кораблей не строят, — п. 8: базовые уровни Power и Chemistry.

    Power даёт двигатель, Chemistry — топливо; до них ни окна дизайна, ни корабля в списке
    стройки. Оба уровня общие: изучив уровень, империя получает все его технологии разом,
    поэтому спрашиваем по одной из каждого.
    """
    for category, option in (('power', 'nuclear-drive'), ('chemistry', 'standard-fuel-cells')):
        acquired = call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']
        if any(x['optionCode'] == option for x in acquired):
            continue
        call('POST', f'/api/games/{game_id}/research',
             {'accessToken': token, 'categoryCode': category, 'levelOrder': 1,
              'optionCode': option})
        for _ in range(limit):
            end_turn(game_id, token, *others)
            acquired = call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']
            if any(x['optionCode'] == option for x in acquired):
                break
    return call_auth('GET', f'/api/games/{game_id}/research', token)['acquired']


# След по проверкам: `MOO3_TRACE=1` печатает каждую пройденную проверку сразу, в stderr.
# Заведено после того, как прогон завис НАСМЕРТЬ и узнать, на чём именно, было нечем:
# отчёт пишется одним куском в самом конце, сервер при этом не обрабатывал ни одного
# запроса, а база не держала ни одного замка. Последняя напечатанная строка и есть ответ
# на вопрос «где он стоит».
trace = os.environ.get('MOO3_TRACE') == '1'


def check(name, ok, detail=''):
    report.append(('OK  ' if ok else 'ПРОВАЛ') + ' | ' + name + (' — ' + detail if detail else ''))
    if not ok:
        failures.append(name)
    if trace:
        print(('OK  ' if ok else 'ПРОВАЛ') + ' | ' + name, file=sys.stderr, flush=True)


# --- 0. Вход в игру (п. 3.1) ---
# До партии игрок называет себя: без пропуска учётной записи сервер не создаёт партию,
# не пускает в чужую и не поднимает сохранение. Проверка входит администратором — он
# заводится при первом запуске сервера, и его пароль лежит в admin.txt рядом с игрой.
denied = call('POST', '/api/games', {'name': 'Без входа', 'playerName': 'Никто'})
check('без учётной записи партия не создаётся — п. 3.1', denied.get('ERROR') == 403,
      str(denied.get('body', {}).get('message')))

admin_file = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'admin.txt')
admin_password = None
if os.path.exists(admin_file):
    for line in io.open(admin_file, encoding='utf-8'):
        if line.startswith('пароль:'):
            admin_password = line.split(':', 1)[1].strip()
check('файл администратора создан при первом запуске — п. 3.1', admin_password is not None,
      admin_file)

signed = call('POST', '/api/auth/login', {'login': 'admin', 'password': admin_password or ''})
account_token = signed.get('token')
check('администратор входит без регистрации — п. 3.1',
      account_token is not None and signed['account']['role'] == 'ADMIN',
      str(signed.get('body', signed.get('account'))))

# Уборка БРОШЕННЫХ партий — тех, что остались от оборвавшегося прогона. Прогон убирает за
# собой сам, но если он упал посередине (упал скрипт, остановили сервер), пропуск хозяина
# его партий не знает уже никто, и удалить их нечем: копятся они быстро — в базе однажды
# набралось восемь с половиной сотен. Убирает администратор, и только СВОИ имена: чужую
# партию на этом сервере трогать нельзя, даже брошенную.
HARNESS_NAMES = ('Регресс', 'Наблюдение', 'Расселение', 'Тактика', 'Баланс прогон',
                 'Прогон', 'Дипломатия', 'Шпионаж', 'Лидеры', 'Сохранение', 'Загруженная',
                 'Совет')
# ПАРТИИ ИДУЩЕГО БАЛАНСОВОГО ПРОГОНА УБИРАТЬ НЕЛЬЗЯ. Пульт называет свои партии
# «Прогон <зерно>» (`BalanceGameRunner.play`), а «Прогон» стоит в списке выше — и уборка
# сносила живые партии замера прямо посреди счёта. Замер собирает их по порядку и на
# первой же пропавшей упал бы целиком, похоронив час работы. Поэтому, пока прогон идёт,
# его партии не трогаются вовсе: брошенными они не являются.
runs_now = call('GET', '/api/balance/runs')
running_run = isinstance(runs_now, list) and any(r.get('status') == 'RUNNING' for r in runs_now)
sweep_names = tuple(one for one in HARNESS_NAMES if not (running_run and one == 'Прогон'))
abandoned = [g for g in call('GET', '/api/games')
             if any(g['name'].startswith(one) for one in sweep_names)]
for g in abandoned:
    call('DELETE', f"/api/games/{g['id']}/admin")
left = [g for g in call('GET', '/api/games')
        if any(g['name'].startswith(one) for one in sweep_names)]
check('брошенные партии прошлых прогонов убраны — уборка за собой',
      not left, f'было {len(abandoned)}, осталось {len(left)}'
              + ('; партии идущего балансового прогона не трогались' if running_run else ''))
changes.append(f'убрано брошенных партий: {len(abandoned)}')


# УЧЁТНЫЕ ЗАПИСИ ПРОГОН ТОЖЕ УБИРАЕТ ЗА СОБОЙ — п. 3.1.
# Каждый прогон заводит их десятками: один «Регресс», один «Застрявший», один «Новый» и
# ШЕСТЬ «Наплывов» — последний раздел нарочно упирается в предел регистраций с адреса.
# За собой они не убирались, и экран «Запросы на регистрацию» зарастал: в базе однажды
# набралось 1280 записей при двух настоящих. Убирается по домену: `.test` — домен,
# зарезервированный стандартом под проверки, и ничего, кроме прогона, его здесь не
# заводит. Чужого на этом сервере уборка не трогает, как и с партиями.
TEST_MAIL_DOMAIN = '@example.test'


def sweep_accounts():
    """Снести тестовые записи; вернуть, сколько снесено. Администратора не трогает.

    ЖДЁТ, ПОКА ОТПУСТИТ ПРЕДЕЛ ЧАСТОТЫ. Последний раздел прогона нарочно устраивает наплыв
    в двадцать потоков и упирается в `RateLimitFilter`, а уборка идёт сразу за ним — и
    первый же запрос списка записей получал 429. Список тогда приходил не списком, уборка
    честно сносила ноль, и проверка «прогон убрал свои записи» проваливалась с «снесено 0,
    осталось 9». Ведро адреса наполняется за секунды, поэтому хватает нескольких попыток с
    паузой; молча вернуть ноль нельзя — это и есть та самая тихая уборка, из-за которой
    записи копились сотнями.
    """
    # ЗАХОДОВ НЕСКОЛЬКО, И ЭТО НЕ ПЕРЕСТРАХОВКА. Предел частоты рубит не только чтение
    # списка, но и САМИ УДАЛЕНИЯ: первый заход снёс девять записей из пятнадцати, а
    # остальные шесть получили 429 — и уборка отчиталась бы «снесено девять», не заметив
    # остатка. Поэтому заходим заново, пока тестовых записей не останется совсем.
    gone = 0
    for attempt in range(10):
        rows = call('GET', '/api/auth/accounts')
        if not isinstance(rows, list):
            time.sleep(2)
            continue
        doomed = [one for one in rows
                  if str(one.get('login', '')).lower().endswith(TEST_MAIL_DOMAIN)
                  and one.get('role') != 'ADMIN']
        if not doomed:
            return gone
        for one in doomed:
            if call('DELETE', f"/api/auth/accounts/{one['id']}") is None:
                gone += 1
        time.sleep(1)
    return gone


# В начале прогона убираются записи ПРОШЛЫХ прогонов — тех, что оборвались и убрать за
# собой не успели. Свои этот прогон заведёт ниже и снесёт в самом конце.
stale_accounts = sweep_accounts()
stale_now = call('GET', '/api/auth/accounts')
stale_now = stale_now if isinstance(stale_now, list) else []
check('брошенные учётные записи прошлых прогонов убраны — уборка за собой',
      not [one for one in stale_now
           if isinstance(one, dict)
           and str(one.get('login', '')).lower().endswith(TEST_MAIL_DOMAIN)],
      f'было {stale_accounts}')
changes.append(f'убрано брошенных учётных записей: {stale_accounts}')

# Убирать партии вправе только администратор: это хозяйство сервера, как и сохранения.
guard = call('POST', '/api/games', {'name': 'Регресс уборка', 'galaxySize': 'SMALL',
                                    'playerName': 'Хозяин'})
saved_token, account_token = account_token, None
without_admin = call('DELETE', f"/api/games/{guard['game']['id']}/admin")
account_token = saved_token
check('убрать партию без прав администратора нельзя — п. 3.1',
      without_admin.get('ERROR') in (401, 403), str(without_admin.get('ERROR')))
call('DELETE', f"/api/games/{guard['game']['id']}/admin")
check('администратор убирает партию без пропуска её хозяина',
      all(g['id'] != guard['game']['id'] for g in call('GET', '/api/games')))

# Без пропуска администратора прогон бессмыслен: партии заводятся по нему, и дальше всё
# отвечало бы отказом. Отдельно назван самый частый случай — счётчики защиты входа
# (п. 3.1), которые этот же прогон и тратит своими проверками подбора и наплыва
# регистраций. Они живут в памяти, поэтому лечится перезапуском сервера.
if account_token is None:
    print('Прогон невозможен: администратор не вошёл — '
          + str(signed.get('body', {}).get('message', signed)))
    if signed.get('ERROR') == 429:
        print('Это счётчики защиты входа: они в памяти сервера. Перезапустите '
              'moo3-server\\run.cmd и повторите прогон.')
    raise SystemExit(1)

wrong = call('POST', '/api/auth/login', {'login': 'admin', 'password': 'не тот пароль'})
check('неверный пароль не пускает — п. 3.1', wrong.get('ERROR') == 403,
      str(wrong.get('body', {}).get('message')))

# Регистрация игрока: запись заводится, но до подтверждения почты вход закрыт — п. 3.1.
# Логина в заявке нет: логином служит сама почта, а имя идёт только на показ в игре.
new_login = 'regress' + str(int(time.time())) + '@example.test'
registered = register({'email': new_login, 'name': 'Регресс', 'password': 'orion-2026'})
check('регистрация заводит запись и шлёт ссылку — п. 3.1',
      registered.get('login') == new_login and registered.get('notice'),
      str(registered.get('notice', registered)))
check('логином записи становится сама почта — п. 3.1',
      registered.get('login') == registered.get('email'),
      f"{registered.get('login')} / {registered.get('email')}")
before_confirm = call('POST', '/api/auth/login', {'login': new_login, 'password': 'orion-2026'})
check('до подтверждения почты вход закрыт — п. 3.1', before_confirm.get('ERROR') == 403,
      str(before_confirm.get('body', {}).get('message')))
check('почта занята: второй раз её не занять — п. 3.1',
      register({'email': new_login.upper(), 'name': 'Двойник',
                'password': 'orion-2026'}).get('ERROR') == 409)
check('короткий пароль не принимается — п. 3.1',
      register({'email': 'x' + new_login, 'name': 'Регресс', 'password': 'коротко'})
      .get('ERROR') in (400, 409))
check('без имени регистрации нет — п. 3.1',
      register({'email': 'y' + new_login, 'name': '  ', 'password': 'orion-2026'})
      .get('ERROR') in (400, 409))

# Задачка «докажите, что вы человек» — п. 3.1. Проверяется то, ради чего она заведена:
# без ответа и с неверным ответом заявка не проходит, а решённый вопрос тратится.
solved = solved_challenge()
if solved:
    check('регистрация без ответа на задачку не проходит — п. 3.1',
          call('POST', '/api/auth/register',
               {'email': 'z' + new_login, 'name': 'Робот',
                'password': 'orion-2026'}).get('ERROR') == 400)
    check('неверный ответ на задачку не проходит — п. 3.1',
          call('POST', '/api/auth/register',
               {'email': 'z' + new_login, 'name': 'Робот', 'password': 'orion-2026',
                'challengeId': solved['challengeId'],
                'challengeAnswer': str(int(solved['challengeAnswer']) + 1)}).get('ERROR') == 400)
    # Тот же вопрос второй раз не годится: неверный ответ его уже потратил, и верный
    # теперь опоздал. Иначе одна решённая задачка открывала бы дорогу тысяче заявок.
    check('вопрос задачки одноразовый — п. 3.1',
          call('POST', '/api/auth/register',
               dict({'email': 'z' + new_login, 'name': 'Робот',
                     'password': 'orion-2026'}, **solved)).get('ERROR') == 400)

# Ссылка из письма — п. 3.1. Путь письма зависит от настроек сервера, и проверка идёт
# по тому пути, который включён: с настроенным SMTP письмо ушло на почтовый сервер и
# читать его отсюда нечем, без него — лежит в папке исходящих, и тогда проверяется вся
# цепочка: письмо есть, ссылка в нём рабочая, по ней запись подтверждается.
if registered.get('mailSent'):
    check('письмо ушло на почтовый сервер — п. 3.1',
          'отправлено' in (registered.get('notice') or ''), registered.get('notice'))
    confirm_token = None
else:
    outbox = OUTBOX
    letters = [f for f in os.listdir(outbox) if new_login in f] if os.path.isdir(outbox) else []
    letter = io.open(os.path.join(outbox, letters[-1]), encoding='utf-8').read() if letters else ''
    found = re.search(r'confirm=(\S+)', letter)
    check('письмо со ссылкой ушло игроку — п. 3.1', found is not None,
          letters[-1] if letters else 'письма нет в ' + outbox)
    confirm_token = found.group(1) if found else None

player_token = None
if confirm_token:
    confirmed = call('POST', '/api/auth/confirm?token=' + confirm_token)
    player_token = confirmed.get('token')
    check('ссылка из письма подтверждает запись и пускает в игру — п. 3.1',
          confirmed.get('token') is not None and confirmed['account']['login'] == new_login,
          str(confirmed.get('body', confirmed.get('account'))))
    check('ссылка одноразовая — п. 3.1',
          call('POST', '/api/auth/confirm?token=' + confirm_token).get('ERROR') == 403)
    check('после подтверждения вход по паролю открыт — п. 3.1',
          call('POST', '/api/auth/login', {'login': new_login, 'password': 'orion-2026'})
          .get('token') is not None)

check('чужая ссылка подтверждения не годится — п. 3.1',
      call('POST', '/api/auth/confirm?token=' + 'x' * 43).get('ERROR') == 403)

# Язык живёт в учётной записи — п. 3.5. До этого он лежал только в localStorage браузера:
# игрок выбирал русский, садился за другую машину — и получал английский заново. Прогон
# просит ответы по-русски, поэтому запись заводится русской; «другая машина» здесь — это
# запрос с другим Accept-Language, и язык обязан прийти ИЗ ЗАПИСИ, а не из запроса.
if player_token:
    check('запись помнит язык, на котором игрок зарегистрировался — п. 3.5',
          confirmed['account'].get('locale') == 'ru', str(confirmed['account'].get('locale')))
    elsewhere = call_lang('POST', '/api/auth/login', 'en',
                          body={'login': new_login, 'password': 'orion-2026'})
    check('вход с другой машины отдаёт язык записи, а не язык запроса — п. 3.5',
          elsewhere['account'].get('locale') == 'ru',
          str(elsewhere['account'].get('locale')))
    saved_account, account_token = account_token, player_token
    changed = call('PUT', '/api/auth/locale', {'locale': 'en'})
    check('переключатель языка записывает выбор в запись — п. 3.5',
          changed.get('locale') == 'en', str(changed))
    check('записанный язык виден при следующем входе — п. 3.5',
          call('GET', '/api/auth/me').get('locale') == 'en',
          str(call('GET', '/api/auth/me').get('locale')))
    # Игра знает два языка, и запись третьего выглядела бы настройкой, которая ничего не
    # делает: отказ честнее.
    check('незнакомый язык не записывается — п. 3.5',
          call('PUT', '/api/auth/locale', {'locale': 'de'}).get('ERROR') == 400)
    call('PUT', '/api/auth/locale', {'locale': 'ru'})

    # Размер текста интерфейса — п. 11.1: живёт там же, где язык, и по той же причине.
    # Настройка, лежащая только в браузере, теряется на второй машине, а размер текста
    # выбирают один раз и надолго.
    check('новая запись размера текста не называет — п. 11.1',
          confirmed['account'].get('textScale') in (None, 100),
          str(confirmed['account'].get('textScale')))
    bigger = call('PUT', '/api/auth/text-scale', {'textScale': 130})
    check('переключатель размера записывает выбор в запись — п. 11.1',
          bigger.get('textScale') == 130, str(bigger))
    check('записанный размер виден при следующем входе — п. 11.1',
          call('GET', '/api/auth/me').get('textScale') == 130,
          str(call('GET', '/api/auth/me').get('textScale')))
    # Список ступеней закрыт: каждую смотрели глазами на всех экранах, и «180» было бы
    # настройкой, которой интерфейс не подчиняется.
    check('ступень не из списка не записывается — п. 11.1',
          call('PUT', '/api/auth/text-scale', {'textScale': 180}).get('ERROR') == 400)
    call('PUT', '/api/auth/text-scale', {'textScale': 100})
    account_token = saved_account

check('без входа язык записи не меняется — п. 3.5',
      probe('PUT', '/api/auth/locale',
            json.dumps({'locale': 'ru'}).encode())['status'] in (401, 403))

check('без входа размер текста не меняется — п. 11.1',
      probe('PUT', '/api/auth/text-scale',
            json.dumps({'textScale': 130}).encode())['status'] in (401, 403))

# Повторная отправка письма — п. 3.1. Без неё регистрация была ловушкой: письмо не дошло —
# логин занят, вход закрыт, и сделать нельзя ничего. Проверяется на своей записи, целиком:
# вход закрыт, чужому паролю отказ, новая ссылка приходит, старая перестаёт годиться.
stuck_login = 'zastryal%d' % int(time.time())
stuck_email = stuck_login + '@example.test'
stuck = register({'email': stuck_email, 'name': 'Застрявший',
                  'password': 'orion-2026'})
check('регистрация называет, где искать письмо — п. 3.1',
      bool(stuck.get('notice')), str(stuck.get('notice')))
check('до подтверждения вход закрыт, и сказано, что делать — п. 3.1',
      call('POST', '/api/auth/login',
           {'login': stuck_email, 'password': 'orion-2026'}).get('ERROR') == 403)
check('письмо заново по чужому паролю не выслать — п. 3.1',
      call('POST', '/api/auth/resend',
           {'login': stuck_email, 'password': 'ne-tot-parol'}).get('ERROR') == 403)

stuck_again = call('POST', '/api/auth/resend',
                   {'login': stuck_email, 'password': 'orion-2026'})
check('письмо высылается заново по логину или почте — п. 3.1',
      bool(stuck_again.get('notice')) and 'ERROR' not in stuck_again,
      str(stuck_again.get('body', stuck_again.get('notice'))))

# Ссылки читаются из исходящих: с настроенным SMTP их отсюда не достать, и тогда
# проверяется только то, что сервер согласился выслать письмо заново.
if not stuck_again.get('mailSent'):
    outbox_dir = OUTBOX
    letters = sorted(f for f in os.listdir(outbox_dir) if stuck_login in f)
    tokens = []
    for name in letters:
        letter = io.open(os.path.join(outbox_dir, name), encoding='utf-8').read()
        found = re.search(r'confirm=(\S+)', letter)
        if found:
            tokens.append(found.group(1))
    check('писем стало два, и ссылки в них разные — п. 3.1',
          len(tokens) == 2 and tokens[0] != tokens[1], str(len(tokens)))
    if len(tokens) == 2:
        check('старая ссылка после повторного письма не годится — п. 3.1',
              call('POST', '/api/auth/confirm?token=' + tokens[0]).get('ERROR') == 403)
        opened = call('POST', '/api/auth/confirm?token=' + tokens[1])
        check('новая ссылка подтверждает запись — п. 3.1',
              opened.get('token') is not None, str(opened.get('body', opened.get('account'))))
        check('подтверждённой записи письмо заново не шлют — п. 3.1',
              call('POST', '/api/auth/resend',
                   {'login': stuck_email, 'password': 'orion-2026'}).get('ERROR') == 409)
    for name in letters:
        os.remove(os.path.join(outbox_dir, name))

# Подтверждение записи администратором — п. 3.1: последний ключ от застрявшей
# регистрации, когда почта не работает вовсе. Заводим свою застрявшую запись и открываем
# её из интерфейса администратора.
admin_case = 'adminconfirm%d@example.test' % int(time.time())
register({'email': admin_case, 'name': 'Ждущий', 'password': 'orion-2026'})
check('до подтверждения запись в игру не пускает — п. 3.1',
      call('POST', '/api/auth/login',
           {'login': admin_case, 'password': 'orion-2026'}).get('ERROR') == 403)

check('список записей закрыт без пропуска администратора — п. 3.1',
      probe('GET', '/api/auth/accounts')['status'] == 403)
if player_token:
    check('список записей закрыт обычному игроку — п. 3.1',
          call_account('GET', '/api/auth/accounts', player_token).get('ERROR') == 403)

accounts = call('GET', '/api/auth/accounts')
waiting = [a for a in accounts if a['login'] == admin_case]
check('администратор видит записи с их именами и почтой — п. 3.1',
      len(waiting) == 1 and waiting[0]['name'] == 'Ждущий'
      and waiting[0]['confirmed'] is False,
      registrations_spent() or str(waiting[:1]))

if waiting:
    opened = call('POST', f"/api/auth/accounts/{waiting[0]['id']}/confirm")
    check('администратор подтверждает застрявшую запись — п. 3.1',
          opened.get('confirmed') is True, str(opened.get('body', opened)))
    check('после подтверждения администратором вход открыт — п. 3.1',
          call('POST', '/api/auth/login',
               {'login': admin_case, 'password': 'orion-2026'}).get('token') is not None)
    check('подтверждать дважды нечего — п. 3.1',
          call('POST', f"/api/auth/accounts/{waiting[0]['id']}/confirm").get('ERROR') == 409)

    # Удаление записи администратором — п. 3.1: брошенные заявки иначе висят вечно, а
    # занятая почта не освобождается сама.
    check('удаление записи закрыто без пропуска администратора — п. 3.1',
          probe('DELETE', f"/api/auth/accounts/{waiting[0]['id']}")['status'] == 403)
    check('свою запись администратор не удаляет — п. 3.1',
          call('DELETE', f"/api/auth/accounts/{signed['account']['id']}").get('ERROR') == 409)

    # Удачное удаление отвечает 204 без тела, и call отдаёт на него None.
    removed = call('DELETE', f"/api/auth/accounts/{waiting[0]['id']}") or {}
    check('администратор удаляет учётную запись — п. 3.1', removed.get('ERROR') is None,
          str(removed))
    check('удалённой записи в списке нет — п. 3.1',
          all(a['login'] != admin_case for a in call('GET', '/api/auth/accounts')))
    # Почта освободилась: на неё снова можно завести запись — ради этого удаление и есть.
    check('удалённая почта освобождается — п. 3.1',
          register({'email': admin_case, 'name': 'Новый', 'password': 'orion-2026'})
          .get('login') == admin_case)
    check('удалять нечего дважды — п. 3.1',
          call('DELETE', f"/api/auth/accounts/{waiting[0]['id']}").get('ERROR') == 404)
else:
    # Записи нет — проверять нечего, и падать на пустом списке тоже незачем: причина
    # называется прямо, а прогон идёт дальше.
    check('администратор подтверждает застрявшую запись — п. 3.1', False,
          registrations_spent() or 'запись не завелась, и это не предел регистраций')

# Пропуск учётной записи живёт до выхода: выход гасит его сразу, а не по сроку — п. 3.1.
# Берётся пропуск администратора: он есть всегда, а запись игрока могла остаться
# неподтверждённой — при настроенном SMTP ссылку из письма отсюда не прочитать.
second = call('POST', '/api/auth/login', {'login': 'admin', 'password': admin_password or ''})
if second.get('token'):
    me = call_account('GET', '/api/auth/me', second['token'])
    check('пропуск называет вошедшего — п. 3.1', me.get('login') == 'admin', str(me))
    call_account('POST', '/api/auth/logout', second['token'])
    check('после выхода пропуск не действует — п. 3.1',
          call_account('GET', '/api/auth/me', second['token']).get('ERROR') == 403)
else:
    # Вход администратора не удался — почти наверняка счётчик неудачных попыток с адреса
    # (20 за 15 минут), который этот же прогон и тратит своей проверкой подбора. Падать
    # на отсутствующем пропуске незачем: причина называется, прогон идёт дальше.
    check('пропуск называет вошедшего — п. 3.1', False,
          str(second.get('body', {}).get('message', second)))
# Пропуск идёт заголовком, а заголовки — латиница: выдуманный пропуск тоже пишем ею.
check('чужой пропуск не действует — п. 3.1',
      call_account('GET', '/api/auth/me', 'no-such-token').get('ERROR') == 403)

# Защита от подбора пароля — п. 3.1. Перебирается выдуманный логин: настоящую запись
# проверка не запирает, а счётчик считает и её тоже — он не спрашивает, есть ли такая.
# Стоит в конце раздела: дальше вход этими логинами уже не нужен.
guess_login = 'podbor%d@example.test' % int(time.time())
guess_body = json.dumps({'login': guess_login, 'password': 'popytka'}).encode()
# Стучимся до первого замка, а не заданное число раз: каждая лишняя неудача тратит и
# счётчик адреса (20 за 15 минут), общий на весь прогон, — а он нужен ещё входу
# администратора ниже по тексту.
attempts = []
for _ in range(7):
    attempts.append(probe('POST', '/api/auth/login', guess_body))
    if attempts[-1]['status'] == 429:
        break
codes = [a['status'] for a in attempts]
locked = [a for a in attempts if a['status'] == 429]
check('подбор пароля упирается в замок — п. 3.1', bool(locked), str(codes))
check('замок называет, сколько ждать — п. 3.1',
      bool(locked) and locked[0]['retry_after'].isdigit() and int(locked[0]['retry_after']) > 0,
      str(locked[0]['retry_after']) if locked else 'замка не было')
check('замок на выдуманный логин не запирает вход другим — п. 3.1',
      call('POST', '/api/auth/login',
           {'login': 'admin', 'password': admin_password or ''}).get('token') is not None)

# --- 0.1. Случайные галактические события (п. 11.1) ---
# В MOO II события выключаются в окне новой игры, и партия помнит этот выбор. Проверяется
# и то и другое: признак доезжает до партии и переживает сохранение, выключенные события
# не случаются, а включённые — случаются и попадают в отчёты ходов.
events_off = call('POST', '/api/games', {'name': 'Без событий', 'playerName': 'Тихий',
                                         'galaxySize': 'SMALL', 'raceTraits': [],
                                         'galacticEvents': False})
off_id, off_tok = events_off['game']['id'], events_off['credentials']['accessToken']
check('партия помнит, что события выключены — п. 11.1',
      events_off['game']['galacticEvents'] is False, str(events_off['game']['galacticEvents']))
call('POST', f'/api/games/{off_id}/start', {'accessToken': off_tok})

off_events = 0
for _ in range(60):
    result = end_turn(off_id, off_tok)
    off_events += len([e for e in ((result or {}).get('report') or {}).get('events', [])
                       if e['code'] == 'GALACTIC'])
check('выключенные события не случаются — п. 11.1', off_events == 0, f'событий {off_events}')

off_save = call('POST', f'/api/games/{off_id}/save', {'accessToken': off_tok})
off_loaded = call('POST', f"/api/saves/{off_save['id']}/load")
check('сохранение помнит выключенные события — п. 11.1',
      off_loaded['game']['galacticEvents'] is False, str(off_loaded['game']['galacticEvents']))
call('DELETE', f'/api/games/{off_id}?accessToken={off_tok}')
# Поднятое сохранение — это НОВАЯ партия, и убирать её надо отдельно: прогон обещает
# чистую базу, а копил по партии за прогон, пока это не заметили в базе балансировки.
call('DELETE', f"/api/games/{off_loaded['game']['id']}"
               f"?accessToken={off_loaded['credentials']['accessToken']}")

# Включённые события: жребий берётся от зерна партии и хода, поэтому ждём ходами, а не
# проверяем сразу — за ход что-то случается примерно в одной партии из двенадцати.
events_on = call('POST', '/api/games', {'name': 'С событиями', 'playerName': 'Летописец',
                                        'galaxySize': 'SMALL', 'raceTraits': [],
                                        'galacticEvents': True})
on_id, on_tok = events_on['game']['id'], events_on['credentials']['accessToken']
default_events = call('POST', '/api/games', {'name': 'По умолчанию', 'playerName': 'Т',
                                             'galaxySize': 'SMALL', 'raceTraits': []})
check('события включены по умолчанию, как в оригинале — п. 11.1',
      default_events['game']['galacticEvents'] is True)
call('DELETE', f"/api/games/{default_events['game']['id']}"
               f"?accessToken={default_events['credentials']['accessToken']}")
call('POST', f'/api/games/{on_id}/start', {'accessToken': on_tok})

on_events = []
for _ in range(150):
    result = end_turn(on_id, on_tok)
    on_events += [e['text'] for e in ((result or {}).get('report') or {}).get('events', [])
                  if e['code'] == 'GALACTIC']
    if on_events:
        break
# Событие могло достаться и соседу: игроку видно только своё. Ход при этом обязан
# считаться до конца — раньше подарок технологии из события ронял его целиком.
check('партия с событиями считается без ошибок — п. 11.1',
      call_auth('GET', f'/api/games/{on_id}', on_tok)['game']['turn'] > 1,
      str(on_events[:2]))
call('DELETE', f'/api/games/{on_id}?accessToken={on_tok}')

# --- 1. Лобби и старт партии (п. 3.1, 3.2) ---
# Раса задана и пуста намеренно. Без неё игрок получил бы первую свободную расу
# справочника — а с тех пор, как готовые расы MOO II обзавелись своими сторонами (п. 5),
# это Human с демократией, и выработка колонии зависела бы от того, кто стоит в
# справочнике первым. Проверки этого раздела про колонию, а не про расу.
#
# Галактические события в этой партии выключены намеренно. Они проверяются своими
# партиями выше (раздел 0.5), а здесь только мешают: событие успевает разрушить звёздную
# базу родного мира посреди проверки зданий, и тогда содержание колонии падает вместо
# того, чтобы вырасти на цену построенного, а снесённая база возвращается в список
# стройки. Зерно у партии остаётся случайным — галактики должны быть разными, — а вот
# беда посреди замера делает проверку плавающей, и она дважды провалилась именно так.
g = call('POST', '/api/games', {'name': 'Регресс', 'playerName': 'Тест',
                                'homeStarName': 'Родная', 'galaxySize': 'SMALL',
                                'raceName': 'Безликие', 'raceTraits': [],
                                'galacticEvents': False})
gid, tok, pid = g['game']['id'], g['credentials']['accessToken'], g['credentials']['playerId']
check('создание игры', g['game']['name'] == 'Регресс' and g['credentials']['host'] is True)
check('игра в списке открытых', any(x['id'] == gid for x in call('GET', '/api/games?openOnly=true')))
check('состав лобби', len(call('GET', f'/api/games/{gid}')['players']) == 1)

start = call('POST', f'/api/games/{gid}/start', {'accessToken': tok})
check('старт: добор ИИ до 8', len(start['players']) == 8,
      f"игроков {len(start['players'])}")

# --- 2. Карта галактики (п. 4.2, 11.3) ---
m = call('GET', f'/api/games/{gid}/map?accessToken={tok}')
check('галактика сгенерирована', len(m['systems']) == 33, f"систем {len(m['systems'])}")
# Метка особой звезды скрыта вместе с названием у неразведанных систем, поэтому ищем её
# на раскрытой карте — см. README, раздел «Видимость систем».
revealed = call('GET', f'/api/games/{gid}/map?accessToken={tok}&revealAll=true')
special = [s for s in revealed['systems'] if s.get('special')]
check('есть особая звезда Wardenhold',
      len(special) == 1 and special[0]['name'] == 'Wardenhold',
      str([x['name'] for x in special]))
check('метка особой звезды скрыта у неразведанных систем',
      not any(s.get('special') for s in m['systems']))
# Особую звезду СТЕРЕЖЁТ Страж — п. 4.2.1: её не находят, её берут боем, и за бой
# полагается клад из трёх технологий. Без стража Wardenhold была бы просто звездой с
# хорошим именем. Подпись чудища приходит на языке запроса, а прогон просит русский.
check('особую звезду стережёт Страж — п. 4.2.1',
      special and special[0].get('monster') == 'Страж', str(special[0].get('monster')))

# --- Находки на планетах (п. 4.1) ---
# Проверяются КОДЫ, а не подписи: подпись приходит на языке запроса, и проверка,
# держащаяся на показанном тексте, ломается от первого же перевода.
all_planets = [p for st in revealed['systems'] for p in st['planets']]
found = [p for p in all_planets if p.get('find')]
check('в галактике есть находки — п. 4.1', len(found) >= 2,
      f"находок {len(found)} на {len(all_planets)} планетах")
check('находки — редкость, а не украшение каждой планеты',
      len(found) <= len(all_planets) // 5,
      f"находок {len(found)} из {len(all_planets)}")
check('у находки есть имя и описание',
      all(p.get('findLabel') and p.get('findDescription') for p in found),
      str([p.get('find') for p in found if not p.get('findDescription')]))
check('находка стоит только на пригодной планете — п. 4.1',
      all(p['colonizable'] for p in found),
      str([p['climate'] for p in found if not p['colonizable']]))
# «Natives come in sets of 3, so planets with Natives have to be at least of Medium
# size»: на планете, где помещается трое, для хозяев не осталось бы места вовсе.
check('туземцы селятся только там, где хватит места и колонистам — п. 4.1',
      all(p['maxPopulation'] >= 4 for p in found if p['find'] == 'NATIVES'),
      str([p['maxPopulation'] for p in found if p['find'] == 'NATIVES']))
# Стартовая система у всех империй одна и та же (чертёж один на партию), и находка в ней
# досталась бы всем разом — это сдвинутый старт, а не удача. Проверяется по ВСЕМ родным
# мирам раскрытой карты, а не по своему: чертёж общий.
home_systems = {st['id'] for st in revealed['systems']
                for p in st['planets'] if p.get('homeworld')}
check('в стартовых системах находок нет — п. 4.1',
      not any(p.get('find') for st in revealed['systems'] if st['id'] in home_systems
              for p in st['planets']),
      str([p.get('find') for st in revealed['systems'] if st['id'] in home_systems
           for p in st['planets'] if p.get('find')]))
# Находка видна только там, где побывал флот: у неразведанной системы сервер не отдаёт
# планет вовсе, и разведка находкой не подменяется.
check('находки скрыты у неразведанных систем — п. 15',
      not any(p.get('find') for st in m['systems'] if not st.get('explored')
              for p in st['planets']))
mine = [p for s in m['systems'] for p in s['planets'] if p.get('ownerPlayerId') == pid]
check('родной мир выдан', len(mine) == 1)
home = mine[0]
home_system = [s for s in m['systems'] for p in s['planets'] if p.get('ownerPlayerId') == pid][0]
check('родная звезда названа игроком', home_system['name'] == 'Родная', home_system['name'])
unexplored = [s for s in m['systems'] if not s['explored']]
check('чужие системы не раскрыты', all(s.get('name') is None for s in unexplored))

# --- 3. Колония: занятия и выработка (п. 4.1) ---
c = home['colony']
check('распределение по умолчанию кормит колонию', c['foodBalance'] == 0 and c['farmers'] == 4,
      f"{c['farmers']}/{c['workers']}/{c['scientists']}, еда {c['foodBalance']:+d}")
# Производство отдаётся ЗА ВЫЧЕТОМ УБОРКИ, а терпимость к грязи зависит от размера мира:
# средний терпит меньше большого, и та же добыча оставляет колонии на единицу меньше.
# Числа здесь и переехали 8/8/6 → 8/7/6, когда родной мир стал средним, как в MOO II
# (журнал цен, п. 3.51): прежде по умолчанию он был большим, а сторона «большой мир»
# выдавала огромный.
check('выработка: еда/производство/наука', (c['food'], c['production'], c['research']) == (8, 7, 6),
      f"{c['food']}/{c['production']}/{c['research']}")

bad = call('POST', f"/api/games/{gid}/planets/{home['id']}/population",
           {'accessToken': tok, 'farmers': 1, 'workers': 1, 'scientists': 1})
check('сумма занятий должна сойтись', bad.get('ERROR') == 409)

foreign = [p for s in call('GET', f'/api/games/{gid}/map?accessToken={tok}&revealAll=true')['systems']
           for p in s['planets'] if p.get('ownerPlayerId') and p['ownerPlayerId'] != pid]
bad = call('POST', f"/api/games/{gid}/planets/{foreign[0]['id']}/population",
           {'accessToken': tok, 'farmers': 8, 'workers': 0, 'scientists': 0})
check('чужая колония недоступна', bad.get('ERROR') == 403)

r = call('POST', f"/api/games/{gid}/planets/{home['id']}/population",
         {'accessToken': tok, 'farmers': 4, 'workers': 0, 'scientists': 4})
check('перераспределение применяется', r['colony']['research'] == 12,
      f"наука {r['colony']['research']}")
check('доход науки по империи', call('GET', f'/api/games/{gid}/research?accessToken={tok}')['researchPerTurn'] == 12)

# --- 4. Дерево технологий (п. 9) ---
tree = call('GET', '/api/reference/research')
levels = [l for cat in tree['categories'] for l in cat['levels']]
check('дерево MoO2: 8 разделов, 72 уровня', len(tree['categories']) == 8 and len(levels) == 72,
      f"{len(tree['categories'])} разделов, {len(levels)} уровней")
eng = [cat for cat in tree['categories'] if cat['code'] == 'engineering'][0]
check('стоимости MoO2 на месте', [l['cost'] for l in eng['levels'][:4]] == [80, 150, 250, 400])
# Сверяются раздел и номер уровня, а не название: названия переводятся (п. 3.5), и
# проверка по ним ломалась бы от языка ответа.
general = sorted((cat['code'], l['order']) for cat in tree['categories']
                 for l in cat['levels'] if l['general'])
check('общие уровни помечены',
      general == [('chemistry', 1), ('physics', 1), ('power', 1), ('power', 2)],
      str(general))

# Разделы окна «Инфо» — п. 11.1: список изученного делится на четыре части, как в окне
# Tech Review MOO II. Раздел выводится из справочников зданий и кораблей: что технология
# открывает, туда она и попадает.
tree_options = [o for l in levels for o in l['options']]
sections = {o.get('section') for o in tree_options}
check('у каждой технологии есть раздел окна «Инфо» — п. 11.1',
      all(o.get('section') and o.get('sectionLabel') for o in tree_options),
      str(sorted(sections)))
check('разделов ровно четыре, как в оригинале — п. 11.1',
      sections == {'GENERAL', 'COLONY', 'WEAPON', 'SHIP'}, str(sorted(sections)))
by_code = {o['code']: o['section'] for o in tree_options}
check('здание — в колонии, пушка — в оружие, модуль — в корабли — п. 11.1',
      (by_code.get('automated-factory'), by_code.get('fusion-beam'),
       by_code.get('fusion-drive')) == ('COLONY', 'WEAPON', 'SHIP'),
      str([by_code.get('automated-factory'), by_code.get('fusion-beam'),
           by_code.get('fusion-drive')]))
# Бронеказармы — про колонию, а не «общее»: раздел выводится из справочника зданий, и
# пока здания не было, технология лежала в прочем и не делала ничего (п. 12).
check('бронеказармы стоят в разделе колоний — п. 11.1, п. 12',
      by_code.get('armor-barracks') == 'COLONY', str(by_code.get('armor-barracks')))

bad = call('POST', f'/api/games/{gid}/research',
           {'accessToken': tok, 'categoryCode': 'engineering', 'levelOrder': 3, 'optionCode': 'battle-pods'})
check('через уровень не перепрыгнуть', bad.get('ERROR') == 409)

# Стартовые технологии — п. 9: три первых уровня дерева есть у каждой империи с первого
# хода, они же перечислены в самом описании дерева. Это не мелочь для удобства: пока их
# не выдавали никому, империя ИИ, чьё устремление не любит химию, не строила НИ ОДНОГО
# корабля за всю партию — кораблестроение требует Power и Chemistry разом. На зерне 777
# у восьми империй за 150 ходов выходило шесть перелётов, ноль знакомств и ноль войн.
starting = call('GET', f'/api/games/{gid}/research?accessToken={tok}')['acquired']
started = {t['optionCode'] for t in starting}
check('стартовые технологии выданы — п. 9',
      {'nuclear-drive', 'standard-fuel-cells', 'laser-cannon'} <= started, str(sorted(started)))
# Стартовые уровни названы ССЫЛКАМИ «раздел:номер»: названия переводятся, а ссылка нет,
# и сверять её можно на любом языке ответа. Проверяется, что каждая ссылка находит в дереве
# свой уровень и что все они первые — империя начинает у подножия лестниц.
refs = tree['startingLevels']
found = [(c['code'], lv['order']) for c in tree['categories'] for lv in c['levels']
         if f"{c['code']}:{lv['order']}" in refs]
check('стартовые уровни названы деревом — п. 9',
      len(found) == len(refs) == 3 and all(order == 1 for _, order in found)
      and all(t['levelOrder'] == 1 for t in starting),
      f"{refs} -> {found}")
check('стартовые технологии достались на первом ходу — п. 9',
      all(t['acquiredTurn'] == 1 for t in starting),
      str(sorted({t['acquiredTurn'] for t in starting})))

# Цель выбирается уже НАД стартовым уровнем: физика, химия и Power первого уровня изучены,
# и переспросить их нельзя — через изученное не исследуют заново.
again = call('POST', f'/api/games/{gid}/research',
             {'accessToken': tok, 'categoryCode': 'physics', 'levelOrder': 1, 'optionCode': 'laser-cannon'})
check('стартовый уровень заново не исследуют — п. 9', again.get('ERROR') == 409)

call('POST', f'/api/games/{gid}/research',
     {'accessToken': tok, 'categoryCode': 'power', 'levelOrder': 2, 'optionCode': 'colony-ship'})
rs = call('GET', f'/api/games/{gid}/research?accessToken={tok}')
check('цель исследования выбрана', rs['optionCode'] == 'colony-ship' and rs['levelCost'] == 80,
      f"{rs['optionCode']} ({rs['optionName']}), цена {rs['levelCost']}")

# --- 5. Ход: рост, производство, исследования ---
# Прорыв не наступает ровно на базовой стоимости — п. 9: она открывает возможность, и
# только на двойной стоимости прорыв гарантирован. Поэтому ждём его несколькими ходами,
# а не одним, и проверяем, что до оплаты базовой стоимости шанса не было вовсе.
paid = None
for _ in range(20):
    before = call('GET', f'/api/games/{gid}/research?accessToken={tok}')
    if before['researchPoints'] < before['levelCost'] and paid is None:
        paid = before['breakthroughPercent'] == 0
    end_turn(gid, tok)
    if len(call('GET', f'/api/games/{gid}/research?accessToken={tok}')['acquired']) > len(starting):
        break
check('до базовой стоимости шанса прорыва нет — п. 9', paid is True,
      f"шанс до оплаты нулевой: {paid}")
rs = call('GET', f'/api/games/{gid}/research?accessToken={tok}')
# Сравнивается ПРИБАВИВШЕЕСЯ, а не весь список: со стартовыми технологиями (п. 9) он
# больше не пуст, и «изучено ровно три» было бы проверкой не уровня, а старта партии.
got = sorted(t['optionCode'] for t in rs['acquired'] if t['optionCode'] not in started)
check('общий уровень выдал все технологии',
      got == ['colony-ship', 'outpost-ship', 'transport'], str(got))


def planet():
    mm = call('GET', f'/api/games/{gid}/map?accessToken={tok}')
    return [p for s in mm['systems'] for p in s['planets'] if p.get('ownerPlayerId') == pid][0]


p = planet()
check('население выросло', p['colony']['populationK'] > 8000, f"{p['colony']['populationK']} тыс.")
credits_now = [x for x in call('GET', f'/api/games/{gid}')['players'] if x['id'] == pid][0]['credits']
check('казна пополняется товарами', credits_now > 0, f'{credits_now} кр.')

# --- 6. Здания (п. 10) ---
avail = [x['code'] for x in p['colony']['available']]
# Особые проекты зданиями не являются: дома, товары, шпион, корабль, грузовой флот и
# гражданские корабли — последние открыты изученным только что Cold Fusion, а грузовой
# флот стоит в списке со стартовых технологий (п. 9). Колониальная база — если в родной
# системе есть свободная планета. Их проверяют свои разделы ниже; здесь важно, что ни
# одного ЗДАНИЯ без технологий не предлагается.
check('в начале из зданий не доступно ничего',
      {x for x in avail if not x.startswith('SHIP')}
      - {'COLONY_BASE', 'SPY', 'FREIGHTER', 'COLONY_SHIP', 'OUTPOST_SHIP', 'TRANSPORT'}
      == {'HOUSING', 'TRADE_GOODS'}, str(avail))
bad = call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
           {'accessToken': tok, 'projectCode': 'biospheres'})
check('здание без технологии не построить', bad.get('ERROR') == 409)

call('POST', f'/api/games/{gid}/research',
     {'accessToken': tok, 'categoryCode': 'biology', 'levelOrder': 1, 'optionCode': 'biospheres'})
for _ in range(25):
    end_turn(gid, tok)
    if any(t['optionCode'] == 'biospheres'
           for t in call('GET', f'/api/games/{gid}/research?accessToken={tok}')['acquired']):
        break
p = planet()
check('после исследования здание доступно',
      'biospheres' in [x['code'] for x in p['colony']['available']])

n = p['population']
call('POST', f"/api/games/{gid}/planets/{p['id']}/population",
     {'accessToken': tok, 'farmers': n // 2, 'workers': n - n // 2, 'scientists': 0})
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'biospheres'})
before_build = planet()['colony']
capacity_before, upkeep_before = before_build['maxPopulation'], before_build['upkeep']
# Ждём именно биосферы: звёздная база на родном мире стоит с первого хода (п. 8), и
# «есть хоть одно здание» больше не значит «достроили это».
for _ in range(15):
    end_turn(gid, tok)
    if any(b['code'] == 'biospheres' for b in planet()['colony']['buildings']):
        break
p = planet()
check('здание построено',
      'biospheres' in [b['code'] for b in p['colony']['buildings']],
      str([b['code'] for b in p['colony']['buildings']]))
check('действие здания применилось', p['colony']['maxPopulation'] == capacity_before + 2,
      f"вместимость {capacity_before} -> {p['colony']['maxPopulation']}")
# Содержание: биосферы стоят кредит, и проверяется именно их кредит, а не итог.
# Итог сравнивать нельзя: случайное событие (п. 11.1) успевает разрушить звёздную
# базу родного мира, и «3» превращается в «1» без всякой поломки.
check('содержание списывается', p['colony']['upkeep'] == upkeep_before + 1,
      f"содержание {upkeep_before} -> {p['colony']['upkeep']}")
check('построенное ушло из списка доступного',
      'biospheres' not in [x['code'] for x in p['colony']['available']])

# --- 6.1. Очередь стройки (п. 10) ---
#
# Очередь MOO II: колония строит одно, а что за ним — решено заранее. Проверяется всё,
# на чём такая очередь ломается молча: порядок, перестановка, пределы и главное — что
# достроенное само уступает место следующему.
def colony_now():
    return planet()['colony']


def enqueue(code):
    return call('POST', f"/api/games/{gid}/planets/{p['id']}/queue",
                {'accessToken': tok, 'projectCode': code})


check('в начале очередь пуста — п. 10', colony_now()['queue'] == [], str(colony_now()['queue']))

call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
queued = enqueue('HOUSING')
check('проект встаёт в очередь — п. 10',
      [x['code'] for x in queued['colony']['queue']] == ['HOUSING'],
      str([x['code'] for x in queued['colony']['queue']]))
check('стройку очередь не трогает — п. 10', queued['colony']['projectCode'] == 'SPY',
      str(queued['colony']['projectCode']))

queued = enqueue('SPY')
# Признак повторяемости приходит с сервера: по нему окно стройки решает, гасить ли
# «в очередь» у того, что уже строится. Шпиона и корабли строят сколько угодно раз,
# здание на планете — однажды.
marks = colony_now()['available']
check('повторяемость проекта приходит с сервера — п. 10',
      all(x['repeatable'] == x['special'] for x in marks)
      and any(x['repeatable'] for x in marks),
      str([(x['code'], x['special'], x['repeatable']) for x in marks]))
check('повторяемое встаёт в очередь второй раз — п. 10',
      [x['code'] for x in queued['colony']['queue']] == ['HOUSING', 'SPY'],
      str([x['code'] for x in queued['colony']['queue']]))

moved = call('POST', f"/api/games/{gid}/planets/{p['id']}/queue/1/move",
             {'accessToken': tok, 'toIndex': 0})
check('проект переставляется в очереди — п. 10',
      [x['code'] for x in moved['colony']['queue']] == ['SPY', 'HOUSING'],
      str([x['code'] for x in moved['colony']['queue']]))

dropped = call('DELETE', f"/api/games/{gid}/planets/{p['id']}/queue/1?accessToken={tok}")
check('проект убирается из очереди — п. 10',
      [x['code'] for x in dropped['colony']['queue']] == ['SPY'],
      str([x['code'] for x in dropped['colony']['queue']]))

bad = call('DELETE', f"/api/games/{gid}/planets/{p['id']}/queue/9?accessToken={tok}")
check('несуществующее место в очереди — отказ — п. 10', bad.get('ERROR') == 409,
      str(bad.get('body', {}).get('message')))
bad = enqueue('biospheres')
check('построенное здание в очередь не поставить — п. 10', bad.get('ERROR') == 409,
      str(bad.get('body', {}).get('message')))

# Предел очереди — семь проектов, как в окне стройки оригинала.
while len(colony_now()['queue']) < 7:
    enqueue('SPY')
full = enqueue('SPY')
check('очередь длиннее семи не растёт — п. 10', full.get('ERROR') == 409,
      f"в очереди {len(colony_now()['queue'])}, ответ {full.get('body', {}).get('message')}")

# Здание, стоящее в очереди, из списка стройки уходит: второй раз его не построить.
for index in range(len(colony_now()['queue']) - 1, -1, -1):
    call('DELETE', f"/api/games/{gid}/planets/{p['id']}/queue/{index}?accessToken={tok}")
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'HOUSING'})
free_building = next((x['code'] for x in colony_now()['available']
                      if x['code'] not in ('HOUSING', 'TRADE_GOODS', 'SPY', 'COLONY_BASE',
                                           'FREIGHTER', 'COLONY_SHIP', 'OUTPOST_SHIP', 'TRANSPORT')
                      and not x['code'].startswith('SHIP')), None)
if free_building:
    enqueue(free_building)
    check('здание из очереди уходит из списка стройки — п. 10',
          free_building not in [x['code'] for x in colony_now()['available']],
          free_building)
    call('DELETE', f"/api/games/{gid}/planets/{p['id']}/queue/0?accessToken={tok}")
    check('убранное из очереди возвращается в список — п. 10',
          free_building in [x['code'] for x in colony_now()['available']],
          free_building)

# Главное: достроенное само уступает место следующему, и вложенное не пропадает.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
enqueue('TRADE_GOODS')
for _ in range(25):
    end_turn(gid, tok)
    if colony_now()['projectCode'] != 'SPY':
        break
after = colony_now()
check('достроив, колония берёт следующее из очереди — п. 10',
      after['projectCode'] == 'TRADE_GOODS' and after['queue'] == [],
      f"строит {after['projectCode']}, в очереди {len(after['queue'])}")

# Пустой стройки у колонии не остаётся: достроив последнее при пустой очереди, она
# переходит на товары — в MOO II стройка не бывает пустой, и производство идёт в казну,
# а не пропадает.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
check('очередь пуста, строится шпион — п. 10',
      colony_now()['projectCode'] == 'SPY' and colony_now()['queue'] == [],
      f"строит {colony_now()['projectCode']}, в очереди {len(colony_now()['queue'])}")
for _ in range(25):
    end_turn(gid, tok)
    if colony_now()['projectCode'] != 'SPY':
        break
idle = colony_now()
check('достроив при пустой очереди, колония берётся за товары — п. 10',
      idle['projectCode'] == 'TRADE_GOODS',
      f"строит {idle.get('projectCode')}")

# Проект из очереди поднимается на стапель: игрок передумал, и список стройки обязан его
# предложить — `available` убирает поставленное в очередь, и раньше поднять его было нечем.
# Поднятый из очереди проект из неё уходит: строить его дважды никто не просил.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
enqueue('HOUSING')
promoted = call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
                {'accessToken': tok, 'projectCode': 'HOUSING'})
check('проект очереди поднимается на стапель — п. 10',
      promoted.get('colony', {}).get('projectCode') == 'HOUSING'
      and [x['code'] for x in promoted.get('colony', {}).get('queue', [])] == [],
      f"строит {promoted.get('colony', {}).get('projectCode')}, очередь"
      f" {[x['code'] for x in promoted.get('colony', {}).get('queue', [])]}")

# Бесконечная стройка очередь не запирает: взятая из середины, она не кончилась бы никогда,
# и всё, что стоит за ней, колония не заложила бы вовсе.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
enqueue('HOUSING')
enqueue('SPY')
check('дома стоят в очереди перед шпионом — п. 10',
      [x['code'] for x in colony_now()['queue']] == ['HOUSING', 'SPY'],
      str([x['code'] for x in colony_now()['queue']]))
for _ in range(25):
    end_turn(gid, tok)
    if colony_now()['projectCode'] != 'SPY' or colony_now()['queue'] == []:
        break
after_endless = colony_now()
check('бесконечное в середине очереди пропускается — п. 10',
      after_endless['projectCode'] == 'SPY' and after_endless['queue'] == [],
      f"строит {after_endless['projectCode']}, очередь"
      f" {[x['code'] for x in after_endless['queue']]}")

# Бесконечная стройка стапеля не держит: поставленное за домами и товарами ждало бы
# вечно — очередь забирают только за достроенным. Поэтому новый проект встаёт на их
# место сразу, а сама бесконечная стройка в очередь не уходит: вернувшись в неё, она
# заперла бы всё, что стоит следом.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'HOUSING'})
check('колония строит дома — п. 10', colony_now()['projectCode'] == 'HOUSING',
      str(colony_now()['projectCode']))
after_houses = enqueue('SPY')['colony']
check('за домами очередь не встаёт: проект берётся сразу — п. 10',
      after_houses['projectCode'] == 'SPY' and after_houses['queue'] == [],
      f"строит {after_houses['projectCode']}, очередь"
      f" {[x['code'] for x in after_houses['queue']]}")

# --- 6.2. Продажа построек (п. 10) ---
#
# В MOO II построенное продают: здание исчезает, казна получает половину его цены,
# содержание платить перестают. За ход колония продаёт одну постройку.
def credits_now():
    return [x for x in call('GET', f'/api/games/{gid}')['players'] if x['id'] == pid][0]['credits']


sale_home = colony_now()
# Продаём биосферы, если они целы: у них заодно проверяется снятие действия (+2 к
# вместимости). Целы они не всегда — случайное событие (п. 11.1) сносит постройки, — и
# тогда берётся любая другая: правила продажи от вида здания не зависят.
sale_target = next((b for b in sale_home['buildings'] if b['code'] == 'biospheres'),
                   sale_home['buildings'][0] if sale_home['buildings'] else None)
check('колонии есть что продавать — п. 10', sale_target is not None,
      str([b['code'] for b in sale_home['buildings']]))

if sale_target:
    check('у постройки есть цена продажи — половина цены здания — п. 10',
          sale_target.get('sellValue') == sale_target['cost'] // 2,
          f"цена {sale_target['cost']}, продажа {sale_target.get('sellValue')}")
    check('у особого проекта цены продажи нет — п. 10',
          all(x.get('sellValue') is None for x in sale_home['available'] if x['special']),
          str([x['code'] for x in sale_home['available'] if x['special'] and x.get('sellValue')]))

    # Очередь стройки продажа трогать не должна, пока проданное ей не мешает.
    enqueue('HOUSING')
    sale_credits_before = credits_now()
    sale_upkeep_before = colony_now()['upkeep']
    sale_capacity_before = colony_now()['maxPopulation']

    sold = call('POST', f"/api/games/{gid}/planets/{p['id']}/sell",
                {'accessToken': tok, 'buildingCode': sale_target['code']})
    check('проданное здание исчезает с планеты — п. 10',
          sale_target['code'] not in [b['code'] for b in sold['colony']['buildings']],
          str([b['code'] for b in sold['colony']['buildings']]))
    check('за продажу казна получает половину цены — п. 10',
          credits_now() == sale_credits_before + sale_target['cost'] // 2,
          f"было {sale_credits_before}, стало {credits_now()}")
    check('за проданное больше не платят содержание — п. 10',
          sold['colony']['upkeep'] == sale_upkeep_before - sale_target['upkeep'],
          f"содержание {sale_upkeep_before} -> {sold['colony']['upkeep']}")
    if sale_target['code'] == 'biospheres':
        check('действие проданного здания снимается — п. 10',
              sold['colony']['maxPopulation'] == sale_capacity_before - 2,
              f"вместимость {sale_capacity_before} -> {sold['colony']['maxPopulation']}")
    check('проданное возвращается в список стройки — п. 10',
          sale_target['code'] in [x['code'] for x in sold['colony']['available']])
    check('очередь продажа не трогает, пока строить по-прежнему можно — п. 10',
          [x['code'] for x in sold['colony']['queue']] == ['HOUSING'],
          str([x['code'] for x in sold['colony']['queue']]))

    left_to_sell = [b['code'] for b in sold['colony']['buildings']]
    again = call('POST', f"/api/games/{gid}/planets/{p['id']}/sell",
                 {'accessToken': tok,
                  'buildingCode': left_to_sell[0] if left_to_sell else sale_target['code']})
    check('за ход продаётся одна постройка — п. 10', again.get('ERROR') == 409,
          str(again.get('body', {}).get('message')))
    check('колония помнит ход своей продажи — п. 10',
          colony_now().get('soldTurn') == call('GET', f'/api/games/{gid}')['game']['turn'],
          f"продано на ходу {colony_now().get('soldTurn')}")

    end_turn(gid, tok)
    # Новым ходом предел снят, и отказ приходит уже по делу: такого здания на планете нет.
    bad = call('POST', f"/api/games/{gid}/planets/{p['id']}/sell",
               {'accessToken': tok, 'buildingCode': sale_target['code']})
    check('непостроенное не продать — п. 10', bad.get('ERROR') == 409,
          str(bad.get('body', {}).get('message')))

    if left_to_sell:
        next_turn_sale = call('POST', f"/api/games/{gid}/planets/{p['id']}/sell",
                              {'accessToken': tok, 'buildingCode': left_to_sell[0]})
        check('новым ходом продавать снова можно — п. 10',
              next_turn_sale.get('ERROR') is None
              and left_to_sell[0] not in
                  [b['code'] for b in next_turn_sale.get('colony', {}).get('buildings', [])],
              str(next_turn_sale.get('body', {}).get('message')))

# --- 6.3. Выкуп стройки (п. 10) ---
#
# В MOO II недостающее производство докупают за кредиты, и вещь достраивается тем же
# ходом. Цена зависит от того, много ли вложено: с половины — два кредита за единицу,
# до неё четыре, то есть спешка обходится вдвое дороже.
def buy():
    return call('POST', f"/api/games/{gid}/planets/{p['id']}/buy", {'accessToken': tok})


def spies_now():
    return call('GET', f'/api/games/{gid}/espionage?accessToken={tok}')['spies']


call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
# Копим до половины: с неё выкуп вдвое дешевле — то самое место, где его и делают.
for _ in range(25):
    end_turn(gid, tok)
    half = colony_now()
    if half['projectCode'] != 'SPY' or half['projectPoints'] * 2 >= half['projectCost']:
        break
half = colony_now()
check('колония накопила половину стройки — п. 10',
      half['projectCode'] == 'SPY' and half['projectPoints'] * 2 >= half['projectCost'],
      f"строит {half['projectCode']}, вложено {half['projectPoints']} из {half.get('projectCost')}")

if half['projectCode'] == 'SPY' and half['projectPoints'] * 2 >= half['projectCost']:
    check('с половины единица производства стоит два кредита — п. 10',
          half['buyCost'] == (half['projectCost'] - half['projectPoints']) * 2,
          f"вложено {half['projectPoints']} из {half['projectCost']}, выкуп {half['buyCost']}")

    # Ждём, пока в казне наберётся цена: без денег выкуп — отдельная проверка ниже.
    for _ in range(15):
        half = colony_now()
        if half['projectCode'] != 'SPY' or credits_now() >= half['buyCost']:
            break
        end_turn(gid, tok)
    purse_before, spies_before = credits_now(), spies_now()
    bought = buy()
    check('выкуп оплачивает стройку целиком — п. 10',
          bought.get('colony', {}).get('projectPoints') == half['projectCost'],
          f"вложено {bought.get('colony', {}).get('projectPoints')} из {half['projectCost']},"
          f" ответ {bought.get('body', {}).get('message')}")
    check('выкуп списывает цену из казны — п. 10',
          credits_now() == purse_before - half['buyCost'],
          f"было {purse_before}, стало {credits_now()}, цена {half['buyCost']}")
    check('оплаченной стройке докупать нечего — п. 10',
          bought.get('colony', {}).get('buyCost') is None,
          str(bought.get('colony', {}).get('buyCost')))

    end_turn(gid, tok)
    check('выкупленное достраивается тем же ходом — п. 10', spies_now() == spies_before + 1,
          f"шпионов было {spies_before}, стало {spies_now()}")

# Стройка начата заново: вложено меньше половины, и та же недостача стоит вчетверо.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
fresh = colony_now()
check('пока нет половины, единица производства стоит четыре кредита — п. 10',
      fresh['projectPoints'] * 2 < fresh['projectCost']
      and fresh['buyCost'] == (fresh['projectCost'] - fresh['projectPoints']) * 4,
      f"вложено {fresh['projectPoints']} из {fresh['projectCost']}, выкуп {fresh.get('buyCost')}")

if credits_now() < fresh['buyCost']:
    poor = buy()
    check('без денег стройку не выкупить — п. 10', poor.get('ERROR') == 409,
          str(poor.get('body', {}).get('message')))

# Дома и товары не кончаются вовсе — выкупать в них нечего.
call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
     {'accessToken': tok, 'projectCode': 'TRADE_GOODS'})
check('у бесконечной стройки цены выкупа нет — п. 10', colony_now().get('buyCost') is None,
      str(colony_now().get('buyCost')))
endless = buy()
check('бесконечную стройку не выкупить — п. 10', endless.get('ERROR') == 409,
      str(endless.get('body', {}).get('message')))

# --- 6.4. Загрязнение (п. 10) ---
#
# В MOO II промышленность сверх того, что планета терпит, приходится убирать, и уборка
# съедает само производство. Здесь проверяется, что число считается по правилу оригинала,
# зависит от размера планеты и доходит до стройки — то есть что колония строит на чистое.
TOLERANCE_BY_SIZE = {'Tiny': 2, 'Small': 4, 'Medium': 6, 'Large': 8, 'Huge': 10}

# Уборку можно не только терпеть: справочник несёт рециклотрон и четыре очистных
# сооружения MOO II. Проверяется сам справочник — дойти до их технологий (Engineering и
# Chemistry высоких уровней) за прогон нельзя, а вот прочитаться файл обязан: неизвестное
# действие в нём роняет чтение целиком, и эта проверка ловит опечатку в типе эффекта.
catalogue = {b['code']: b for b in call('GET', '/api/reference/buildings')}
check('рециклотрон есть в справочнике с ценой оригинала — п. 10',
      catalogue.get('recyclotron', {}).get('cost') == 200
      and catalogue.get('recyclotron', {}).get('requiredTechCode') == 'recyclotron',
      str(catalogue.get('recyclotron')))
check('очистные сооружения есть в справочнике — п. 10',
      all(code in catalogue for code in ('pollution-processor', 'atmospheric-renewer',
                                         'nano-disassemblers', 'core-waste-dump')),
      str(sorted(catalogue)))


def waste_of(colony):
    """Уборка по правилу оригинала: половина грязного сверх терпимости, вверх."""
    if colony.get('pollutionFree'):
        return 0
    mined = colony['workers'] * colony['productionPerWorker'] + colony['productionFlat']
    divisor = colony['pollutionDivisor']
    over = mined - colony['productionFlat'] - colony['pollutionTolerance'] * divisor
    return 0 if over <= 0 else -(-over // (2 * divisor))


home_planet = planet()
dirty_before = colony_now()
check('терпимость планеты к грязи — от её размера — п. 10',
      dirty_before['pollutionTolerance'] == TOLERANCE_BY_SIZE.get(home_planet['sizeLabel']),
      f"{home_planet['sizeLabel']}: терпит {dirty_before['pollutionTolerance']}")
check('без очистных сооружений производство грязное целиком — п. 10',
      dirty_before['pollutionDivisor'] == 1, str(dirty_before['pollutionDivisor']))

# Учёных — в рабочие: на родном мире так промышленность заведомо перерастает терпимость,
# и уборке есть что убирать. Фермеров при этом не трогаем: колония должна кормить себя,
# иначе голод уменьшит население прямо посреди проверки, и стройка получит уже не то
# производство, которое мы считали.
jobs_before = (dirty_before['farmers'], dirty_before['workers'], dirty_before['scientists'])
everyone = home_planet['population']
call('POST', f"/api/games/{gid}/planets/{home_planet['id']}/population",
     {'accessToken': tok, 'farmers': jobs_before[0],
      'workers': everyone - jobs_before[0], 'scientists': 0})
dirty = colony_now()
mined_now = dirty['workers'] * dirty['productionPerWorker'] + dirty['productionFlat']
check('промышленность сверх терпимости пачкает планету — п. 10',
      mined_now > dirty['pollutionTolerance'] and dirty['pollution'] > 0,
      f"добыто {mined_now}, терпит {dirty['pollutionTolerance']}, уборка {dirty['pollution']}")
check('уборка съедает половину лишнего, округляя вверх, — п. 10',
      dirty['pollution'] == waste_of(dirty),
      f"уборка {dirty['pollution']}, по правилу {waste_of(dirty)}")
check('колонии остаётся добытое за вычетом уборки — п. 10',
      dirty['production'] == mined_now - dirty['pollution'],
      f"добыто {mined_now}, уборка {dirty['pollution']}, осталось {dirty['production']}")

# Главное: грязь доходит до стройки — в проект уходит чистое производство, а не добытое.
call('POST', f"/api/games/{gid}/planets/{home_planet['id']}/project",
     {'accessToken': tok, 'projectCode': 'SPY'})
invested_before = colony_now()['projectPoints']
clean = colony_now()['production']
end_turn(gid, tok)
after_turn = colony_now()
gained = after_turn['projectPoints'] - invested_before
# Население за ход могло вырасти на единицу, и тогда ход считал уже с новым рабочим —
# поэтому годится и то производство, что колония показывает после хода.
check('в стройку уходит ровно чистое производство колонии — п. 10',
      after_turn['projectCode'] == 'SPY'
      and gained in (clean, after_turn['production']),
      f"было {invested_before}, стало {after_turn['projectPoints']},"
      f" чистого {clean} (после хода {after_turn['production']})")
check('уборка до стройки не доходит — п. 10', gained < clean + dirty['pollution'],
      f"вложено {gained}, добыто {clean + dirty['pollution']}, уборка {dirty['pollution']}")

# Возвращаем распределение: дальше колонии снова нужно кормить себя.
call('POST', f"/api/games/{gid}/planets/{home_planet['id']}/population",
     {'accessToken': tok, 'farmers': jobs_before[0], 'workers': jobs_before[1],
      'scientists': jobs_before[2]})

# --- 7. Дома (п. 10 + 4.1.1) ---
growth_before = planet()['colony']['growthK']
h = call('POST', f"/api/games/{gid}/planets/{p['id']}/project",
         {'accessToken': tok, 'projectCode': 'HOUSING'})
check('дома ускоряют рост',
      h['colony']['housingBonusPercent'] > 0 and h['colony']['growthK'] > growth_before,
      f"+{h['colony']['housingBonusPercent']}%, прирост {growth_before} -> {h['colony']['growthK']}")

# --- 8. Сохранение и загрузка ---
before = planet()
before_credits = [x for x in call('GET', f'/api/games/{gid}')['players'] if x['id'] == pid][0]['credits']
sv = call('POST', f'/api/games/{gid}/save', {'accessToken': tok})
check('сохранение в списке', any(x['id'] == sv['id'] for x in call('GET', '/api/saves')))
ld = call('POST', f"/api/saves/{sv['id']}/load")
gid2, tok2, pid2 = ld['game']['id'], ld['credentials']['accessToken'], ld['credentials']['playerId']
m2 = call('GET', f'/api/games/{gid2}/map?accessToken={tok2}')
after = [x for s in m2['systems'] for x in s['planets'] if x.get('ownerPlayerId') == pid2][0]
after_credits = [x for x in call('GET', f'/api/games/{gid2}')['players'] if x['id'] == pid2][0]['credits']
rs2 = call('GET', f'/api/games/{gid2}/research?accessToken={tok2}')

check('загрузка: население', after['colony']['populationK'] == before['colony']['populationK'])
check('загрузка: занятия', (after['colony']['farmers'], after['colony']['workers'],
                            after['colony']['scientists']) ==
      (before['colony']['farmers'], before['colony']['workers'], before['colony']['scientists']))
check('загрузка: здания', [b['code'] for b in after['colony']['buildings']] ==
      [b['code'] for b in before['colony']['buildings']])
check('загрузка: стройка', after['colony']['projectCode'] == before['colony']['projectCode'])
check('загрузка: казна', after_credits == before_credits, f'{before_credits} -> {after_credits}')
before_research = call('GET', f'/api/games/{gid}/research?accessToken={tok}')
check('загрузка: изученное', sorted(t['optionCode'] for t in rs2['acquired']) ==
      sorted(t['optionCode'] for t in before_research['acquired']))

# Летопись — п. 11.1: пересчитать её нечем, поэтому она ложится в слепок. Без этого
# загруженная партия открывала бы окно «Инфо» с пустым графиком.
history_before = [e for e in call_auth('GET', f'/api/games/{gid}/info', tok)['empires'] if e['own']][0]
history_after = [e for e in call_auth('GET', f'/api/games/{gid2}/info', tok2)['empires'] if e['own']][0]
check('загрузка: летопись империи — п. 11.1',
      len(history_after['history']) == len(history_before['history'])
      and history_after['history'][-1] == history_before['history'][-1],
      f"замеров {len(history_before['history'])} -> {len(history_after['history'])}")

# Удаление сохранения — только администратором (п. 3.1). Хозяина у сохранения нет: оно
# лежит на сервере общей кучей, его видят и загружают все, — поэтому убирать чужое вправе
# тот, кто отвечает за сервер. Раньше эндпоинт не спрашивал ничего, и снести чужую партию
# мог кто угодно. Слепок берётся свой, отдельный: остальные ещё нужны прогону.
doomed = call('POST', f'/api/games/{gid}/save', {'accessToken': tok})
check('без пропуска учётной записи сохранение не удалить — п. 3.1',
      probe('DELETE', f"/api/saves/{doomed['id']}")['status'] == 403)
if player_token:
    check('обычному игроку сохранение не удалить — п. 3.1',
          call_account('DELETE', f"/api/saves/{doomed['id']}", player_token).get('ERROR') == 403)
check('сохранение ещё на месте: отказы его не тронули — п. 3.1',
      any(x['id'] == doomed['id'] for x in call('GET', '/api/saves')))
check('администратор удаляет сохранение — п. 3.1',
      call_account('DELETE', f"/api/saves/{doomed['id']}", account_token) is None)
check('удалённого сохранения в списке нет — п. 3.1',
      not any(x['id'] == doomed['id'] for x in call('GET', '/api/saves')))
check('удалить сохранение дважды нельзя — п. 3.1',
      call('DELETE', f"/api/saves/{doomed['id']}").get('ERROR') == 404)

# --- 9. Колониальная база (п. 4.1) ---
def planet_by_id(planet_id):
    mm = call('GET', f'/api/games/{gid}/map?accessToken={tok}')
    return [x for s in mm['systems'] for x in s['planets'] if x['id'] == planet_id][0]


home = planet()
home_id = home['id']
home_system = [s for s in call('GET', f'/api/games/{gid}/map?accessToken={tok}')['systems']
               if any(x['id'] == home_id for x in s['planets'])][0]
free = [x for x in home_system['planets']
        if x['colonizable'] and not x.get('ownerPlayerId') and x['id'] != home_id]
codes = [x['code'] for x in home['colony']['available']]

if free:
    check('база предлагается, пока в системе есть куда селиться', 'COLONY_BASE' in codes, str(codes))
    call('POST', f'/api/games/{gid}/planets/{home_id}/project',
         {'accessToken': tok, 'projectCode': 'COLONY_BASE'})
    check('стоимость базы видна колонии — 200, как в MOO II',
          planet_by_id(home_id)['colony']['projectCost'] == 200,
          str(planet_by_id(home_id)['colony']['projectCost']))

    for _ in range(25):
        end_turn(gid, tok)
        if planet_by_id(home_id)['colonyBaseReady']:
            break
    check('база достроена и ждёт выбора планеты', planet_by_id(home_id)['colonyBaseReady'] is True)
    # Готовая база списку стройки не мешает, пока в системе есть куда селиться: заселять
    # бывает что не одну планету, и следующую базу закладывают, не дожидаясь, пока
    # распорядятся этой. Считается при этом место: баз заказывают ровно по числу свободных
    # планет — готовая, строящаяся и стоящие в очереди все вместе.
    base_offered = 'COLONY_BASE' in [x['code'] for x in planet_by_id(home_id)['colony']['available']]
    check('база предлагается, пока свободных планет больше, чем заказано баз — п. 4.1',
          base_offered == (len(free) > 1),
          f"свободных планет {len(free)}, готова одна база, предлагается {base_offered}")

    if len(free) > 1:
        second = call('POST', f'/api/games/{gid}/planets/{home_id}/project',
                      {'accessToken': tok, 'projectCode': 'COLONY_BASE'})
        check('вторую базу можно заложить, пока есть куда селиться — п. 4.1',
              second.get('ERROR') is None, str(second.get('body', {}).get('message')))

        # Набираем базы до предела: на каждую свободную планету по одной, и ни одной сверх.
        for _ in range(len(free) + 2):
            if 'COLONY_BASE' not in [x['code'] for x
                                     in planet_by_id(home_id)['colony']['available']]:
                break
            call('POST', f'/api/games/{gid}/planets/{home_id}/queue',
                 {'accessToken': tok, 'projectCode': 'COLONY_BASE'})
        ordered = planet_by_id(home_id)
        ordered_bases = (1 if ordered['colonyBaseReady'] else 0)             + (1 if ordered['colony']['projectCode'] == 'COLONY_BASE' else 0)             + len([x for x in ordered['colony']['queue'] if x['code'] == 'COLONY_BASE'])
        check('баз заказывают ровно по числу свободных планет — п. 4.1',
              ordered_bases == len(free),
              f"свободных планет {len(free)}, заказано баз {ordered_bases}")
        check('набрав по базе на планету, список базу не предлагает — п. 4.1',
              'COLONY_BASE' not in [x['code'] for x in ordered['colony']['available']],
              str([x['code'] for x in ordered['colony']['available']]))
        refused = call('POST', f'/api/games/{gid}/planets/{home_id}/queue',
                       {'accessToken': tok, 'projectCode': 'COLONY_BASE'})
        check('лишнюю базу в очередь не поставить — п. 4.1', refused.get('ERROR') == 409,
              str(refused.get('body', {}).get('message')))

        # Очередь возвращаем к одной базе: дальше разделу нужна достроенная, а не пять.
        while len([x for x in planet_by_id(home_id)['colony']['queue']
                   if x['code'] == 'COLONY_BASE']) > 0:
            queue_now = [x['code'] for x in planet_by_id(home_id)['colony']['queue']]
            call('DELETE', f'/api/games/{gid}/planets/{home_id}/queue/'
                           f"{queue_now.index('COLONY_BASE')}?accessToken={tok}")
        # Ждём, пока вторая база наберёт свою цену: достроить её колония не вправе, пока
        # первая не пристроена, и вложенное при этом не растёт сверх цены и не пропадает.
        for _ in range(25):
            end_turn(gid, tok)
            if planet_by_id(home_id)['colony']['projectPoints'] >= 200:
                break
        waiting = planet_by_id(home_id)
        check('вторая база ждёт, пока пристроят первую — п. 4.1',
              waiting['colonyBaseReady'] is True
              and waiting['colony']['projectCode'] == 'COLONY_BASE'
              and waiting['colony']['projectPoints'] == 200,
              f"вложено {waiting['colony']['projectPoints']}, строит"
              f" {waiting['colony']['projectCode']}, готова {waiting['colonyBaseReady']}")

    target = free[0]
    system_after = call('POST', f'/api/games/{gid}/planets/{home_id}/colonize',
                        {'accessToken': tok, 'targetPlanetId': target['id']})
    settled = [x for x in system_after['planets'] if x['id'] == target['id']][0]
    check('база заселила выбранную планету',
          settled['population'] == 1 and settled.get('ownerPlayerId') == pid,
          f"{settled['name']}: {settled['population']} жит.")
    check('после заселения база снята с колонии',
          planet_by_id(home_id)['colonyBaseReady'] is False)

    again = call('POST', f'/api/games/{gid}/planets/{home_id}/colonize',
                 {'accessToken': tok, 'targetPlanetId': target['id']})
    check('без готовой базы заселить нельзя', again.get('ERROR') == 409)

    # Счётчик механик (этап 1 балансировки): колония, заселённая базой, — такая же
    # колония, как основанная кораблём. Счётчик знал только о корабле, и в тесной
    # галактике, где ИИ расселяется почти одними базами, он показывал ноль.
    base_used = next((e.get('used') or {}
                      for e in call_auth('GET', f'/api/games/{gid}/telemetry', tok)['empires']
                      if e['playerId'] == pid), {})
    check('колония от базы попадает в счётчик механик — этап 1',
          base_used.get('COLONIZED', 0) >= 1, str(base_used))
else:
    check('без свободных планет базу не предлагают', 'COLONY_BASE' not in codes, str(codes))

# --- 9.1. Приказ стройки набору колоний (п. 10) ---
#
# Список колоний правит империей разом: игрок помечает колонии и закладывает им общее.
# Проект встаёт первым в очередь, сдвигая её вниз, а колония, занятая бесконечной
# стройкой, берётся за него сразу — за домами и товарами очередь ждала бы вечно.
own_colonies = [x for st in call('GET', f'/api/games/{gid}/map?accessToken={tok}')['systems']
                for x in st['planets'] if x.get('ownerPlayerId') == pid and x.get('colony')]
group = [x['id'] for x in own_colonies]


def queue_codes(planet_id):
    return [q['code'] for q in planet_by_id(planet_id)['colony']['queue']]


# Родному миру — стройка и очередь, чтобы было что сдвигать.
call('POST', f'/api/games/{gid}/planets/{home_id}/project',
     {'accessToken': tok, 'projectCode': 'SPY'})
call('POST', f'/api/games/{gid}/planets/{home_id}/queue',
     {'accessToken': tok, 'projectCode': 'HOUSING'})
queue_before = queue_codes(home_id)
# Прочие колонии набора: у новой стройка бесконечная (товары) — на ней и проверяется,
# что бесконечная уступает стапель.
endless_before = {x['id']: planet_by_id(x['id'])['colony'].get('projectCode') for x in own_colonies}

ordered = call('POST', f'/api/games/{gid}/colonies/queue',
               {'accessToken': tok, 'planetIds': group, 'projectCode': 'SPY', 'top': True})
check('приказ отвечает всеми колониями набора — п. 10',
      isinstance(ordered, list) and len(ordered) == len(group),
      str(ordered if not isinstance(ordered, list) else len(ordered)))
check('проект встаёт первым в очередь, сдвигая её вниз — п. 10',
      queue_codes(home_id) == ['SPY'] + queue_before,
      f"было {queue_before}, стало {queue_codes(home_id)}")

for other in group:
    if other == home_id:
        continue
    was = endless_before[other]
    now = planet_by_id(other)['colony']
    check('бесконечная стройка уступает стапель новому проекту — п. 10',
          now['projectCode'] == 'SPY' and queue_codes(other) == [],
          f"строила {was}, строит {now['projectCode']}, очередь {queue_codes(other)}")

# Недоступное хоть одной колонии не исполняется вовсе. Асимметрия делается нарочно:
# здание, уже стоящее в очереди колонии, из её списка доступного уходит (второго такого
# на планете не построить), — и приказ всему набору обязан сорваться, не тронув ничьей
# очереди. Здание берётся из общего списка: если общих зданий нет вовсе, проверять нечего.
def available_codes(planet_id):
    return {x['code'] for x in planet_by_id(planet_id)['colony']['available']
            if not x['special']}


common_buildings = set.intersection(*(available_codes(x) for x in group)) if group else set()
if common_buildings and len(group) > 1:
    once = sorted(common_buildings)[0]
    call('POST', f'/api/games/{gid}/planets/{group[0]}/queue',
         {'accessToken': tok, 'projectCode': once})
    queues_before = {x: queue_codes(x) for x in group}
    denied = call('POST', f'/api/games/{gid}/colonies/queue',
                  {'accessToken': tok, 'planetIds': group, 'projectCode': once, 'top': True})
    check('недоступный хоть одной колонии проект не закладывается — п. 10',
          isinstance(denied, dict) and denied.get('ERROR') == 409,
          f"«{once}» уже в очереди у {planet_by_id(group[0])['name']}, ответ"
          f" {denied.get('body', {}).get('message') if isinstance(denied, dict) else denied}")
    check('сорвавшийся приказ не трогает очередей — п. 10',
          {x: queue_codes(x) for x in group} == queues_before,
          str({x: queue_codes(x) for x in group}))

# Порядок «в хвост» остался у экрана колонии: там очередь набирают по одной и правят руками.
tail_before = queue_codes(home_id)
call('POST', f'/api/games/{gid}/planets/{home_id}/queue',
     {'accessToken': tok, 'projectCode': 'SPY'})
check('без пометки «первым» проект по-прежнему встаёт в хвост — п. 10',
      queue_codes(home_id) == tail_before + ['SPY'],
      f"было {tail_before}, стало {queue_codes(home_id)}")

# --- 10. Выбор расы и конструктор расы (п. 5, п. 7) ---
# Партия начинается с выбора расы, и на экране стоят те же тринадцать рас, что в MOO II.
# Сами расы пока заглушки: играется только своя раса, поэтому проверяется справочник.
races = call('GET', '/api/reference/races')
check('в справочнике тринадцать рас MOO II',
      len(races) == 13, f"рас {len(races)}")
check('расы те же, что в оригинале',
      {'ALKARI', 'ELERIAN', 'GNOLAM', 'MEKLAR', 'TRILARIAN', 'HUMANS', 'PSILONS'}
      <= {race['code'] for race in races},
      str(sorted(race['code'] for race in races)))

design = call('GET', '/api/reference/race-traits')
# Групп в конструкторе больше пяти: к выработке колоний добавились подсистемы —
# бой, шпионаж, корабли, правительство и родной мир. Их проверяет свой раздел ниже.
# ЧИСЛА БЮДЖЕТОВ ЗДЕСЬ НЕ ВПИСАНЫ. Они двигаются балансировкой — 10 в MOO II, потом 15,
# 20, теперь 30, — и проверка, знающая сегодняшнее наизусть, падает на первой же честной
# правке, не найдя ни одной поломки. Держимся за правило: оба бюджета пришли, оба
# положительны, вернуть слабостями больше, чем можно потратить, нельзя.
check('конструктор расы отдаёт очки и группы',
      design["picks"] > 0 and design["antiPicks"] > 0
      and design["antiPicks"] < design["picks"]
      and {'growth', 'food', 'industry', 'science', 'money'} <= {g['code'] for g in design['groups']},
      f"очков {design.get('picks')}, анти-очков {design.get('antiPicks')},"
      f" групп {len(design.get('groups', []))}")


# Цена каждой стороны расы по коду: по ней проверяется, что готовые расы MOO II
# стоят ровно бюджет — ошибка в цене или в составе тут же уводит сумму в сторону.
trait_cost = {o['code']: o['picks'] for g in design['groups'] for o in g['options']}


#: Партии конструктора расы, которые надо убрать в конце прогона. Здесь их не удалить
#: сразу: `plain` служит образцом сравнения до самого раздела флотов. Прогон обещает
#: чистую базу, а копил по несколько партий за раз, пока это не всплыло при уборке базы
#: балансировки.
race_games = []


def race_game(name, traits, seed=None):
    """
    Партия с собранной расой: возвращает её родной мир или ошибку сервера.

    Зерно нужно тем разделам, где важна не раса, а расстановка звёзд: знакомство с
    соседом идёт по дальности, и без зерна раздел то проходит, то нет.
    """
    request = {'name': 'Регресс раса', 'playerName': 'Тест',
               'galaxySize': 'SMALL', 'raceName': name, 'raceTraits': traits,
               # БЕЗ СЛУЧАЙНЫХ СОБЫТИЙ — как и главная партия прогона. Проверяется сторона
               # расы, а событие меняет ровно то, что она даёт: сдвиг оси выправил климат
               # родного мира подземным, вместимость выросла с 22 до 30, и население, у
               # которого столько еды не растёт, «не доросло до заявленной вместимости».
               # Провал был в событии, а не в игре.
               'galacticEvents': False}
    if seed is not None:
        request['seed'] = seed
    created = call('POST', '/api/games', request)
    if 'ERROR' in created:
        return created
    rid = created['game']['id']
    rtok = created['credentials']['accessToken']
    rpid = created['credentials']['playerId']
    race_games.append((rid, rtok))
    call('POST', f'/api/games/{rid}/start', {'accessToken': rtok})
    mm = call('GET', f'/api/games/{rid}/map?accessToken={rtok}')
    home = [x for st in mm['systems'] for x in st['planets']
            if x.get('homeworld') and x.get('ownerPlayerId') == rpid][0]
    players = call('GET', f'/api/games/{rid}')['players']
    return {'id': rid, 'token': rtok, 'colony': home['colony'],
            'raceName': [x for x in players if x['id'] == rpid][0]['raceName']}


# Раса сравнения: названная, но без единой стороны. Безымянной её сделать нельзя —
# теперь пустое название значит «беру готовую расу MOO II», и такая раса приходит со
# своим набором очков, сравнивать с ней было бы не с чем.
plain = race_game('Простые', [])
# Три прибавки к труду плюс слабость, которая их не касается. Слабость здесь не для
# красоты: сама тройка стоит СЕМНАДЦАТЬ очков при бюджете в пятнадцать (цены еды выросли —
# журнал цен, п. 3.31), и сервер такую расу законно отвергает. Раньше тройка стоила ровно
# пятнадцать, и проверка молча держалась на этом; плохие шпионы возвращают недостающие два
# и ни на еду, ни на промышленность, ни на науку не влияют ничем.
custom = race_game('Кринтакс', ['food-good', 'industry-good', 'science-good', 'spy-poor'])
fertile = race_game('Плодовитые', ['growth-fast'])
money = race_game('Богатые', ['money-rich'])
science = race_game('Умники', ['science-poor'])

check('партия с собранной расой создаётся', 'colony' in custom, str(custom.get('body')))
# Через .get, а не по ключу: отвергнутая раса возвращает отказ сервера, и обращение по
# ключу роняло ВЕСЬ прогон KeyError'ом вместо того, чтобы назвать провалившуюся проверку.
# Так и вышло на правке цен: причина была в двух лишних очках, а прогон молчал о ней.
check('раса зовётся так, как назвал игрок', custom.get('raceName') == 'Кринтакс', str(custom.get('raceName')))
check('еда: раса прибавляет фермеру',
      custom['colony']['foodPerFarmer'] == plain['colony']['foodPerFarmer'] + 1,
      f"{plain['colony']['foodPerFarmer']} -> {custom['colony']['foodPerFarmer']}")
check('промышленность: раса прибавляет рабочему',
      custom['colony']['productionPerWorker'] == plain['colony']['productionPerWorker'] + 1,
      f"{plain['colony']['productionPerWorker']} -> {custom['colony']['productionPerWorker']}")
check('наука: раса прибавляет учёному',
      custom['colony']['researchPerScientist'] == plain['colony']['researchPerScientist'] + 1,
      f"{plain['colony']['researchPerScientist']} -> {custom['colony']['researchPerScientist']}")
check('рост: плодовитые растут вдвое быстрее',
      fertile['colony']['growthK'] == plain['colony']['growthK'] * 2,
      f"{plain['colony']['growthK']} -> {fertile['colony']['growthK']}")
check('деньги: раса поднимает доход колонии',
      money['colony']['income'] > plain['colony']['income'],
      f"{plain['colony']['income']} -> {money['colony']['income']}")
check('наука: слабая раса теряет очко с учёного',
      science['colony']['researchPerScientist'] == plain['colony']['researchPerScientist'] - 1,
      f"{plain['colony']['researchPerScientist']} -> {science['colony']['researchPerScientist']}")

# НАБОР СВЕРХ БЮДЖЕТА СТРОИТСЯ ИЗ СПРАВОЧНИКА, А НЕ ВПИСАН КОДАМИ. Вписанная пара
# «плодовитые плюс великие промышленники» стоила двадцать очков и перебирала бюджет,
# пока он был пятнадцать; при двадцати она в него уложилась, при тридцати — тем более.
# Соседняя проверка потолка анти-выбора уже собрана из справочника по этой же причине
# (журнал, п. 3.62), и здесь теперь так же: дорогие стороны берутся по одной из группы,
# пока сумма не перевалит за бюджет.
def spending_over(limit):
    picked, spent = [], 0
    for group in design['groups']:
        if spent > limit:
            break
        rich = sorted([o for o in group['options'] if o['picks'] > 0],
                      key=lambda o: -o['picks'])
        if rich:
            picked.append(rich[0]['code'])
            spent += rich[0]['picks']
    return picked, spent


greedy, greedy_sum = spending_over(design['picks'])
check('перебор очков расы не проходит',
      greedy_sum > design['picks']
      and race_game('Жадные', greedy).get('ERROR') == 409,
      f"потратили {greedy_sum} при бюджете {design['picks']}")
# Потолок анти-выбора считается отдельно от бюджета: раса в него не укладывается и
# незаконна, хотя бюджета ей хватает с запасом.
#
# НАБОРЫ СТРОЯТСЯ ИЗ СПРАВОЧНИКА, А НЕ ВПИСАНЫ КОДАМИ. Вписанные ломались ДВАЖДЫ подряд,
# и оба раза на ЧЕСТНОЙ правке: сперва подъём потолка 10 -> 15 сделал прежнюю тройку
# законной, следом подорожание слабостей увело следующую за потолок. Обе проверки — про
# правило, а не про конкретные стороны, и цены им знать незачем (журнал, п. 3.62).
#
# Слабости берутся ПО ОДНОЙ ИЗ ГРУППЫ: так отказ не придёт по другой причине — ни «из
# группы берётся не больше одной», ни несовместимостью сторон.
def selling_over(limit):
    # Слабости из разных групп, пока возвращённое не перевалит за limit.
    picked, returned = [], 0
    for group in design['groups']:
        if returned > limit:
            break
        weak = [o for o in group['options'] if o['picks'] < 0]
        if weak:
            picked.append(weak[0]['code'])
            returned -= weak[0]['picks']
    return picked, returned


over, over_sum = selling_over(design['antiPicks'])
check('перебор анти-очков не проходит — п. 7',
      over_sum > design['antiPicks']
      and race_game('Торгаши слабостями', over).get('ERROR') == 409,
      f"вернули {over_sum} при потолке {design['antiPicks']}")
# Та же горсть без последней слабости укладывается в потолок и проходит.
fits = over[:-1]
fits_sum = -sum(trait_cost[c] for c in fits)
check('под потолок анти-очков проходит — п. 7',
      fits_sum <= design['antiPicks'] and 'colony' in race_game('Кремнёвые', fits),
      f"вернули {fits_sum} при потолке {design['antiPicks']}")

check('две особенности из одной группы не проходят',
      race_game('Путаники', ['food-good', 'food-great']).get('ERROR') == 409)
check('несуществующая особенность не проходит',
      race_game('Ошибка', ['no-such-trait']).get('ERROR') == 404)
check('несовместимые стороны вместе не проходят — п. 7',
      race_game('Едоки камня', ['lithovore', 'food-good']).get('ERROR') == 409)
# Климата родного мира в таблице MOO II нет вовсе — там любой родной мир земного типа,
# и своей особенности на климат конструктор не продаёт (п. 7).
check('климат родного мира в конструкторе не продаётся — п. 7',
      race_game('Райские', ['home-gaia']).get('ERROR') == 404)
check('несколько особых способностей разом проходят — п. 7',
      'colony' in race_game('Ловкачи', ['omniscient', 'lucky', 'stealthy-ships']))

# --- Готовые расы MOO II: каждая приходит со своим набором очков (п. 5) ---
ready_races = call('GET', '/api/reference/races')
check('справочник отдаёт тринадцать рас со сторонами — п. 5',
      len(ready_races) == 13 and all(r['traits'] for r in ready_races),
      f"рас {len(ready_races)}, без сторон "
      f"{[r['code'] for r in ready_races if not r['traits']]}")
# ГОТОВЫЕ РАСЫ НЕ ОБЯЗАНЫ БЫТЬ РАВНЫМИ ПО СИЛЕ — решение хозяина проекта (журнал,
# п. 3.70), и требования «ровно бюджет» у них больше нет. Пока цена была величиной
# договорной, ровная сумма и проверяла, что таблица списана верно; теперь цена
# измеряемая и гонится за отдачей стороны, так что подорожание просто делает расу
# дороже — то есть сильнее собранной игроком. Это и есть MOO II, где Псилоны сильнее
# Гноламов.
#
# Проверяется поэтому СОСТАВ, а не сумма: набор не пуст, все коды из справочника, и
# стоимость осмысленна (не ноль и не минус — это значило бы потерянный набор).
def race_composition_ok(race):
    traits = race['traits']
    return (bool(traits)
            and all(c in trait_cost for c in traits)
            and len(set(traits)) == len(traits)
            and sum(trait_cost[c] for c in traits) > 0)


check('готовые расы собраны законно, а стоить могут по-разному — п. 5',
      all(race_composition_ok(r) for r in ready_races),
      str({r['code']: sum(trait_cost[c] for c in r['traits']) for r in ready_races}))
# Бюджет и потолок спрашиваются ИГРОКУ — послабление касается только готовых рас.
check('игроку бюджет по-прежнему спрашивается — п. 7',
      race_game('Транжиры', ['gov-unification', 'food-great', 'world-large',
                             'growth-fast']).get('ERROR') == 409)
check('родные миры готовых рас земные, как в MOO II — п. 5',
      all(r['homeClimateCode'] == 'TERRAN' for r in ready_races),
      str({r['code']: r['homeClimateCode'] for r in ready_races}))


def ready_game(code):
    """Партия за готовую расу: возвращает её родной мир и разведанное с первого хода."""
    created = call('POST', '/api/games', {'name': 'Регресс ' + code, 'playerName': 'Тест',
                                          'galaxySize': 'SMALL', 'raceCode': code})
    rid = created['game']['id']
    rtok = created['credentials']['accessToken']
    rpid = created['credentials']['playerId']
    call('POST', f'/api/games/{rid}/start', {'accessToken': rtok})
    mm = call('GET', f'/api/games/{rid}/map?accessToken={rtok}')
    home = [x for st in mm['systems'] for x in st['planets']
            if x.get('homeworld') and x.get('ownerPlayerId') == rpid][0]
    return {'id': rid, 'token': rtok, 'planet': home, 'colony': home['colony'],
            'explored': sum(1 for st in mm['systems'] if st.get('explored')),
            'systems': len(mm['systems'])}


meklar = ready_game('MEKLAR')
check('Synthar: киборги едят вполовину меньше — п. 7',
      meklar['colony']['foodConsumption'] * 2 == meklar['planet']['population'],
      f"жителей {meklar['planet']['population']}, съедают {meklar['colony']['foodConsumption']}")
check('Synthar: великие промышленники дают рабочему две единицы сверх — п. 7',
      meklar['colony']['productionPerWorker'] == meklar['planet']['productionPerWorker'] + 2,
      f"{meklar['planet']['productionPerWorker']} -> {meklar['colony']['productionPerWorker']}")

silicoid = ready_game('SILICOIDS')
check('Lithari: литоворы не едят вовсе — п. 7',
      silicoid['colony']['foodConsumption'] == 0, str(silicoid['colony']['foodConsumption']))
# Неприхотливые считают любой мир земным (п. 4.1.2), поэтому на земном родном мире эта
# сторона не даёт ничего — как и в MOO II. Проку от неё на ядовитых и мёртвых мирах.
check('Lithari: на земном мире неприхотливость ничего не меняет — п. 4.1.2',
      silicoid['colony']['maxPopulation'] == silicoid['planet']['maxPopulation'],
      f"{silicoid['planet']['maxPopulation']} -> {silicoid['colony']['maxPopulation']}")
# Неприхотливым отходы не мешают: убирать за собой им не приходится вовсе — п. 10.
check('Lithari: неприхотливые не убирают за собой — п. 7 и п. 10',
      silicoid['colony']['pollutionFree'] is True and silicoid['colony']['pollution'] == 0,
      f"уборка {silicoid['colony']['pollution']}")
check('обычной расе грязь считают — п. 10',
      meklar['colony']['pollutionFree'] is False,
      str(meklar['colony']['pollutionFree']))

psilon = ready_game('PSILONS')
# Родной мир Псилонов БОЛЬШОЙ, а не огромный: по умолчанию он средний, как в MOO II, и
# сторона «большой мир» поднимает его на одну ступень. Здесь стояло HUGE — отпечаток той
# самой ошибки, из-за которой каждая империя начинала на ступень богаче положенного, а
# купившая сторону — на две.
check('Cerebri: великие учёные и большой родной мир — п. 7',
      psilon['colony']['researchPerScientist'] == 5 and psilon['planet']['size'] == 'LARGE',
      f"наука {psilon['colony']['researchPerScientist']}, мир {psilon['planet']['size']}")
# Изобретательность отдаётся экрану выбора: у такой расы уровень выдаётся целиком, и
# отмечать в нём одну технологию значило бы соврать — прорыв выдаст все.
check('Cerebri: изобретательность видна в состоянии исследований — п. 7',
      call('GET', f"/api/games/{psilon['id']}/research?accessToken={psilon['token']}")['creative']
      is True)

alkari = ready_game('ALKARI')
check('Aviari: мир артефактов даёт учёному два очка сверх — п. 7',
      alkari['colony']['researchPerScientist'] == 5,
      str(alkari['colony']['researchPerScientist']))

mrrshan = ready_game('MRRSHAN')
check('Felyr: богатый родной мир поднимает выработку рабочего — п. 7',
      mrrshan['planet']['minerals'] == 'ULTRA_RICH', str(mrrshan['planet']['minerals']))
check('обычная раса изобретательной не считается — п. 7',
      call('GET', f"/api/games/{mrrshan['id']}/research?accessToken={mrrshan['token']}")['creative']
      is False)

klackon = ready_game('KLACKONS')
check('Chitarri: объединение поднимает еду и производство в полтора раза — п. 14',
      klackon['colony']['food'] == klackon['colony']['foodPerFarmer']
                                   * klackon['colony']['farmers'] * 3 // 2
      # Сравнивается добытое, а не оставшееся: уборка за собой (п. 10) съедает часть
      # производства уже после того, как строй его поднял, и к строю отношения не имеет.
      and klackon['colony']['production'] + klackon['colony']['pollution']
          == klackon['colony']['productionPerWorker'] * klackon['colony']['workers'] * 3 // 2,
      f"еда {klackon['colony']['food']}, производство {klackon['colony']['production']}"
      f" + уборка {klackon['colony']['pollution']}")

trilarian = ready_game('TRILARIAN')
check('Tidari: водная раса кормится с земного мира лучше прочих — п. 4.1.2',
      trilarian['colony']['foodPerFarmer'] == trilarian['planet']['foodPerFarmer'] + 1,
      f"{trilarian['planet']['foodPerFarmer']} -> {trilarian['colony']['foodPerFarmer']}")

elerian = ready_game('ELERIAN')
check('Iluni: всевидящие видят всю галактику с первого хода — п. 15',
      elerian['explored'] == elerian['systems'],
      f"{elerian['explored']} из {elerian['systems']}")

for ready in (meklar, silicoid, psilon, alkari, mrrshan, klackon, trilarian, elerian):
    call('DELETE', f"/api/games/{ready['id']}?accessToken={ready['token']}")

# Раса переживает сохранение: слепок хранит её название и особенности. Проверяется
# на плодовитых: у них прирост вдвое выше обычного, и потеря особенностей сразу видна.
race_save = call('POST', f"/api/games/{fertile['id']}/save", {'accessToken': fertile['token']})
race_loaded = call('POST', f"/api/saves/{race_save['id']}/load")
race_gid = race_loaded['game']['id']
race_tok = race_loaded['credentials']['accessToken']
race_pid = race_loaded['credentials']['playerId']
race_map = call('GET', f'/api/games/{race_gid}/map?accessToken={race_tok}')
race_home = [x for st in race_map['systems'] for x in st['planets']
             if x.get('homeworld') and x.get('ownerPlayerId') == race_pid][0]
race_players = call('GET', f'/api/games/{race_gid}')['players']
check('загрузка: название расы',
      [x for x in race_players if x['id'] == race_pid][0]['raceName'] == 'Плодовитые',
      str([x for x in race_players if x['id'] == race_pid][0]['raceName']))
check('загрузка: особенности расы действуют',
      race_home['colony']['growthK'] == fertile['colony']['growthK'],
      f"{fertile['colony']['growthK']} -> {race_home['colony']['growthK']}")
# Поднятое сохранение — новая партия под тем же названием, и убрать её надо отдельно.
race_games.append((race_gid, race_tok))

for race in (custom, fertile, money, science):
    call('DELETE', f"/api/games/{race['id']}?accessToken={race['token']}")
call('DELETE', f'/api/games/{race_gid}?accessToken={race_tok}')
call('DELETE', f"/api/saves/{race_save['id']}")

# --- 10.5. Стороны расы, которым нужна своя подсистема (п. 7) ---
# Тяжесть мира считается от его размера и роняет производство у непривычной расы;
# неприхотливые считают любой мир земным; командные очки дают колонии.
plain_map = call('GET', f"/api/games/{plain['id']}/map?accessToken={plain['token']}")
plain_planets = [x for st in plain_map['systems'] for x in st['planets']]
check('тяжесть приходит с каждой планетой — п. 4.1',
      all(x.get('gravity') in ('LOW', 'NORMAL', 'HIGH') for x in plain_planets)
      and all(x.get('gravityLabel') for x in plain_planets),
      str({x.get('gravity') for x in plain_planets}))
check('тяжесть считается от размера мира — п. 4.1',
      all((x['gravity'] == 'LOW') == (x['size'] in ('TINY', 'SMALL'))
          and (x['gravity'] == 'HIGH') == (x['size'] == 'HUGE')
          for x in plain_planets if not x.get('homeworld')),
      str([(x['size'], x['gravity']) for x in plain_planets[:6]]))
# Цена тяжести приходит рядом с самой тяжестью — п. 4.1: список планет читают, выбирая
# цель колониального корабля, и последствие должно стоять там же, где значение (так и в
# оригинале, docs/moo2/planets.png: под «Low G» написано «-25% prod»).
GRAVITY_COST = {'LOW': -25, 'NORMAL': 0, 'HIGH': -50}
check('цена тяжести приходит вместе с ней — п. 4.1',
      all(x.get('gravityPenaltyPercent') == GRAVITY_COST[x['gravity']] for x in plain_planets),
      str([(x['gravity'], x.get('gravityPenaltyPercent')) for x in plain_planets[:6]]))

# Родной мир расе всегда по силам: она на нём выросла — п. 7.
check('родной мир обычной тяжести при любом размере — п. 7',
      all(x['gravity'] == 'NORMAL' for x in plain_planets if x.get('homeworld')),
      str([(x['size'], x['gravity']) for x in plain_planets if x.get('homeworld')]))

# Неприхотливые: ядовитый мир вмещает столько же, сколько земной того же размера.
tolerant_home = race_game('Неприхотливые', ['tolerant'])
check('партия с неприхотливыми заводится — п. 7', 'colony' in tolerant_home,
      str(tolerant_home.get('body')))

# НАСЕЛЕНИЕ ДОРАСТАЕТ РОВНО ДО ЗАЯВЛЕННОЙ ВМЕСТИМОСТИ — п. 4.1, п. 7.
#
# Проверка заведена по настоящей ошибке, и ошибка эта пряталась от всех 288 юнит-тестов.
# Расовую прибавку к вместимости (неприхотливые, водные, подземные) считали ДВА места
# по-разному: прирост считался с расой (`ColonyService.growthK`), а фаза роста обрезала
# результат потолком БЕЗ расы. Прибавка обнулялась целиком, при том что окно колонии
# показывало её честно — оттого и не замечали. Замер круга 7 назвал цену молчанием:
# неприхотливые −13,1 очка при цене 12, подземные −9,9 при цене 3, то есть сторона не
# бесполезна, а ВРЕДНА (империя ИИ видела свободное место вечно и держала такие колонии
# на бесконечной стройке домов).
#
# Проверяется поэтому не формула, а проводка: растим колонию, пока прирост не встанет, и
# сверяем итог с тем числом, которое сервер сам же и назвал. Подземные взяты потому, что
# их прибавка не зависит от климата и видна прямо на родном мире, — колонизировать ничего
# не нужно.
# ЗЕРНО ЗДЕСЬ ОБЯЗАТЕЛЬНО: проверка растит колонию шесть сотен ходов и сверяет итог с
# числом, которое сервер сам назвал, — а без зерна это лотерея. Однажды она и проиграла:
# вместимость к концу роста оказалась 30 вместо обычных 22, и население, которому столько
# еды взять неоткуда, «не доросло». Поломки в игре не было: на закреплённой партии колония
# дорастает ровно до потолка (22000 из 22000, баланс еды в ноль).
deep = race_game('Подземные', ['subterranean'], seed=11)
check('партия с подземными заводится — п. 7', 'colony' in deep, str(deep.get('body')))
if 'colony' in deep:
    # Ходы гоняем пачками по пять: карта нужна не каждый ход, а только чтобы увидеть,
    # что рост встал. Три пачки без движения — значит колония упёрлась в свой потолок.
    home, stalled, before = None, 0, -1
    for _ in range(130):
        for _ in range(5):
            end_turn(deep['id'], deep['token'])
        fresh = call('GET', f"/api/games/{deep['id']}/map?accessToken={deep['token']}")
        home = [x for st in fresh['systems'] for x in st['planets'] if x.get('homeworld')][0]
        if home['colony']['populationK'] == before:
            stalled += 1
            if stalled >= 3:
                break
        else:
            stalled = 0
        before = home['colony']['populationK']
    # Вместимость спрашиваем у КОЛОНИИ: там она посчитана с расой, как её видит игрок.
    # Именно с этим числом и обязан сойтись рост — в том и была ошибка, что не сходился.
    # Сверяем ТЫСЯЧИ с тысячами: у самого потолка целое число жителей стоит на месте
    # десятками ходов, пока тысячи ещё ползут, и сравнение целых объявило бы поломку там,
    # где колония просто не доросла последней сотни (так и вышло: 21 при вместимости 22).
    check('население дорастает до заявленной вместимости — п. 4.1, п. 7',
          home is not None
          and home['colony']['populationK'] == home['colony']['maxPopulation'] * 1000,
          f"выросло до {home['colony']['populationK']} тысяч при вместимости "
          f"{home['colony']['maxPopulation']} ({home['colony']['maxPopulation'] * 1000} тысяч)"
          if home else 'родной мир не найден')
# Командные очки: их дают колонии, занимают корабли, а военачальникам достаётся вдвое.
plain_command = call('GET', f"/api/games/{plain['id']}/ships?accessToken={plain['token']}")
warlord = race_game('Военачальники', ['warlord'])
warlord_command = call('GET', f"/api/games/{warlord['id']}/ships?accessToken={warlord['token']}")
check('командные очки приходят с флотом — п. 8',
      plain_command['commandCapacity'] > 0 and plain_command['commandUsed'] == 0,
      f"{plain_command['commandUsed']} из {plain_command['commandCapacity']}")
check('военачальникам колония даёт вдвое больше командных очков — п. 7',
      warlord_command['commandCapacity'] == plain_command['commandCapacity'] * 2,
      f"{plain_command['commandCapacity']} -> {warlord_command['commandCapacity']}")

# Телепаты подчиняют колонию вместо десанта, а прочим это недоступно вовсе — п. 7, п. 12.
check('нетелепату подчинение недоступно — п. 7',
      plain_command['telepathic'] is False, str(plain_command['telepathic']))
telepaths = race_game('Телепаты', ['telepathic'])
telepath_fleet = call('GET', f"/api/games/{telepaths['id']}/ships?accessToken={telepaths['token']}")
check('телепаты помечены во флоте — п. 7',
      telepath_fleet['telepathic'] is True, str(telepath_fleet['telepathic']))

# --- 11. Подсистемы: правительство, родной мир, бой, шпионаж, корабли ---
check('конструктор знает все подсистемы',
      {g['code'] for g in design['groups']} >= {'ground', 'espionage', 'ship-attack',
                                                'ship-defense', 'government'},
      str([g['code'] for g in design['groups']]))
# Таблица оригинала: четыре правительства, девять лестниц по три ступени и двадцать две
# особые способности. Ни одной своей строки сверх неё — п. 7.
design_codes = [o['code'] for g in design['groups'] for o in g['options']]
check('конструктор — ровно таблица MOO II, 53 особенности — п. 7',
      len(design_codes) == 53 and len(set(design_codes)) == 53
      and not any(c.startswith('home-') for c in design_codes),
      f"особенностей {len(design_codes)} в {len(design['groups'])} группах")
check('конструктор знает все группы MOO II — п. 7',
      {g['code'] for g in design['groups']} >= {'gravity', 'population-capacity', 'homeworld',
                                                'food-consumption', 'diplomacy', 'creativity',
                                                'economics', 'special'},
      str([g['code'] for g in design['groups']]))

# Феодализм (-4) + великие бойцы (4) + хорошие шпионы (3) = 3 очка.
krull = race_game('Крулл', ['gov-feudal', 'ground-great', 'spy-good'])
check('партия с расой из подсистем создаётся', 'colony' in krull, str(krull.get('body')))

krull_players = call('GET', f"/api/games/{krull['id']}")['players']
krull_me = [x for x in krull_players if x['raceName'] == 'Крулл'][0]
check('правительство названо в составе игроков — п. 14',
      krull_me['government'] == 'Феодализм', str(krull_me.get('government')))
# Феодализм MOO II роняет науку вдвое, а не на очко с учёного: выработка учёного та же,
# половину теряет уже колония. Лаборатории под этот штраф не попадают, но их тут нет.
check('феодализм роняет науку колонии вдвое — п. 14',
      krull['colony']['researchPerScientist'] == plain['colony']['researchPerScientist']
      and krull['colony']['research'] == krull['colony']['researchPerScientist']
                                         * krull['colony']['scientists'] // 2,
      f"учёных {krull['colony']['scientists']}, науки {krull['colony']['research']}")

krull_map = call('GET', f"/api/games/{krull['id']}/map?accessToken={krull['token']}")
krull_home = [x for st in krull_map['systems'] for x in st['planets']
              if x.get('homeworld') and x.get('ownerPlayerId') == krull_me['id']][0]
# Климат родного мира приходит от справочной расы, а не из конструктора: своей
# особенности на климат в MOO II нет — п. 7.
reference_climates = {r['homeClimateCode'] for r in call('GET', '/api/reference/races')}
check('родной мир получил климат справочной расы — п. 4.1.2',
      krull_home['climate'] in reference_climates,
      f"{krull_home['climateLabel']} при {sorted(reference_climates)}")

spy = call('GET', f"/api/games/{krull['id']}/espionage?accessToken={krull['token']}")
# Хорошие шпионы MOO II — это +10% и здесь одно лишнее очко за ход поверх базового.
# Феодализм шпионам не помогает: перевес в обороне дают диктатура и объединение.
check('разведка отдаёт приход за ход — п. 13',
      spy['pointsPerTurn'] == 2 and spy['racePoints'] == 1,
      f"за ход {spy['pointsPerTurn']}, от расы {spy['racePoints']}")
end_turn(krull['id'], krull['token'])
spy_after = call('GET', f"/api/games/{krull['id']}/espionage?accessToken={krull['token']}")
check('очки шпионажа копятся в конце хода — п. 13',
      spy_after['points'] == spy['points'] + spy['pointsPerTurn'],
      f"{spy['points']} -> {spy_after['points']}")

# Корабли: раса поднимает атаку и защиту проектов — п. 8.
# Отличные канониры (4) + хорошие пилоты (3) = 7 очков: +50% к залпу, +25% к защите.
pilots = race_game('Пилоты', ['ship-attack-great', 'ship-defense-good'])
plain_fleet = call('GET', f"/api/games/{plain['id']}/ships?accessToken={plain['token']}")
pilots_fleet = call('GET', f"/api/games/{pilots['id']}/ships?accessToken={pilots['token']}")
check('флот отдаёт проекты кораблей — п. 8',
      len(plain_fleet['designs']) > 0 and plain_fleet['fleets'] == [],
      f"проектов {len(plain_fleet['designs'])}, флотов {len(plain_fleet['fleets'])}")
check('раса поднимает атаку и защиту кораблей — п. 8',
      pilots_fleet['designs'][0]['attack'] == plain_fleet['designs'][0]['attack'] * 150 // 100
      and pilots_fleet['designs'][0]['defense'] == plain_fleet['designs'][0]['defense'] * 125 // 100,
      f"атака {plain_fleet['designs'][0]['attack']} -> {pilots_fleet['designs'][0]['attack']}, "
      f"защита {plain_fleet['designs'][0]['defense']} -> {pilots_fleet['designs'][0]['defense']}")

# Наземный бой: правила проверены юнит-тестами, здесь — доступ к действию (п. 12).
krull_alien = [x for st in krull_map['systems'] for x in st['planets']
               if x.get('ownerPlayerId') and x['ownerPlayerId'] != krull_me['id']]
if krull_alien:
    other_system = call('POST', f"/api/games/{krull['id']}/planets/{krull_home['id']}/invade",
                        {'accessToken': krull['token'], 'targetPlanetId': krull_alien[0]['id'], 'troops': 2})
    check('десант не летит в чужую систему без транспортов — п. 12',
          other_system.get('ERROR') == 409, str(other_system.get('body', {}).get('message')))
own = call('POST', f"/api/games/{krull['id']}/planets/{krull_home['id']}/invade",
           {'accessToken': krull['token'], 'targetPlanetId': krull_home['id'], 'troops': 2})
check('свою колонию захватывать нечем — п. 12', own.get('ERROR') == 409)

for race in (krull, pilots, plain):
    call('DELETE', f"/api/games/{race['id']}?accessToken={race['token']}")

# --- 12. Шпионы, разведка чужих систем и дипломатия (п. 13, 15) ---
# Зерно постоянное: дальше идут разведка, знакомство, обмен технологиями и дипломатия
# ИИ — всё это держится на том, есть ли сосед в пределах досягаемости.
shadows = race_game('Тени', ['spy-great'], seed=1010)
shadow_id, shadow_tok = shadows['id'], shadows['token']
shadow_players = call('GET', f'/api/games/{shadow_id}')['players']
shadow_pid = [x for x in shadow_players if x['raceName'] == 'Тени'][0]['id']


def shadow_map(reveal='false'):
    return call('GET', f'/api/games/{shadow_id}/map?accessToken={shadow_tok}&revealAll={reveal}')


def shadow_home():
    return [x for st in shadow_map()['systems'] for x in st['planets']
            if x.get('homeworld') and x.get('ownerPlayerId') == shadow_pid][0]


home = shadow_home()
check('шпион доступен любой колонии без исследований — п. 13',
      'SPY' in [x['code'] for x in home['colony']['available']],
      str([x['code'] for x in home['colony']['available']]))

call('POST', f"/api/games/{shadow_id}/planets/{home['id']}/project",
     {'accessToken': shadow_tok, 'projectCode': 'SPY'})
for _ in range(15):
    end_turn(shadow_id, shadow_tok)
    if call('GET', f'/api/games/{shadow_id}/espionage?accessToken={shadow_tok}')['spies'] > 0:
        break
spy_state = call('GET', f'/api/games/{shadow_id}/espionage?accessToken={shadow_tok}')
check('колония построила шпиона — п. 13', spy_state['spies'] == 1, f"шпионов {spy_state['spies']}")
check('шпион приносит очко разведки за ход — п. 13',
      spy_state['pointsPerTurn'] == 1 + spy_state['racePoints'] + spy_state['spies'],
      f"за ход {spy_state['pointsPerTurn']}, от расы {spy_state['racePoints']}")

explored_before = len([st for st in shadow_map()['systems'] if st['explored']])

# Разведывают только корабли — п. 15: чужая система откроется приходом флота. Цель
# выбирается среди ещё незнакомых: с ближними соседями империя знакомится по дальности,
# ни разу никуда не слетав, и лететь к ним было бы нечего проверять.
relations_before = call('GET', f'/api/games/{shadow_id}/diplomacy?accessToken={shadow_tok}')
known_before_ids = {r['playerId'] for r in relations_before}

# Дальность меряется от своих миров, и дальше неё флот не летает вовсе — п. 8. Раньше
# опорой считалась и стоянка флота, поэтому флот доползал куда угодно цепочкой перелётов;
# теперь так нельзя, и до чужой звезды дорогу прокладывают заставы — тем они в MOO II и
# нужны. Цель — ближайшая чужая система: до дальнего угла галактики застав не напасёшься.
shadow_reach = set(call_auth('GET', f'/api/games/{shadow_id}/ships', shadow_tok)['reachableSystemIds'])
shadow_home_system = [st for st in shadow_map('true')['systems']
                      if any(pl['id'] == home['id'] for pl in st['planets'])][0]
strangers = [st for st in shadow_map('true')['systems']
             if {pl['ownerPlayerId'] for pl in st['planets'] if pl.get('ownerPlayerId')}
             - known_before_ids - {shadow_pid}]
alien_system = min(
    strangers,
    key=lambda st: (st['x'] - shadow_home_system['x']) ** 2
    + (st['y'] - shadow_home_system['y']) ** 2,
    default=None)
alien_owners = set() if alien_system is None else (
    {pl['ownerPlayerId'] for pl in alien_system['planets'] if pl.get('ownerPlayerId')}
    - {shadow_pid})
check('хозяин неразведанной системы до прилёта неизвестен — п. 15',
      not (alien_owners & known_before_ids),
      f"знакомы {len(known_before_ids)}, хозяева цели {len(alien_owners)}")

# Систему открывает флот: колония строит корабль, флот летит к чужой звезде.
shadow_fleet = build_ship(shadow_id, shadow_tok, home['id'])
check('колония построила корабль для разведки — п. 8', len(shadow_fleet) == 1, str(shadow_fleet))

# Недостижимая звезда: приказ к ней сервер обязан отвергнуть, какой бы соблазнительной
# она ни была, — дальность считается от своих миров (п. 8).
#
# Достижимое спрашивается заново: пока строился корабль, империя изучила Chemistry первого
# уровня, а с ней и Extended Fuel Tanks — дальность выросла в полтора раза, и звезда,
# бывшая недостижимой в начале, могла попасть в пузырь.
far_now = set(call_auth('GET', f'/api/games/{shadow_id}/ships', shadow_tok)['reachableSystemIds'])
far_away = next((st for st in shadow_map('true')['systems'] if st['id'] not in far_now), None)
if far_away:
    order = call('POST', f"/api/games/{shadow_id}/fleets/{shadow_fleet[0]['id']}/move",
                 {'accessToken': shadow_tok, 'targetSystemId': far_away['id']})
    check('дальше дальности топлива приказ не отдать — п. 8', order.get('ERROR') == 409,
          str(order.get('body', {}).get('message')))

# Чужая звезда обычно вне дальности старта: до неё империя дотягивается заставами — п. 8.
bridged = alien_system is not None and reach_towards(
    shadow_id, shadow_tok, home['id'], alien_system['id'])
check('заставы раздвигают дальность империи — п. 8', bridged,
      f"систем в пределах дальности было {len(shadow_reach)}, стало "
      f"{len(call_auth('GET', f'/api/games/{shadow_id}/ships', shadow_tok)['reachableSystemIds'])}")

# Флот с прошлой проверки успел полетать за заставой: берём его заново. Флота может и не
# остаться — корабль-застава одноразовый, и если заставами дотянулись им одним, лететь
# дальше некому. Тогда проверки ниже проваливаются по одной, а не роняют прогон целиком.
scouts = call_auth('GET', f'/api/games/{shadow_id}/fleets', shadow_tok) if bridged else []
check('после расселения заставами флот у империи остался — п. 8', bool(scouts) or not bridged,
      f"флотов {len(scouts)}")
if bridged and scouts:
    scout = scouts[0]
    order = call('POST', f"/api/games/{shadow_id}/fleets/{scout['id']}/move",
                 {'accessToken': shadow_tok, 'targetSystemId': alien_system['id']})
    check('приказ задаёт курс и срок, а не переносит флот сразу — п. 8',
          order.get('arrivalTurn', 0) > order.get('departureTurn', 0)
          and order.get('starSystemId') != alien_system['id'],
          str(order.get('body', {}).get('message', order.get('arrivalTurn'))))

    arrived = fly_to(shadow_id, shadow_tok, alien_system['id'], limit=60)
    check('флот долетел до неразведанной системы — п. 15',
          arrived is not None and arrived['starSystemId'] == alien_system['id'], str(arrived))

    opened = [st for st in shadow_map()['systems'] if st['id'] == alien_system['id']][0]
    check('приход флота открыл систему — п. 15',
          opened['explored'] is True and len(opened['planets']) > 0,
          f"разведана {opened['explored']}, планет {len(opened['planets'])}")
    check('разведанных систем стало больше — п. 15',
          len([st for st in shadow_map()['systems'] if st['explored']]) > explored_before)

relations = call('GET', f'/api/games/{shadow_id}/diplomacy?accessToken={shadow_tok}')
# Знакомых может оказаться и больше одного: до далёкой звезды флот идёт цепочкой
# перелётов и знакомится с хозяевами всех колоний, какие встретит по дороге (см. проверку
# разведки выше). Сколько именно их будет, зависит от сгенерированной галактики, поэтому
# проверяется правило, а не число: знакомство состоялось, и у каждого знакомства есть ход.
#
# Нейтралитет здесь уже не требуется: пока флот летел, сосед мог объявить войну сам
# (п. 15, дипломатия ИИ). Что знакомство начинается с нейтралитета, проверяется ниже, на
# партии, где никто ещё не успел ничего сделать.
check('приход флота знакомит с хозяевами колоний — п. 15',
      alien_owners <= {r['playerId'] for r in relations}
      and len(relations) > 0
      and all(r['metTurn'] >= 1 for r in relations),
      str([(r['raceName'], r['stance'], r['metTurn']) for r in relations]))

# ИИ исследует сам — п. 9. Своими глазами его дерево не увидеть, зато видно то, ради чего
# это и сделано: у знакомого ИИ появляется чем меняться (п. 15). До выбора цели за ИИ
# список был пуст всегда — империи ИИ навсегда оставались с тем, с чем начали.
#
# Ждём ходами, а не проверяем сразу: ИИ мог успеть изучить ровно то, что уже есть у нас
# (общие уровни в начале дерева у всех одни), и тогда меняться пока нечем. Игрок к этому
# времени не исследует, а ИИ продолжает, поэтому рано или поздно он уходит вперёд.
# Сосед берётся из знакомых: знакомство приходит и по дальности (п. 15), а не только
# приходом флота, и для обмена важно лишь то, что империя ИИ знакома и исследует сама.
# Знакомых может не оказаться вовсе: в иной галактике соседи стоят дальше дальности, и
# познакомиться не с кем. Тогда проверка проваливается со своей причиной, а не роняет
# прогон на пустом списке — говорить о неисследующем ИИ там, где просто некому знакомиться,
# было бы неправдой.
ai_id = sorted({r['playerId'] for r in relations})[0] if relations else None
ai_wanted = []
for _ in range(60 if ai_id else 0):
    ai_wanted = call_auth('GET', f'/api/games/{shadow_id}/diplomacy/{ai_id}/technologies',
                          shadow_tok)['wanted']
    if ai_wanted:
        break
    end_turn(shadow_id, shadow_tok)
check('ИИ исследует сам, и с ним есть что обменять — п. 9, п. 15',
      len(ai_wanted) > 0,
      f"у соседа есть чего нет у нас: {[t['name'] for t in ai_wanted][:5]}" if ai_id
      else 'знакомых империй нет: до соседей не дотянулись')

# Характер правителя — п. 15: по нему игрок судит, чего от соседа ждать, и он же решает
# всё поведение ИИ. У человека характера нет: это про империи ИИ.
relations = call_auth('GET', f'/api/games/{shadow_id}/diplomacy', shadow_tok)
check('у правителя каждой империи ИИ есть характер — п. 15',
      all(len((r.get('character') or '').split()) == 2 for r in relations),
      str([(r['raceName'], r.get('character')) for r in relations]))

# Дипломатия ИИ — п. 15: сосед не только отвечает, но и приходит сам. Что именно он
# сделает, зависит от характера и от расстановки сил, поэтому проверяется правило, а не
# договор: отношения изменил не игрок. Ждём ходами — разговор ИИ заводит не каждый ход.
#
# Отталкивающие соседи в счёт не идут (п. 7): с ними не бывает ни договоров, ни дани, ни
# подарков, а война требует перевеса — молчание такого соседа это правило, а не поломка.
# Кто отталкивающий, видно в окне «Инфо»: там стороны рас всех знакомых империй (п. 11.1).
info_empires = call_auth('GET', f'/api/games/{shadow_id}/info', shadow_tok)['empires']
repulsive = {e['playerId'] for e in info_empires
             if any(t['code'] == 'repulsive' for t in e['traits'])}
talkative = [r for r in relations if r['playerId'] not in repulsive]

for _ in range(40):
    relations = call_auth('GET', f'/api/games/{shadow_id}/diplomacy', shadow_tok)
    if any(r['treaties'] or r['stance'] != 'NEUTRAL' for r in relations):
        break
    end_turn(shadow_id, shadow_tok)

acted = [r for r in relations if r['treaties'] or r['stance'] != 'NEUTRAL']
check('ИИ сам заводит дипломатию, а не только отвечает — п. 15',
      len(acted) > 0 or not talkative,
      str([(r['raceName'], r.get('character'), r['stance'], r['treatyLabels']) for r in relations])
      + ('' if talkative else ' — все знакомые отталкивающие, переговоров с ними не бывает'))

neighbour = relations[0]['playerId']

# Мир принимают по доверию — п. 15. Сколько его нужно, зависит от характера правителя
# (ксенофоба уговорить труднее прочих), поэтому доверие сперва поднимается подарками:
# проверяется правило «доверия хватило — мир заключён», а не конкретное число.
#
# Мир мог наступить и без нас: сосед предлагает договоры сам, а союз подразумевает мир.
# Тогда предлагать нечего — правило проверяется по состоянию отношений.
peace = relations[0]
for _ in range(6):
    if peace['stance'] == 'PEACE':
        break
    purse = [p for p in call('GET', f'/api/games/{shadow_id}')['players']
             if p['id'] == shadow_pid][0]['credits']
    call('POST', f'/api/games/{shadow_id}/diplomacy',
         {'accessToken': shadow_tok, 'targetPlayerId': neighbour,
          'action': 'GIFT_CREDITS', 'credits': max(1, purse // 2)})
    peace = call('POST', f'/api/games/{shadow_id}/diplomacy',
                 {'accessToken': shadow_tok, 'targetPlayerId': neighbour,
                  'action': 'PROPOSE_PEACE'})
check('мир заключают, когда доверия хватает — п. 15', peace['stance'] == 'PEACE',
      f"{peace['stanceLabel']}, доверие {peace['trust']}")

# Сосед мог успеть подписать с нами пакт сам — а пакт войну запрещает (это правило
# проверяется отдельно, в блоке шпионажа). Здесь нужен чистый лист, иначе объявление
# войны упрётся в договор, а не в согласие соседа.
for treaty in peace['treaties']:
    call('POST', f'/api/games/{shadow_id}/diplomacy',
         {'accessToken': shadow_tok, 'targetPlayerId': neighbour,
          'action': 'BREAK_TREATY', 'treaty': treaty})
war = call('POST', f'/api/games/{shadow_id}/diplomacy',
           {'accessToken': shadow_tok, 'targetPlayerId': neighbour, 'action': 'DECLARE_WAR'})
check('война объявляется без согласия — п. 15', war['stance'] == 'WAR', war['stanceLabel'])
refused = call('POST', f'/api/games/{shadow_id}/diplomacy',
               {'accessToken': shadow_tok, 'targetPlayerId': neighbour, 'action': 'PROPOSE_PEACE'})
# Планку согласия на мир опускает характер соседа: миролюбивый и дипломат снимают
# по 15 очков каждый (`DiplomacyService.treatyBias`), поэтому исход зависит от того,
# какой правитель достался соседу. Проверяется правило, а не исход: отказ — или
# согласие, но только когда доверия хватает и с самой низкой планкой.
check('после войны мир даётся не даром — п. 15',
      refused['stance'] == 'WAR' or refused['trust'] >= 20,
      f"{refused['stanceLabel']}, доверие {refused['trust']}")

# Незнакомый — это тот, кого нет в списке знакомых, а не просто «не сосед»: за полсотни
# ходов флот и дальность знакомят империю не с одним соседом, и второй в списке игроков
# вполне может оказаться старым знакомым.
known_ids = {r['playerId'] for r in call_auth('GET', f'/api/games/{shadow_id}/diplomacy', shadow_tok)}
strangers = [x for x in shadow_players if x['id'] != shadow_pid and x['id'] not in known_ids]
if strangers:
    unknown = call('POST', f'/api/games/{shadow_id}/diplomacy',
                   {'accessToken': shadow_tok, 'targetPlayerId': strangers[0]['id'],
                    'action': 'PROPOSE_PEACE'})
    check('с незнакомой империей дипломатии нет — п. 15', unknown.get('ERROR') == 409,
          str(unknown.get('body', {}).get('message')))

# Знакомство по дальности — п. 15: в MOO II контакт возникает от соседства, а не от
# встречи флотов, и ни один корабль для него не нужен. Само правило дальности покрыто
# юнит-тестом (FlightRulesTest), здесь проверяется, что список знакомых живёт без флота
# и что заведённые отношения взаимны и начинаются с нейтралитета.
# События выключены: дальше по этой партии снимается летопись, а случайное событие успевает
# снести звёздную базу родного мира вместе с частью населения — и проверка «постройки
# считаются ценой» проваливается не на летописи, а на невезении. Те же грабли, что у главной
# партии прогона и у «Тактики».
range_game = call('POST', '/api/games', {'name': 'Соседство', 'playerName': 'Сосед',
                                         'galaxySize': 'SMALL', 'raceTraits': [],
                                         'galacticEvents': False})
range_id, range_tok = range_game['game']['id'], range_game['credentials']['accessToken']
call('POST', f'/api/games/{range_id}/start', {'accessToken': range_tok})
for _ in range(3):
    end_turn(range_id, range_tok)
# Человеку цель за спиной не выбирают: экран для этого у него есть, и подставленная
# технология отнимала бы решение — п. 9. Три хода прошло, цель по-прежнему не выбрана.
range_research = call_auth('GET', f'/api/games/{range_id}/research', range_tok)
# Изученное при этом не пусто: три первых уровня дерева есть у всех с первого хода
# (п. 9), и проверяется здесь именно ЦЕЛЬ — что её не выбрали за человека, — а не то,
# что империя начинает партию с пустой головой.
check('человеку цель исследования не подставляют — п. 9',
      'optionCode' not in range_research
      and all(a['levelOrder'] == 1 and a['acquiredTurn'] == 1
              for a in range_research['acquired']),
      str({k: v for k, v in range_research.items() if k != 'acquired'}))
range_relations = call_auth('GET', f'/api/games/{range_id}/diplomacy', range_tok)
check('знакомство заводится без единого корабля — п. 15',
      isinstance(range_relations, list)
      and call_auth('GET', f'/api/games/{range_id}/fleets', range_tok) == []
      and all(r['stance'] == 'NEUTRAL' and r['metTurn'] >= 1 for r in range_relations),
      f"знакомых {len(range_relations)}: "
      f"{[(r['raceName'], r['stance']) for r in range_relations]}")

# Окно «Инфо» — п. 11.1: летопись империй и свойства знакомых рас. Три хода уже прошли,
# значит и замеров должно быть три: они снимаются в конце каждого хода.
range_info = call_auth('GET', f'/api/games/{range_id}/info', range_tok)
range_own = [e for e in range_info['empires'] if e['own']][0]
check('летопись копится по ходу за ход — п. 11.1',
      len(range_own['history']) == range_info['turn'] - 1
      and [p['turn'] for p in range_own['history']] == sorted(p['turn'] for p in range_own['history']),
      f"ход {range_info['turn']}, замеров {len(range_own['history'])}")
check('замер описывает империю целиком — п. 11.1',
      all(key in range_own['history'][-1] for key in
          ('populationK', 'colonies', 'buildings', 'production', 'research', 'fleetPower',
           'technologies', 'credits', 'might'))
      and range_own['history'][-1]['populationK'] > 0,
      str(range_own['history'][-1]))
# Постройки — величина графика оригинала, и меряется она ценой, а не числом: у родного
# мира с первого хода стоит звёздная база (90 единиц производства).
check('постройки в летописи считаются ценой — п. 11.1',
      range_own['history'][-1]['buildings'] >= 90,
      str(range_own['history'][-1]['buildings']))
# Мощь — сводная величина графика и та же, по которой сравнивают себя империи ИИ (п. 15):
# она больше любой своей части, иначе части в неё не вошли бы.
last_point = range_own['history'][-1]
check('мощь империи сводит замер в одно число — п. 11.1, п. 15',
      last_point['might'] > max(last_point['production'], last_point['research'],
                                last_point['fleetPower'], last_point['technologies']),
      f"мощь {last_point['might']}")
check('в окне «Инфо» стороны своей расы и правительство — п. 7, п. 11.1',
      range_own['government'] and isinstance(range_own['traits'], list),
      f"{range_own['government']}, сторон {len(range_own['traits'])}")

# Незнакомых в окне нет — п. 15: иначе оно оказалось бы разведкой сильнее всякого шпиона.
range_players = call('GET', f'/api/games/{range_id}')['players']
range_known = {r['playerId'] for r in range_relations} | {range_own['playerId']}
check('в окне «Инфо» только знакомые империи — п. 15',
      {e['playerId'] for e in range_info['empires']} == range_known
      and len(range_players) > len(range_known),
      f"в партии {len(range_players)}, в окне {len(range_info['empires'])}")

call('DELETE', f'/api/games/{range_id}?accessToken={range_tok}')

# Отталкивающая раса — п. 7, п. 15: договоров с ней не бывает, только война и мир.
# Знакомство добывается флотом, иначе отказ пришёл бы за незнакомство, а не за расу —
# и проверка молча проходила бы не тем путём.
rep_game = call('POST', '/api/games', {'name': 'Отталкивающие', 'playerName': 'Р',
                                       'galaxySize': 'SMALL', 'raceName': 'Силикоид',
                                       'raceTraits': ['repulsive']})
rep_id, rep_tok = rep_game['game']['id'], rep_game['credentials']['accessToken']
rep_pid = rep_game['credentials']['playerId']
call('POST', f'/api/games/{rep_id}/start', {'accessToken': rep_tok})
rep_map = call('GET', f'/api/games/{rep_id}/map?accessToken={rep_tok}&revealAll=true')
rep_home = [pl for st in rep_map['systems'] for pl in st['planets']
            if pl.get('homeworld') and pl.get('ownerPlayerId') == rep_pid][0]
rep_alien = [st for st in rep_map['systems']
             if any(pl.get('ownerPlayerId') and pl['ownerPlayerId'] != rep_pid
                    for pl in st['planets'])][0]
build_ship(rep_id, rep_tok, rep_home['id'])
fly_to(rep_id, rep_tok, rep_alien['id'])
rep_known = call_auth('GET', f'/api/games/{rep_id}/diplomacy', rep_tok)
if rep_known:
    target = rep_known[0]['playerId']
    treaty = call('POST', f'/api/games/{rep_id}/diplomacy',
                  {'accessToken': rep_tok, 'targetPlayerId': target,
                   'action': 'PROPOSE_TREATY', 'treaty': 'TRADE'})
    message = str(treaty.get('body', {}).get('message', ''))
    check('отталкивающей расе договор не заключить — п. 7',
          treaty.get('ERROR') == 409 and 'отталкивающая' in message, message)
    # Война и мир отталкивающим доступны: в MOO II им остаётся ровно это.
    #
    # Война может идти и без нашего объявления: сосед ИИ объявляет её сам (п. 15), и
    # отталкивающему он объявляет её охотнее прочих. Тогда сервер отвечает «и так война»,
    # и это тот же ответ по существу: воевать отталкивающей расе не запрещено.
    war = call('POST', f'/api/games/{rep_id}/diplomacy',
               {'accessToken': rep_tok, 'targetPlayerId': target, 'action': 'DECLARE_WAR'})
    # Текст — на языке заголовка запроса; на всякий случай принимаем оба (п. 3.5).
    message = str(war.get('body', {}).get('message', ''))
    already = 'и так война' in message or 'already at war' in message
    check('война отталкивающей расе доступна — п. 7',
          war.get('stance') == 'WAR' or already,
          str(war.get('stanceLabel', war.get('body', {}).get('message', war))))
    # Дипломатия ИИ ходит тем же путём, что и игрок, — изнутри пересчёта хода (п. 15).
    # Пока отказ помечал транзакцию хода на откат, партия с отталкивающим соседом
    # переставала считаться совсем: каждый конец хода отвечал 500 и ход не наступал.
    reports = [end_turn(rep_id, rep_tok) for _ in range(10)]
    check('невозможное предложение соседа не ломает конец хода — п. 15',
          all((r or {}).get('report') for r in reports),
          str([(r or {}).get('ERROR', 'ok') for r in reports]))
call('DELETE', f'/api/games/{rep_id}?accessToken={rep_tok}')

# Обмен технологиями и подарки — п. 15. Партия на двоих людей: у ИИ технологий нет вовсе
# (своего исследования он не ведёт), и обменивать с ним было бы нечего.
# ПАРТИЯ РОВНО НА ДВОИХ: сценарий про двух людей, и живой сосед ему не нужен. Добор ИИ
# попадал сюда молча, а с тех пор как ИИ обзавёлся разведкой и водит флоты сам, его
# корабли перехватывали одинокий корабль-курьер, а то и отнимали родной мир —
# проверки валились ЧЕРЕЗ РАЗ, не отличая поломку от невезения. Двое участников
# стали возможны после того, как из игры убрали победу на выборах Высшего совета:
# прежде партия из двух империй обрывалась голосованием на двадцать пятом ходу.
duo = call('POST', '/api/games', {'name': 'Обмен', 'playerName': 'Первый',
                                  'galaxySize': 'SMALL', 'raceTraits': [], 'seed': 3131,
                                  'totalPlayers': 2})
duo_id, tok_one = duo['game']['id'], duo['credentials']['accessToken']
pid_one = duo['credentials']['playerId']
duo_two = call('POST', f'/api/games/{duo_id}/join', {'playerName': 'Второй'})
tok_two, pid_two = duo_two['credentials']['accessToken'], duo_two['credentials']['playerId']
call('POST', f'/api/games/{duo_id}/start', {'accessToken': tok_one})


def duo_research(token, other, category, order, option, limit=200):
    """Изучает уровень одному из двоих: ход объявляют оба, иначе он не считается."""
    call('POST', f'/api/games/{duo_id}/research',
         {'accessToken': token, 'categoryCode': category,
          'levelOrder': order, 'optionCode': option})
    for _ in range(limit):
        # Через `.get`, а не по ключу: партия может кончиться прямо посреди ожидания
        # (победа покорением у соседа), и тогда сервер честно отвечает отказом. Прежде
        # этот отказ ронял ВЕСЬ прогон `KeyError`ом на 2790-й строке, не назвав причины
        # и не доиграв две с половиной тысячи проверок.
        state = call_auth('GET', f'/api/games/{duo_id}/research', token)
        got = state.get('acquired')
        if got is None:
            print('   партия двоих оборвалась: %s' % str(state)[:120], flush=True)
            return False
        if any(a['categoryCode'] == category and a['levelOrder'] == order for a in got):
            return True
        end_turn(duo_id, token, other)
    return False


# Разные ветки дерева: тогда каждому есть что предложить другому. Берутся уровни ВЫШЕ
# стартовых (п. 9): Power, Chemistry и Physics первого уровня есть у обоих с первого
# хода, и обмен ими пуст — на этом проверка и попалась, показав «отдать 0».
duo_research(tok_one, tok_two, 'engineering', 1, 'reinforced-hull')
duo_research(tok_two, tok_one, 'computers', 1, 'electronic-computer')
duo_research(tok_two, tok_one, 'biology', 1, 'biospheres')

# Знакомимся перелётом второго к первому.
duo_map = call('GET', f'/api/games/{duo_id}/map?accessToken={tok_two}&revealAll=true')
duo_home_one = [st for st in duo_map['systems']
                if any(p.get('ownerPlayerId') == pid_one for p in st['planets'])][0]
duo_home_two = [p for st in duo_map['systems'] for p in st['planets']
                if p.get('homeworld') and p.get('ownerPlayerId') == pid_two][0]
build_ship(duo_id, tok_two, duo_home_two['id'], tok_one)
# Дорогу к соседу прокладывают заставы, и сколько их понадобится, зависит от карты:
# ходов даём с запасом, а получилось ли — говорим в сообщении. Без этого провал на
# неудачной галактике читался как поломка знакомства.
duo_reached = reach_towards(duo_id, tok_two, duo_home_two['id'], duo_home_one['id'],
                            tok_one, hops=8)
# Потерянный по дороге корабль заменяется новым: в живой галактике курьер смертен, и без
# этого проверка знакомства плавала от прогона к прогону.
for duo_try in range(3):
    if fly_to(duo_id, tok_two, duo_home_one['id'], tok_one, limit=60) is not None:
        break
    build_ship(duo_id, tok_two, duo_home_two['id'], tok_one)

duo_known = [r for r in call_auth('GET', f'/api/games/{duo_id}/diplomacy', tok_one)
             if r['playerId'] == pid_two]
check('две империи людей познакомились — п. 15', len(duo_known) == 1,
      str(duo_known) if duo_reached else 'до соседа не дотянулись заставами: чужой мир вне дальности')

if duo_known:
    trade = call_auth('GET', f'/api/games/{duo_id}/diplomacy/{pid_two}/technologies', tok_one)
    check('список обмена показывает обе половины сделки — п. 15',
          len(trade['offer']) > 0 and len(trade['wanted']) > 0
          and all(t['value'] > 0 for t in trade['offer'] + trade['wanted']),
          f"отдать {len(trade['offer'])}, просить {len(trade['wanted'])}")
    # В списках только то, чего у другой стороны нет: общие технологии в обмене бессмысленны.
    mine_codes = {a['optionCode'] for a in call_auth('GET', f'/api/games/{duo_id}/research', tok_one)['acquired']}
    check('в списке обмена нет того, что уже есть у обоих — п. 15',
          all(t['code'] not in mine_codes for t in trade['wanted']),
          str([t['code'] for t in trade['wanted']][:5]))

    fair = next(((g, t) for g in trade['offer'] for t in trade['wanted']
                 if g['value'] >= t['value']), None)
    if fair:
        give, take = fair
        swap = call('POST', f'/api/games/{duo_id}/diplomacy',
                    {'accessToken': tok_one, 'targetPlayerId': pid_two,
                     'action': 'EXCHANGE_TECH',
                     'offeredTech': give['code'], 'requestedTech': take['code']})
        got_mine = {a['optionCode'] for a in call_auth('GET', f'/api/games/{duo_id}/research', tok_one)['acquired']}
        got_theirs = {a['optionCode'] for a in call_auth('GET', f'/api/games/{duo_id}/research', tok_two)['acquired']}
        check('равноценный обмен состоялся и технологии ушли в обе стороны — п. 15',
              take['code'] in got_mine and give['code'] in got_theirs,
              str(swap.get('answer')))

    # Невыгодный обмен: второй берёт дорогой уровень, первый просит его за дешёвый.
    duo_research(tok_two, tok_one, 'chemistry', 2, 'deuterium-fuel-cells')
    trade = call_auth('GET', f'/api/games/{duo_id}/diplomacy/{pid_two}/technologies', tok_one)
    unfair = next(((g, t) for g in trade['offer'] for t in trade['wanted']
                   if g['value'] < t['value']), None)
    if unfair:
        give, take = unfair
        refused = call('POST', f'/api/games/{duo_id}/diplomacy',
                       {'accessToken': tok_one, 'targetPlayerId': pid_two,
                        'action': 'EXCHANGE_TECH',
                        'offeredTech': give['code'], 'requestedTech': take['code']})
        after = {a['optionCode'] for a in call_auth('GET', f'/api/games/{duo_id}/research', tok_one)['acquired']}
        # Правило MOO II: соглашаются только на выгодную себе сделку.
        check('дешёвое за дорогое не меняют — п. 15',
              str(refused.get('answer', '')).startswith('Отказ') and take['code'] not in after,
              f"{give['value']} ОИ за {take['value']} ОИ: {refused.get('answer')}")

    # Подарок деньгами: казна убывает, чужое доверие растёт — и это видно на экране.
    trust_before = [r for r in call_auth('GET', f'/api/games/{duo_id}/diplomacy', tok_one)
                    if r['playerId'] == pid_two][0]['trust']
    purse = [p for p in call('GET', f'/api/games/{duo_id}')['players']
             if p['id'] == pid_one][0]['credits']
    gift = call('POST', f'/api/games/{duo_id}/diplomacy',
                {'accessToken': tok_one, 'targetPlayerId': pid_two,
                 'action': 'GIFT_CREDITS', 'credits': max(1, purse // 2)})
    purse_after = [p for p in call('GET', f'/api/games/{duo_id}')['players']
                   if p['id'] == pid_one][0]['credits']
    check('подарок деньгами уходит из казны и поднимает доверие — п. 15',
          purse_after < purse and gift.get('trust', 0) > trust_before,
          f"казна {purse} -> {purse_after}, доверие {trust_before} -> {gift.get('trust')}")

    # Подарок технологией: согласия не требует, и подаренное уходит из списка предложений.
    trade = call_auth('GET', f'/api/games/{duo_id}/diplomacy/{pid_two}/technologies', tok_one)
    if trade['offer']:
        present = trade['offer'][0]
        call('POST', f'/api/games/{duo_id}/diplomacy',
             {'accessToken': tok_one, 'targetPlayerId': pid_two,
              'action': 'GIFT_TECH', 'offeredTech': present['code']})
        theirs = {a['optionCode'] for a in call_auth('GET', f'/api/games/{duo_id}/research', tok_two)['acquired']}
        left = call_auth('GET', f'/api/games/{duo_id}/diplomacy/{pid_two}/technologies', tok_one)
        check('подаренная технология доходит и уходит из списка предложений — п. 15',
              present['code'] in theirs
              and present['code'] not in {t['code'] for t in left['offer']},
              present['name'])

call('DELETE', f'/api/games/{duo_id}?accessToken={tok_one}')

# Корабль для проверок сохранения и списания строится ЗАНОВО, а не берётся тот разведчик,
# что улетал к соседу: он стоит в чужой системе, и с тех пор как ИИ стал воевать всерьёз,
# его там убивают. Ни сохранение, ни списание к войне отношения не имеют, и ставить их в
# зависимость от военной удачи незачем — прогон падал на пустом списке флотов.
before_scrap = {one['id'] for one in call_auth('GET', f'/api/games/{shadow_id}/fleets', shadow_tok)}
scrap_fleets = [one for one in build_ship(shadow_id, shadow_tok, home['id'])
                if one['id'] not in before_scrap]
check('корабль под сохранение и списание построен — п. 8', bool(scrap_fleets),
      str(len(scrap_fleets)))

# Разведка и шпионы переживают сохранение партии.
saved_fleets = call_auth('GET', f'/api/games/{shadow_id}/fleets', shadow_tok)
spy_save = call('POST', f'/api/games/{shadow_id}/save', {'accessToken': shadow_tok})
spy_loaded = call('POST', f"/api/saves/{spy_save['id']}/load")
loaded_id = spy_loaded['game']['id']
loaded_tok = spy_loaded['credentials']['accessToken']
saved_spy = call_auth('GET', f'/api/games/{shadow_id}/espionage', shadow_tok)
loaded_spy = call('GET', f'/api/games/{loaded_id}/espionage?accessToken={loaded_tok}')
check('загрузка: очки разведки на месте — п. 13',
      loaded_spy['points'] == saved_spy['points'],
      f"{saved_spy['points']} -> {loaded_spy['points']}")
loaded_fleets = call_auth('GET', f'/api/games/{loaded_id}/fleets', loaded_tok)
# Сверяется со слепком ИСХОДНОЙ партии, а не с жёсткой единицей: флотов у игрока может быть
# и больше, и меньше — война их убавляет, — а проверяется здесь то, что загрузка ничего не
# теряет.
check('загрузка: флот на месте — п. 8',
      len(loaded_fleets) == len(saved_fleets)
      and all(one['ships'] >= 1 for one in loaded_fleets),
      f'было {len(saved_fleets)}, стало {len(loaded_fleets)}')

# Списание кораблей — п. 8: экран флота и есть то место, откуда корабль убирают из строя.
#
# Корабль под списание строится ЗАНОВО, а не берётся тот разведчик, что улетал к соседу:
# он стоит в чужой системе, и с тех пор как ИИ стал воевать всерьёз, его там убивают — а
# проверка падала на пустом списке флотов, не дойдя до конца прогона. Списание к войне
# отношения не имеет, и ставить его в зависимость от военной удачи незачем.
scout = scrap_fleets[0]
scrap_one = call('POST', f"/api/games/{shadow_id}/fleets/{scout['id']}/scrap",
                 {'accessToken': shadow_tok,
                  'ships': [{'designId': scout['composition'][0]['designId'],
                             'ships': scout['composition'][0]['ships']}]})
# Сверяется исчезновение ИМЕННО этого флота, а не пустота списка: у империи, которая
# строит корабли с первого хода (п. 9, стартовые технологии), рядом стоят и другие.
check('списание убирает корабли из строя — п. 8',
      all(one['id'] != scout['id'] for one in scrap_one),
      str([one['id'] for one in scrap_one]))
check('флот без кораблей не остаётся на карте — п. 8',
      all(one['id'] != scout['id']
          for one in call_auth('GET', f'/api/games/{shadow_id}/fleets', shadow_tok)))
gone = call('POST', f"/api/games/{shadow_id}/fleets/{scout['id']}/scrap",
            {'accessToken': shadow_tok,
             'ships': [{'designId': scout['composition'][0]['designId'], 'ships': 1}]})
check('списанный флот больше не найти — п. 8', gone.get('ERROR') == 404, str(gone))

call('DELETE', f'/api/games/{shadow_id}?accessToken={shadow_tok}')
call('DELETE', f'/api/games/{loaded_id}?accessToken={loaded_tok}')
call('DELETE', f"/api/saves/{spy_save['id']}")

# --- 13. Операции шпионов и договоры (п. 13, 15) ---
# Два живых игрока в одной партии: у ИИ нечего красть — он не исследует.
# Зерно постоянное: раздел держится на знакомстве двух империй, а знакомятся они по
# дальности — то есть по тому, куда галактика поставила их родные звёзды. Без зерна
# прогон то проходил, то валил полтора десятка проверок разом. У 1010 родные миры стоят
# в одиннадцати парсеках — заставами такое расстояние проходится.
# ПАРТИЯ РОВНО НА ДВОИХ: сценарий про двух людей, и живой сосед ему не нужен. Добор ИИ
# попадал сюда молча, а с тех пор как ИИ обзавёлся разведкой и водит флоты сам, его
# корабли перехватывали одинокий корабль-курьер, а то и отнимали родной мир —
# проверки валились ЧЕРЕЗ РАЗ, не отличая поломку от невезения. Двое участников
# стали возможны после того, как из игры убрали победу на выборах Высшего совета:
# прежде партия из двух империй обрывалась голосованием на двадцать пятом ходу.
duel = call('POST', '/api/games', {'name': 'Регресс шпионы', 'playerName': 'Вор',
                                   'galaxySize': 'SMALL', 'raceTraits': ['spy-great'],
                                   'seed': 1010, 'totalPlayers': 2})
duel_id = duel['game']['id']
thief_tok, thief_pid = duel['credentials']['accessToken'], duel['credentials']['playerId']
victim = call('POST', f'/api/games/{duel_id}/join', {'playerName': 'Жертва',
                                                     'raceTraits': ['science-great']})
victim_tok, victim_pid = victim['credentials']['accessToken'], victim['credentials']['playerId']
call('POST', f'/api/games/{duel_id}/start', {'accessToken': thief_tok})

duel_map = call('GET', f'/api/games/{duel_id}/map?accessToken={thief_tok}&revealAll=true')
thief_home = [x for st in duel_map['systems'] for x in st['planets']
              if x.get('homeworld') and x.get('ownerPlayerId') == thief_pid][0]
victim_system = [st for st in duel_map['systems']
                 if any(pl.get('homeworld') and pl.get('ownerPlayerId') == victim_pid
                        for pl in st['planets'])][0]

# Жертва учится, вор растит агентов: двое — на вылазку и на операцию.
#
# Учится жертва тому, чего у вора заведомо нет: три общих уровня начала дерева (Power,
# Chemistry, Physics) есть у всех с первого хода, и красть в них нечего. Space Academy
# из социологии не начинает партию ни у кого — вот её агент и вынесет.
call('POST', f'/api/games/{duel_id}/research',
     {'accessToken': victim_tok, 'categoryCode': 'sociology', 'levelOrder': 1,
      'optionCode': 'space-academy'})
call('POST', f"/api/games/{duel_id}/planets/{thief_home['id']}/project",
     {'accessToken': thief_tok, 'projectCode': 'SPY'})

for _ in range(40):
    end_turn(duel_id, thief_tok, victim_tok)
    spies = call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')
    home_now = [x for st in call('GET', f'/api/games/{duel_id}/map?accessToken={thief_tok}')['systems']
                for x in st['planets'] if x['id'] == thief_home['id']][0]
    # «Колония освободилась» — это товары, а не пустая стройка: пустой стройки у
    # колонии больше не бывает (п. 10), и прежняя проверка на пусто не срабатывала
    # никогда — второй шпион не строился, а раздел валился на слабой контрразведке.
    if spies['spies'] < 2 and home_now['colony'].get('projectCode') in (None, 'TRADE_GOODS'):
        call('POST', f"/api/games/{duel_id}/planets/{thief_home['id']}/project",
             {'accessToken': thief_tok, 'projectCode': 'SPY'})
    victim_research = call('GET', f'/api/games/{duel_id}/research?accessToken={victim_tok}')
    victim_knows = [t['optionCode'] for t in victim_research['acquired']]
    if spies['spies'] >= 2 and 'space-academy' in victim_knows:
        break

spies = call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')
check('агенты империи перечислены поимённо — п. 13',
      len(spies['agents']) == spies['spies'] and all(a['mission'] == 'HOME' for a in spies['agents']),
      f"агентов {len(spies['agents'])}")

# Знакомство и отправка агента к сопернику. Дорогу к нему прокладывают заставы: дальше
# своей дальности флот не летает, а дальность растёт своими мирами — п. 8.
duel_fleet = build_ship(duel_id, thief_tok, thief_home['id'], victim_tok)
if duel_fleet:
    reach_towards(duel_id, thief_tok, thief_home['id'], victim_system['id'], victim_tok)
    # Корабль вора тоже смертен: гибнет — строим нового и летим снова.
    for duel_try in range(3):
        if fly_to(duel_id, thief_tok, victim_system['id'], victim_tok, limit=60) is not None:
            break
        build_ship(duel_id, thief_tok, thief_home['id'], victim_tok)
spies = call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')
unknown_target = call('POST', f'/api/games/{duel_id}/spies',
                      {'accessToken': thief_tok, 'spyId': spies['agents'][0]['id'],
                       'mission': 'STEAL_TECH', 'targetPlayerId': thief_pid})
check('шпионить за собой нельзя — п. 13', unknown_target.get('ERROR') == 409)

assigned = call('POST', f'/api/games/{duel_id}/spies',
                {'accessToken': thief_tok, 'spyId': spies['agents'][0]['id'],
                 'mission': 'STEAL_TECH', 'targetPlayerId': victim_pid})
# Разбирается через get: отказ приходит телом ошибки, и обращение по ключу роняло бы
# весь прогон вместо одной проверки — знакомство с жертвой зависит от галактики.
check('агент отправлен к сопернику — п. 13',
      assigned.get('mission') == 'STEAL_TECH' and assigned.get('targetPlayerId') == victim_pid,
      str(assigned.get('missionLabel', assigned.get('body', {}).get('message'))))
check('отправленный агент не пополняет запас империи — п. 13',
      call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')['pointsPerTurn']
      == spies['pointsPerTurn'] - 1)

def duel_relation(token, other_pid, field):
    """Доверие в паре дуэли, или None, если стороны ещё не знакомы.

    Знакомство зависит от сгенерированной галактики: до соперника надо долететь, а
    заставы прокладывают дорогу не в каждой карте. Раньше отсутствие строки роняло
    весь прогон обращением к пустому списку — теперь проверка просто не проходит и
    называет причину, как соседняя проверка с отправкой агента.
    """
    rows = [r for r in call('GET', f'/api/games/{duel_id}/diplomacy?accessToken={token}')
            if r['playerId'] == other_pid]
    return rows[0][field] if rows else None


# Своё изученное у вора уже есть (базовые уровни Power и Chemistry — без них он не
# построил бы корабля), поэтому кражу видно по приросту списка, а не по его непустоте.
known_before = len(call('GET', f'/api/games/{duel_id}/research?accessToken={thief_tok}')['acquired'])
# Доверие снимается прямо перед тем ходом, на котором случилась кража, и сравнивается
# с ним же: за пятнадцать ходов доверие двигает и случайное событие галактики (п. 11.1),
# и тогда «стало больше, чем в начале» говорит не о краже, а о событии. Поле оба раза
# одно — своё доверие жертвы (`yourTrust`): чужое кража не трогает вовсе.
trust_before = duel_relation(victim_tok, thief_pid, 'yourTrust')
for _ in range(15):
    trust_before = duel_relation(victim_tok, thief_pid, 'yourTrust')
    end_turn(duel_id, thief_tok, victim_tok)
    if len(call('GET', f'/api/games/{duel_id}/research?accessToken={thief_tok}')['acquired']) > known_before:
        break
stolen = call('GET', f'/api/games/{duel_id}/research?accessToken={thief_tok}')['acquired']
check('шпион украл технологию — п. 13', len(stolen) > known_before,
      str([t['name'] for t in stolen]))

# СЦЕНА УКРАДЕННОЙ ТЕХНОЛОГИИ СОБИРАЕТСЯ ПО КЛЮЧУ И КОДУ, а не по показанному тексту
# (п. 13): текст меняется с языком, а ключ один на оба. Если событие перестанет нести
# ключ или ссылку на справочник, сцена молча перестанет открываться — заметить это
# можно будет только глазами, поэтому проверяется здесь.
theft_report = call('GET', f'/api/games/{duel_id}/turn/report?accessToken={thief_tok}') or {}
theft_events = [one for one in theft_report.get('events', [])
                if one.get('key') == 'turn.espionage.stole']
check('событие кражи несёт ключ и код технологии — по ним строится сцена (п. 13)',
      bool(theft_events)
      and any(str(arg).startswith('catalog.tech.')
              for arg in (theft_events[0].get('args') or [])),
      str(theft_events[:1]))
trust_after = duel_relation(victim_tok, thief_pid, 'yourTrust')
check('кража бьёт по доверию пострадавшего — п. 13',
      trust_before is not None and trust_after is not None and trust_after < trust_before,
      f'{trust_before} -> {trust_after}' if trust_before is not None
      else 'вор и жертва не познакомились: до соперника не долетели')

# Договоры: жертва предлагает вору, у того доверие не испорчено.
def diplomacy(token, target, action, treaty=None):
    body = {'accessToken': token, 'targetPlayerId': target, 'action': action}
    if treaty:
        body['treaty'] = treaty
    return call('POST', f'/api/games/{duel_id}/diplomacy', body)


def victim_home():
    return [x for st in call('GET', f'/api/games/{duel_id}/map?accessToken={victim_tok}')['systems']
            for x in st['planets']
            if x.get('homeworld') and x.get('ownerPlayerId') == victim_pid][0]


income_before = victim_home()['colony']['income']
research_before = victim_home()['colony']['researchPerScientist']

def treaties(answer):
    """Договоры из ответа дипломатии; пусто — отказ (например, стороны не знакомы).

    Знакомство в дуэли зависит от карты, и отказ приходит телом ошибки: обращение по
    ключу роняло бы весь прогон вместо одной проверки.
    """
    return answer.get('treaties') or []


trade = diplomacy(victim_tok, thief_pid, 'PROPOSE_TREATY', 'TRADE')
check('торговый договор подписан — п. 15', 'TRADE' in treaties(trade),
      str(trade.get('answer', trade.get('body', {}).get('message'))))
check('торговый договор поднимает доход колоний — п. 15',
      victim_home()['colony']['income'] > income_before,
      f"{income_before} -> {victim_home()['colony']['income']}")

science = diplomacy(victim_tok, thief_pid, 'PROPOSE_TREATY', 'RESEARCH')
check('исследовательский договор подписан — п. 15', 'RESEARCH' in treaties(science))
check('исследовательский договор поднимает науку — п. 15',
      victim_home()['colony']['researchPerScientist'] == research_before + 1,
      f"{research_before} -> {victim_home()['colony']['researchPerScientist']}")

pact = diplomacy(victim_tok, thief_pid, 'PROPOSE_TREATY', 'NON_AGGRESSION')
check('пакт о ненападении подписан — п. 15', 'NON_AGGRESSION' in treaties(pact))
war_blocked = diplomacy(victim_tok, thief_pid, 'DECLARE_WAR')
check('пока договоры в силе, война невозможна — п. 15', war_blocked.get('ERROR') == 409,
      str(war_blocked.get('body', {}).get('message')))

def explored_names(token):
    return {st['name'] for st in call('GET', f'/api/games/{duel_id}/map?accessToken={token}')['systems']
            if st['explored']}


def home_system_name(player_id):
    return [st['name'] for st in call('GET', f'/api/games/{duel_id}/map?accessToken={victim_tok}'
                                             f'&revealAll=true')['systems']
            if any(pl.get('ownerPlayerId') == player_id and pl.get('homeworld')
                   for pl in st['planets'])][0]


# Союзник показывает свои владения — п. 15. Проверяются именно они, а не совпадение
# списков целиком: разведка союзника не передаётся дальше по цепочке, а союзники бывают и
# другие (империи ИИ предлагают союз сами), так что видят стороны разное.
victim_home_system, thief_home_system = home_system_name(victim_pid), home_system_name(thief_pid)
alliance = diplomacy(victim_tok, thief_pid, 'PROPOSE_TREATY', 'ALLIANCE')
check('союз подписан — п. 15', 'ALLIANCE' in treaties(alliance), str(alliance.get('answer')))
explored_victim, explored_thief = explored_names(victim_tok), explored_names(thief_tok)
check('союзники делятся разведкой — п. 15',
      thief_home_system in explored_victim and victim_home_system in explored_thief,
      f'родная система вора {thief_home_system} у жертвы: '
      f'{thief_home_system in explored_victim}; жертвы {victim_home_system} у вора: '
      f'{victim_home_system in explored_thief}')

tribute = diplomacy(victim_tok, thief_pid, 'DEMAND_TRIBUTE')
check('дань платят по большой приязни — п. 15',
      str(tribute.get('answer', '')).startswith('Дань получена'), str(tribute.get('answer')))

broken = diplomacy(victim_tok, thief_pid, 'BREAK_TREATY', 'ALLIANCE')
check('разрыв союза уносит и ненападение — п. 15',
      'ALLIANCE' not in treaties(broken) and 'NON_AGGRESSION' not in treaties(broken),
      str(broken.get('treatyLabels', broken)))

# Контрразведка: агенты жертвы ловят чужих — п. 13.
victim_spies = call('GET', f'/api/games/{duel_id}/espionage?accessToken={victim_tok}')
if victim_spies['spies'] == 0:
    victim_home_planet = victim_home()
    call('POST', f"/api/games/{duel_id}/planets/{victim_home_planet['id']}/project",
         {'accessToken': victim_tok, 'projectCode': 'SPY'})
    for _ in range(20):
        end_turn(duel_id, thief_tok, victim_tok)
        victim_spies = call('GET', f'/api/games/{duel_id}/espionage?accessToken={victim_tok}')
        if victim_spies['spies'] > 0:
            break

check('у жертвы есть агент для обороны — п. 13', victim_spies['spies'] > 0,
      f"агентов {victim_spies['spies']}")
counter = call('POST', f'/api/games/{duel_id}/spies',
               {'accessToken': victim_tok, 'spyId': victim_spies['agents'][0]['id'],
                'mission': 'COUNTER'})
check('агента можно поставить на контрразведку — п. 13', counter['mission'] == 'COUNTER',
      str(counter.get('missionLabel')))
defence = call('GET', f'/api/games/{duel_id}/espionage?accessToken={victim_tok}')
check('контрразведка отнимает очки у чужих агентов — п. 13', defence['counterStrength'] > 0,
      f"сила {defence['counterStrength']}")
check('контрразведчик не пополняет запас империи — п. 13',
      defence['pointsPerTurn'] < victim_spies['pointsPerTurn'],
      f"{victim_spies['pointsPerTurn']} -> {defence['pointsPerTurn']}")

# Поймать агента можно, только когда контрразведка отнимает больше, чем он набирает
# за ход: вор здесь из расы великих шпионов и набирает три очка, поэтому обороне нужно
# четверо агентов. Пока их меньше — операция лишь замедляется, и это тоже проверяется.
thief_gain = 1 + call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')['racePoints']
for _ in range(40):
    defence = call('GET', f'/api/games/{duel_id}/espionage?accessToken={victim_tok}')
    # Ответ-отказ вместо разведки значит, что партия сложилась не так, как задумано
    # (жертва потеряла колонии, знакомство не случилось). Выходим и даём проверкам ниже
    # провалиться по одной со своими сообщениями — прогон обязан доходить до конца.
    if 'counterStrength' not in defence:
        break
    if defence['counterStrength'] > thief_gain:
        break
    for agent in defence.get('agents', []):
        if agent['mission'] != 'COUNTER':
            call('POST', f'/api/games/{duel_id}/spies',
                 {'accessToken': victim_tok, 'spyId': agent['id'], 'mission': 'COUNTER'})
    home_now = victim_home()
    # Освободившаяся колония строит товары — их и сменяем на шпиона (п. 10).
    if home_now['colony'].get('projectCode') in (None, 'TRADE_GOODS'):
        call('POST', f"/api/games/{duel_id}/planets/{home_now['id']}/project",
             {'accessToken': victim_tok, 'projectCode': 'SPY'})
    end_turn(duel_id, thief_tok, victim_tok)

defence = call('GET', f'/api/games/{duel_id}/espionage?accessToken={victim_tok}')
check('оборона набрала контрразведку сильнее чужого агента — п. 13',
      defence['counterStrength'] > thief_gain,
      f"сила {defence['counterStrength']} против {thief_gain} очков за ход")

# Переназначение обнуляет накопленное, поэтому агент встречает контрразведку с нуля.
thief_state = call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')
if thief_state['agents']:
    call('POST', f'/api/games/{duel_id}/spies',
         {'accessToken': thief_tok, 'spyId': thief_state['agents'][0]['id'],
          'mission': 'SABOTAGE', 'targetPlayerId': victim_pid})
    thief_spies_before = thief_state['spies']
    # Доверие спрашиваем помощником, а не выборкой с `[0]`: знакомство держится на
    # дальности и разведке (п. 15), и в иной галактике этих двоих ещё не познакомили —
    # список отношений пуст, и прогон падал целиком вместо провала одной проверки.
    trust_before_catch = duel_relation(victim_tok, thief_pid, 'yourTrust')
    end_turn(duel_id, thief_tok, victim_tok)
    thief_spies_after = call('GET', f'/api/games/{duel_id}/espionage?accessToken={thief_tok}')['spies']
    trust_after_catch = duel_relation(victim_tok, thief_pid, 'yourTrust')
    check('контрразведка ловит чужого агента — п. 13', thief_spies_after < thief_spies_before,
          f'{thief_spies_before} -> {thief_spies_after}')
    check('поимку помнят: доверие падает — п. 13',
          trust_before_catch is not None and trust_after_catch is not None
          and trust_after_catch < trust_before_catch,
          f'{trust_before_catch} -> {trust_after_catch}')

# --- 16. Ход по игрокам, отчёт и подписка на события (п. 11.1) ---
# Партия на двоих: пока не закончили оба, ход не считается.
turn_before = call('GET', f'/api/games/{duel_id}')['game']['turn']
first = call('POST', f'/api/games/{duel_id}/turn/end', {'accessToken': thief_tok})
check('первый игрок закончил ход, партия ждёт второго — п. 11.1',
      first['advanced'] is False and first['waitingFor'] == [victim_pid],
      f"advanced={first['advanced']}, ждём {len(first['waitingFor'])}")
check('ход не сдвинулся, пока ждут — п. 11.1',
      first['state']['game']['turn'] == turn_before,
      f"{turn_before} -> {first['state']['game']['turn']}")
check('признак конца хода виден в составе партии — п. 11.1',
      [pl['turnEnded'] for pl in first['state']['players'] if pl['id'] == thief_pid] == [True]
      and [pl['turnEnded'] for pl in first['state']['players'] if pl['id'] == victim_pid] == [False])

again = call('POST', f'/api/games/{duel_id}/turn/end', {'accessToken': thief_tok})
check('повторный конец хода тем же игроком ничего не двигает — п. 11.1',
      again['advanced'] is False and again['state']['game']['turn'] == turn_before)

second = call('POST', f'/api/games/{duel_id}/turn/end', {'accessToken': victim_tok})
check('закончили все — ход посчитан — п. 11.1',
      second['advanced'] is True and second['state']['game']['turn'] == turn_before + 1,
      f"{turn_before} -> {second['state']['game']['turn']}")
check('в ответе есть отчёт хода — п. 11.1',
      second['report'] is not None and second['report']['turn'] == turn_before,
      str(second['report'] and second['report']['turn']))

# Отчёт лежит в базе: игрок, закончивший первым, узнаёт о пересчёте не из своего ответа.
stored = call_auth('GET', f'/api/games/{duel_id}/turn/report', thief_tok)
check('отчёт хода доступен и после перезагрузки страницы — п. 11.1',
      stored is not None and stored['turn'] == turn_before,
      str(stored and stored['turn']))
# Планету помечает изменившейся фаза роста, и только когда прирост не нулевой
# (`PopulationPhase`). У колонии, упёршейся в свою вместимость, прирост ноль — и список
# пуст по делу, а не по поломке: проверка это и различает. Без оговорки она падала на
# каждом третьем прогоне, когда родной мир успевал набрать полную вместимость.
mine_now = [pl for st in call_auth('GET', f'/api/games/{duel_id}/map', thief_tok)['systems']
            for pl in st['planets'] if pl.get('ownerPlayerId') == thief_pid]
growing = [pl for pl in mine_now
           if (pl.get('population') or 0) < (pl.get('colony') or {}).get('maxPopulation', 0)]
check('отчёт называет изменившиеся планеты — п. 11.1',
      len(stored['changedPlanetIds']) > 0 or not growing,
      f"планет {len(stored['changedPlanetIds'])}, растущих колоний {len(growing)}")

# Пропуск заголовком вместо адреса — п. 10 замечаний по нагрузке.
by_header = call_auth('GET', f'/api/games/{duel_id}/map', thief_tok)
check('пропуск принимается заголовком X-Access-Token', 'systems' in by_header,
      str(list(by_header)[:2]))
no_token = call('GET', f'/api/games/{duel_id}/map')
check('без пропуска карта не отдаётся', no_token.get('ERROR') in (400, 403))

# --- 17. Сохранения уходят вместе с партией ---
duel_save = call('POST', f'/api/games/{duel_id}/save', {'accessToken': thief_tok})
check('партию можно сохранить', duel_save.get('id') is not None)
call('DELETE', f'/api/games/{duel_id}?accessToken={thief_tok}')
check('сохранение удалённой партии не осталось в базе',
      not any(x['id'] == duel_save['id'] for x in call('GET', '/api/saves')))

# --- 18. Флоты, встречи и итоги хода (п. 8, п. 11.1) ---
# Зерно постоянное: раздел держится на том, что корабль долетит до соседа, а долетит
# он или нет — дело сгенерированной галактики. Без зерна раздел то проходил, то валил
# восемь проверок разом. У 2010 родные звёзды стоят в двенадцати парсеках.
navy = call('POST', '/api/games', {'name': 'Флоты', 'playerName': 'Адмирал',
                                   'homeStarName': 'Флагман', 'galaxySize': 'SMALL',
                                   'seed': 2010})
navy_id, navy_tok, navy_pid = (navy['game']['id'], navy['credentials']['accessToken'],
                               navy['credentials']['playerId'])
foe = call('POST', f'/api/games/{navy_id}/join', {'playerName': 'Сосед', 'homeStarName': 'Соседняя'})
foe_tok, foe_pid = foe['credentials']['accessToken'], foe['credentials']['playerId']
call('POST', f'/api/games/{navy_id}/start', {'accessToken': navy_tok})

navy_map = call('GET', f'/api/games/{navy_id}/map?accessToken={navy_tok}&revealAll=true')


def navy_home(pid):
    for st in navy_map['systems']:
        for pl in st['planets']:
            if pl.get('ownerPlayerId') == pid:
                return st, pl
    return None, None


navy_sys, navy_planet = navy_home(navy_pid)
foe_sys, foe_planet = navy_home(foe_pid)

# Корабль в стройке стоит с ПЕРВОГО хода: двигатель и топливо выданы стартовыми
# технологиями (п. 9). Раньше здесь проверялось обратное — что до этих уровней корабля
# нет, — и проверка проходила ровно потому, что стартовые технологии не выдавались
# никому: империя ИИ, чьё устремление не любит химию, так и не строила ни одного корабля
# за партию (зерно 777: шесть перелётов и ноль войн на восемь империй за 150 ходов).
check('корабль в стройке есть с первого хода — п. 8, п. 9',
      any(x['code'].startswith('SHIP:') for x in navy_planet['colony']['available']),
      str([x['code'] for x in navy_planet['colony']['available']]))

# Двигатель и топливо: обе империи учат базовые уровни Power и Chemistry — п. 8.
research_shipbuilding(navy_id, navy_tok, foe_tok)
research_shipbuilding(navy_id, foe_tok, navy_tok)
navy_map = call('GET', f'/api/games/{navy_id}/map?accessToken={navy_tok}&revealAll=true')
navy_sys, navy_planet = navy_home(navy_pid)
foe_sys, foe_planet = navy_home(foe_pid)

check('корабль есть в списке стройки — п. 8',
      any(x['code'].startswith('SHIP:') and x['name'].startswith('Корабль: ')
          for x in navy_planet['colony']['available']),
      str([x['code'] for x in navy_planet['colony']['available']]))

navy_project = ship_project(navy_id, navy_tok, navy_planet['id'])
foe_project = ship_project(navy_id, foe_tok, foe_planet['id'])
for token, planet, project in ((navy_tok, navy_planet, navy_project),
                               (foe_tok, foe_planet, foe_project)):
    call('POST', f"/api/games/{navy_id}/planets/{planet['id']}/project",
         {'accessToken': token, 'projectCode': project})

navy_fleet, foe_fleet = [], []
for _ in range(30):
    end_turn(navy_id, navy_tok, foe_tok)
    navy_fleet = call_auth('GET', f'/api/games/{navy_id}/fleets', navy_tok)
    foe_fleet = call_auth('GET', f'/api/games/{navy_id}/fleets', foe_tok)
    if navy_fleet and foe_fleet:
        break
    for token, planet, project in ((navy_tok, navy_planet, navy_project),
                                   (foe_tok, foe_planet, foe_project)):
        call('POST', f"/api/games/{navy_id}/planets/{planet['id']}/project",
             {'accessToken': token, 'projectCode': project})

check('колония построила корабль, и он встал во флот системы — п. 8',
      len(navy_fleet) == 1 and navy_fleet[0]['ships'] >= 1
      and navy_fleet[0]['starSystemId'] == navy_sys['id'],
      str(navy_fleet))
check('инициатива флота считается по силе кораблей и их скорости — п. 8',
      navy_fleet[0]['initiative'] == navy_fleet[0]['power'] * navy_fleet[0]['speed'],
      f"сила {navy_fleet[0]['power']}, скорость {navy_fleet[0]['speed']},"
      f" инициатива {navy_fleet[0]['initiative']}")
check('флот знает, из каких проектов он собран — п. 8',
      sum(x['ships'] for x in navy_fleet[0]['composition']) == navy_fleet[0]['ships'],
      str(navy_fleet[0]['composition']))

# Карточка корабля на экране флота (окно Fleet Operations MOO II) снабжается прямо из
# состава: корпус, щит, оружие и особые модули. Списком проектов империи её не наполнить —
# вытесненного проекта там нет, а корабли по нему летают.
navy_ship = navy_fleet[0]['composition'][0]
check('состав флота несёт карточку корабля: корпус и его размер — п. 8',
      navy_ship.get('hullName') and 1 <= navy_ship.get('hullSize', 0) <= 6,
      f"корпус {navy_ship.get('hullName')}, размер {navy_ship.get('hullSize')}")
check('состав флота несёт вооружение и особые модули — п. 8',
      isinstance(navy_ship.get('weapons'), list) and isinstance(navy_ship.get('specials'), list)
      and all(w['count'] > 0 and w['damage'] > 0 for w in navy_ship['weapons']),
      str(navy_ship.get('weapons')))
# По этому же признаку экран флота делит корабли на боевые и вспомогательные: в MOO II
# вспомогательный корабль — тот, что не несёт оружия и в бой не входит вовсе.
check('корабль без оружия — вспомогательный, с оружием — боевой — п. 8',
      bool(navy_ship['weapons']) == (navy_ship['attack'] > 0),
      f"оружия {len(navy_ship['weapons'])}, залп {navy_ship['attack']}")

# Сканеров у империи ещё нет: чужая система — просто звезда, ни состава, ни присутствия.
dark = [st for st in call_auth('GET', f'/api/games/{navy_id}/map', navy_tok)['systems']
        if st['id'] == foe_sys['id']][0]
check('без сканеров о чужой системе не известно ничего — п. 15',
      dark['explored'] is False and dark['scanned'] is False and dark['occupied'] is False,
      f"разведана {dark['explored']}, сканер {dark['scanned']}, присутствие {dark['occupied']}")

navy_fleet = call_auth('GET', f'/api/games/{navy_id}/fleets', navy_tok)
# До чужой звезды дорогу прокладывают заставы: дальше своей дальности флот не летает,
# а дальность растёт своими мирами — п. 8.
reach_towards(navy_id, navy_tok, navy_planet['id'], foe_sys['id'], foe_tok)
moved = fly_to(navy_id, navy_tok, foe_sys['id'], foe_tok, limit=60)
check('флот летит и к неразведанной звезде: это и есть разведка — п. 15',
      moved is not None and moved['starSystemId'] == foe_sys['id'], str(moved))
check('приход флота открыл чужую систему — п. 15',
      [st for st in call_auth('GET', f'/api/games/{navy_id}/map', navy_tok)['systems']
       if st['id'] == foe_sys['id']][0]['explored'] is True)

# Отдельного хода после прилёта не нужно: встречу заводит та же фаза конца хода, что
# привела флот, — иначе итоги хода со встречей успели бы смениться следующими (п. 8).
# Встреча берётся ИМЕННО С ЭТИМ соперником, а не «единственная в партии». Здесь стояло
# «встреч ровно одна у каждого», и проверка молча опиралась на то, что больше в галактике
# никто не летает. Как только ИИ обзавёлся разведкой и повёл флоты сам, у игрока стало
# появляться по две встречи — со вторым человеком и с забредшим кораблём соседа, — и
# гроздь из шести проверок валилась там, где поломки нет.
mine_all = call_auth('GET', f'/api/games/{navy_id}/encounters', navy_tok)
theirs_all = call_auth('GET', f'/api/games/{navy_id}/encounters', foe_tok)
mine_enc = [e for e in mine_all if e.get('opponentPlayerId') == foe_pid]
theirs_enc = [e for e in theirs_all if e.get('opponentPlayerId') == navy_pid]
check('встреча флотов заведена в конце хода — п. 8', len(mine_enc) == 1 and len(theirs_enc) == 1,
      f"с соперником у первого {len(mine_enc)}, у второго {len(theirs_enc)};"
      f" всего {len(mine_all)} и {len(theirs_all)}")
check('решает тот, чья инициатива выше — п. 8',
      mine_enc and theirs_enc and mine_enc[0]['yourTurn'] != theirs_enc[0]['yourTurn'],
      f"первый {mine_enc and mine_enc[0]['yourTurn']}, второй {theirs_enc and theirs_enc[0]['yourTurn']}")

navy_report = call_auth('GET', f'/api/games/{navy_id}/turn/report', navy_tok)
check('встреча попала в итоги хода — п. 11.1',
      any(e['code'] == 'ENCOUNTER' for e in navy_report['events']),
      str([e['code'] for e in navy_report['events']]))
check('знакомство с расой попало в итоги хода — п. 11.1',
      any(e['code'] == 'DIPLOMACY' for e in navy_report['events']),
      str([e['code'] for e in navy_report['events']]))

# Первым решает тот, у кого yourTurn: он расходится, очередь переходит второму.
#
# Встречи может и не быть: до чужой звезды флот идёт заставами, а хватит ли их, зависит от
# сгенерированной карты. Тогда берётся заведомо несуществующая встреча — проверки ниже
# честно проваливаются по одной, каждая со своим сообщением, вместо падения всего прогона
# на пустом списке. О самой встрече говорит проверка выше.
# Заглушка непременно из ASCII: она идёт в адрес запроса, а кириллицу в строке
# запроса urllib не кодирует вовсе — прогон падал целиком там, где должен был
# провалить одну проверку.
encounter = mine_enc[0] if mine_enc else {
    'yourTurn': True, 'id': '00000000-0000-0000-0000-000000000000'}
first_tok, second_tok = (navy_tok, foe_tok) if encounter['yourTurn'] else (foe_tok, navy_tok)
encounter_id = encounter['id']
passed = call('POST', f'/api/games/{navy_id}/encounters/{encounter_id}',
              {'accessToken': first_tok, 'decision': 'IGNORE'})
check('разошедшийся первым передаёт выбор второму — п. 8',
      passed.get('state') == 'WAITING_SECOND', str(passed.get('state')))
not_yours = call('POST', f'/api/games/{navy_id}/encounters/{encounter_id}',
                 {'accessToken': first_tok, 'decision': 'ATTACK'})
check('дважды подряд решать нельзя — п. 8', not_yours.get('ERROR') == 409, str(not_yours.get('ERROR')))

# Счёт кораблей снимается прямо перед боем и по всем флотам сразу: между высылкой
# флота и встречей проходит десяток ходов, и колонии обеих сторон успевают достроить
# ещё корабли — сравнение с давним снимком одного флота ловило бы эту стройку, а не бой.
before_navy = sum(f['ships'] for f in call_auth('GET', f'/api/games/{navy_id}/fleets', navy_tok))
before_foe = sum(f['ships'] for f in call_auth('GET', f'/api/games/{navy_id}/fleets', foe_tok))

battle = call('POST', f'/api/games/{navy_id}/encounters/{encounter_id}',
              {'accessToken': second_tok, 'decision': 'ATTACK', 'auto': True})
check('атака закрывает встречу боем — п. 8', battle.get('state') == 'BATTLE', str(battle.get('state')))
check('у боя есть исход — п. 8', bool(battle.get('outcome')), str(battle.get('outcome')))

after_navy = call_auth('GET', f'/api/games/{navy_id}/fleets', navy_tok)
after_foe = call_auth('GET', f'/api/games/{navy_id}/fleets', foe_tok)
check('бой стоил кораблей — п. 8',
      sum(f['ships'] for f in after_navy) + sum(f['ships'] for f in after_foe)
      < before_navy + before_foe,
      f"было {before_navy}+{before_foe}, стало "
      f"{sum(f['ships'] for f in after_navy)}+{sum(f['ships'] for f in after_foe)}")

end_turn(navy_id, navy_tok, foe_tok)
navy_after = call_auth('GET', f'/api/games/{navy_id}/turn/report', navy_tok)
foe_after = call_auth('GET', f'/api/games/{navy_id}/turn/report', foe_tok)
check('бой попал в итоги хода обеих сторон — п. 11.1',
      any(e['code'] == 'BATTLE' for e in navy_after['events'])
      and any(e['code'] == 'BATTLE' for e in foe_after['events']),
      f"{[e['code'] for e in navy_after['events']]} / {[e['code'] for e in foe_after['events']]}")

# Дипломатия соседа тоже попадает в итоги хода — п. 11.1.
call('POST', f'/api/games/{navy_id}/diplomacy',
     {'accessToken': foe_tok, 'targetPlayerId': navy_pid, 'action': 'DECLARE_WAR'})
end_turn(navy_id, navy_tok, foe_tok)
war_report = call_auth('GET', f'/api/games/{navy_id}/turn/report', navy_tok)
check('чужое объявление войны видно в итогах хода — п. 11.1',
      any('войну' in e['text'] for e in war_report['events']),
      str([e['text'] for e in war_report['events']]))

call('DELETE', f'/api/games/{navy_id}?accessToken={navy_tok}')

# --- 19. Сканеры: присутствие без состава (п. 15) ---
scan = call('POST', '/api/games', {'name': 'Сканеры', 'playerName': 'Наблюдатель',
                                   'homeStarName': 'Обсерватория', 'galaxySize': 'SMALL'})
scan_id, scan_tok, scan_pid = (scan['game']['id'], scan['credentials']['accessToken'],
                               scan['credentials']['playerId'])
call('POST', f'/api/games/{scan_id}/start', {'accessToken': scan_tok})


def scan_map(reveal='false'):
    return call_auth('GET', f'/api/games/{scan_id}/map?revealAll={reveal}', scan_tok)


def foreign_systems():
    """Чужие обитаемые системы: их-то сканер и должен заметить."""
    return [st for st in scan_map('true')['systems']
            if any(pl.get('ownerPlayerId') and pl['ownerPlayerId'] != scan_pid
                   for pl in st['planets'])]


# Space Scanner — первая же технология физики, и она есть у империи с первого хода
# (п. 9, стартовые технологии): «партии до сканеров» больше не бывает. Поэтому здесь
# проверяется его ПРЕДЕЛ, а не отсутствие: дальше своей дальности (4 парсека) сканер не
# видит ничего, иначе карта была бы разведкой сильнее всякого флота.
acquired = [t['optionCode'] for t in
            call_auth('GET', f'/api/games/{scan_id}/research', scan_tok)['acquired']]
check('сканер изучен — п. 15', 'space-scanner' in acquired, str(acquired))

before_scan = scan_map()['systems']
scan_home = [st for st in scan_map('true')['systems']
             if any(pl.get('ownerPlayerId') == scan_pid for pl in st['planets'])][0]
far = [st for st in before_scan
       if st['explored'] is False
       and math.hypot(st['x'] - scan_home['x'], st['y'] - scan_home['y']) > 4]
check('дальше сканера ни одна звезда не «просканирована» — п. 15',
      bool(far) and all(st['scanned'] is False for st in far),
      f"вне дальности {len(far)} звёзд, из них помечено "
      f"{len([st for st in far if st['scanned']])}")

after_scan = scan_map()['systems']
scanned = [st for st in after_scan if st['scanned'] and not st['explored']]
check('сканер накрыл соседние звёзды — п. 15', len(scanned) > 0,
      f"в дальности {len(scanned)} из {len(after_scan)}")
# Пустые поля сервер не сериализует вовсе, поэтому названия у неразведанной звезды нет.
check('сканер не раскрывает состав системы — п. 15',
      all(st['planets'] == [] and st.get('name') is None for st in scanned),
      str([st.get('name') for st in scanned][:3]))

occupied = [st for st in after_scan if st['occupied']]
foreign_ids = {st['id'] for st in foreign_systems()}
check('сканер видит чужое присутствие только там, где оно есть — п. 15',
      all(st['id'] in foreign_ids for st in occupied),
      f"помечено занятых {len(occupied)}")
check('занятая система остаётся неразведанной — п. 15',
      all(st['explored'] is False and st['planets'] == [] for st in occupied))

call('DELETE', f'/api/games/{scan_id}?accessToken={scan_tok}')

# --- 20. Грузовой флот: подвоз еды между колониями (п. 4.1.1) ---
cargo = call('POST', '/api/games', {'name': 'Грузовики', 'playerName': 'Снабженец',
                                    'homeStarName': 'Склад', 'galaxySize': 'SMALL',
                                    'seed': 7311})
cargo_id, cargo_tok, cargo_pid = (cargo['game']['id'], cargo['credentials']['accessToken'],
                                  cargo['credentials']['playerId'])
call('POST', f'/api/games/{cargo_id}/start', {'accessToken': cargo_tok})


def cargo_home():
    m = call_auth('GET', f'/api/games/{cargo_id}/map', cargo_tok)
    return [pl for st in m['systems'] for pl in st['planets']
            if pl.get('homeworld') and pl.get('ownerPlayerId') == cargo_pid][0]


# Freighters — первая технология раздела Power, а весь этот уровень империя получает на
# старте (п. 9). Поэтому здесь проверяется не отказ до технологии, а то, что грузовой
# флот стоит в списке стройки с первого хода: правило «только со своей технологией»
# живёт в ColonyService и держится юнит-тестами, а зависеть от того, чего у империи
# больше не бывает, сквозной прогон не должен.
cargo_tech = [t['optionCode'] for t in
              call_auth('GET', f'/api/games/{cargo_id}/research', cargo_tok)['acquired']]
check('технология грузовиков есть на старте — п. 4.1.1, п. 9',
      'freighters' in cargo_tech, str(cargo_tech))

home_colony = cargo_home()
freighter_project = [x for x in home_colony['colony']['available'] if x['code'] == 'FREIGHTER']
check('грузовой флот доступен в стройке — п. 4.1.1',
      len(freighter_project) == 1 and freighter_project[0]['cost'] == 50,
      str(freighter_project))

call('POST', f"/api/games/{cargo_id}/planets/{home_colony['id']}/project",
     {'accessToken': cargo_tok, 'projectCode': 'FREIGHTER'})
for _ in range(30):
    end_turn(cargo_id, cargo_tok)
    me = [pl for pl in call('GET', f'/api/games/{cargo_id}')['players'] if pl['id'] == cargo_pid][0]
    if me['freighters'] > 0:
        break
check('колония построила грузовик, и он ушёл империи — п. 4.1.1', me['freighters'] >= 1,
      f"грузовиков {me['freighters']}")

# Одинокая колония кормит себя сама: везти неоткуда, и подвоза нет.
lonely = cargo_home()
size = lonely['population']
call('POST', f"/api/games/{cargo_id}/planets/{lonely['id']}/population",
     {'accessToken': cargo_tok, 'farmers': 0, 'workers': size, 'scientists': 0})
starving = cargo_home()
check('колония без фермеров голодает — п. 4.1.1', starving['colony']['foodBalance'] < 0,
      f"баланс {starving['colony']['foodBalance']}")
check('подвоза нет, когда везти нечего: у империи одна колония — п. 4.1.1',
      starving['colony']['foodDelivered'] == 0,
      f"подвоз {starving['colony']['foodDelivered']}")

# Вторая колония в той же системе — и подвоз становится возможен. Свободная планета
# в родной системе есть не в каждой партии, поэтому проверка условная, как и у базы.
call('POST', f"/api/games/{cargo_id}/planets/{lonely['id']}/population",
     {'accessToken': cargo_tok, 'farmers': size, 'workers': 0, 'scientists': 0})
cargo_map = call_auth('GET', f'/api/games/{cargo_id}/map', cargo_tok)
cargo_system = [st for st in cargo_map['systems']
                if any(pl['id'] == lonely['id'] for pl in st['planets'])][0]
cargo_free = [pl for pl in cargo_system['planets']
              if pl['colonizable'] and not pl.get('ownerPlayerId')]

if cargo_free:
    call('POST', f"/api/games/{cargo_id}/planets/{lonely['id']}/project",
         {'accessToken': cargo_tok, 'projectCode': 'COLONY_BASE'})
    for _ in range(40):
        end_turn(cargo_id, cargo_tok)
        if cargo_home()['colonyBaseReady']:
            break
    call('POST', f"/api/games/{cargo_id}/planets/{lonely['id']}/colonize",
         {'accessToken': cargo_tok, 'targetPlanetId': cargo_free[0]['id']})

    def cargo_colonies():
        m = call_auth('GET', f'/api/games/{cargo_id}/map', cargo_tok)
        return [pl for st in m['systems'] for pl in st['planets']
                if pl.get('ownerPlayerId') == cargo_pid]

    # Без второй колонии везти некому и некуда: дальше проверялся бы подвоз самому себе,
    # и каждая проверка проваливалась бы со своей загадочной подписью. Поэтому сперва
    # говорим прямо, основалась ли колония, — и только потом возим.
    check('вторая колония для подвоза основана — п. 4.1.1', len(cargo_colonies()) > 1,
          f"колоний {len(cargo_colonies())}")

if cargo_free and len(cargo_colonies()) > 1:
    young = sorted(cargo_colonies(), key=lambda c: c['population'])[0]
    donor = sorted(cargo_colonies(), key=lambda c: c['population'])[-1]
    # Молодая колония работает без фермеров, старая — только фермерами: излишек есть,
    # и его должен развезти грузовой флот.
    call('POST', f"/api/games/{cargo_id}/planets/{young['id']}/population",
         {'accessToken': cargo_tok, 'farmers': 0, 'workers': young['population'], 'scientists': 0})
    call('POST', f"/api/games/{cargo_id}/planets/{donor['id']}/population",
         {'accessToken': cargo_tok, 'farmers': donor['population'], 'workers': 0, 'scientists': 0})

    after = {c['id']: c for c in cargo_colonies()}
    fed = after[young['id']]
    supplier = after[donor['id']]
    check('грузовой флот довозит еду голодающей колонии — п. 4.1.1',
          fed['colony']['foodDelivered'] > 0,
          f"подвоз {fed['colony']['foodDelivered']} при излишке донора"
          f" {supplier['colony']['foodBalance']}")
    # Голодная колония теряет жителей, сытая — нет. Расти при этом она обязана не всегда:
    # молодая колония на крошечной планете бывает уже заполнена под завязку, и тогда
    # прирост ноль. Проверяется именно отсутствие убыли — она и есть признак голода.
    check('подвоз спасает колонию от убыли населения — п. 4.1.1',
          fed['colony']['growthK'] >= 0,
          f"прирост {fed['colony']['growthK']} при балансе {fed['colony']['foodBalance']},"
          f" вместимость {fed['colony']['maxPopulation']}")

    # --- 21. Перевозка жителей внутри своей системы (п. 4.1.1) ---
    #
    # Правило оригинала: «population transfers do not need Freighters and take effect
    # immediately if within the same system» (StrategyWiki, Growing your population).
    # Обе колонии этой партии стоят в родной системе — проверяется именно этот путь, и
    # прежде всего то, ради чего правило и правили: грузовой флот таким переселением не
    # занимается вовсе.
    #
    # Рейс между системами в прогон не помещается: второй системы у империи нет, пока она
    # не построит колониальный корабль за 500 единиц производства, а ИИ жителей не возит
    # вовсе. Числа рейса держит юнит-тест (`freightersForTransfer`), сам рейс проверяется
    # руками — это известный пробел прогона, а не забытая проверка.
    #
    # Второй грузовик строится всё равно — им и проверяется, что переселение его не тронуло.
    call('POST', f"/api/games/{cargo_id}/planets/{donor['id']}/project",
         {'accessToken': cargo_tok, 'projectCode': 'FREIGHTER'})
    for _ in range(30):
        end_turn(cargo_id, cargo_tok)
        me = [pl for pl in call('GET', f'/api/games/{cargo_id}')['players']
              if pl['id'] == cargo_pid][0]
        if me['freighters'] >= 2:
            break
    check('у империи два грузовика — п. 4.1.1', me['freighters'] >= 2, f"грузовиков {me['freighters']}")

    before_transfer = {c['id']: c for c in cargo_colonies()}
    source = before_transfer[donor['id']]
    destination = before_transfer[young['id']]
    source_before = source['population']
    destination_before = destination['population']
    room_before = destination['colony']['maxPopulation'] - destination_before
    reserved_before = me['freightersReserved']
    # Сколько переселять: и колонию не опустошить, и в колонию назначения поместиться.
    moving = min(2, source_before - 1, room_before)

    check('колонии есть кого и куда переселять — п. 4.1.1', moving >= 1,
          f"у донора {source_before} жителей, свободных мест {room_before}")

    if moving >= 1:
        sent = call('POST', f"/api/games/{cargo_id}/planets/{source['id']}/transfer",
                    {'accessToken': cargo_tok, 'targetPlanetId': destination['id'],
                     'population': moving})
        check('жители уходят с колонии сразу — п. 4.1.1',
              sent.get('population') == source_before - moving,
              f"{source_before} -> {sent.get('population')}")

        moved_now = {c['id']: c for c in cargo_colonies()}[destination['id']]
        check('в своей системе жители переходят сразу, не дожидаясь конца хода — п. 4.1.1',
              moved_now['population'] == destination_before + moving,
              f"{destination_before} -> {moved_now['population']}")

        me = [pl for pl in call('GET', f'/api/games/{cargo_id}')['players']
              if pl['id'] == cargo_pid][0]
        check('в своей системе грузовики не занимаются — п. 4.1.1',
              me['freightersReserved'] == reserved_before,
              f"занято {me['freightersReserved']} из {me['freighters']}")

        # Ждать на борту в своей системе негде: больше, чем вмещает колония назначения,
        # не перевезти вовсе.
        room_now = moved_now['colony']['maxPopulation'] - moved_now['population']
        overflow = call('POST', f"/api/games/{cargo_id}/planets/{source['id']}/transfer",
                        {'accessToken': cargo_tok, 'targetPlanetId': destination['id'],
                         'population': room_now + 1})
        check('больше, чем вмещает колония, в своей системе не перевезти — п. 4.1.1',
              overflow.get('ERROR') == 409,
              str(overflow.get('body', {}).get('message')))

call('DELETE', f'/api/games/{cargo_id}?accessToken={cargo_tok}')

# --- 21. Отчёт хода: что стоит показывать игроку (п. 11.1) ---
# Окно «Результаты хода» всплывает не ради каждого пополнения казны: доход идёт каждый
# ход, и окно ради него обесценилось бы. А вот голод колонии игрок должен увидеть сразу —
# до того, как жители начнут пропадать.
hunger = call('POST', '/api/games', {'name': 'Голод', 'playerName': 'Наблюдатель',
                                     'homeStarName': 'Пустошь', 'galaxySize': 'SMALL'})
hunger_id = hunger['game']['id']
hunger_tok = hunger['credentials']['accessToken']
hunger_pid = hunger['credentials']['playerId']
call('POST', f'/api/games/{hunger_id}/start', {'accessToken': hunger_tok})


def hunger_home():
    m = call_auth('GET', f'/api/games/{hunger_id}/map', hunger_tok)
    return [pl for st in m['systems'] for pl in st['planets']
            if pl.get('homeworld') and pl.get('ownerPlayerId') == hunger_pid][0]


hunger_colony = hunger_home()
# Все жители в рабочие: колония перестаёт себя кормить, и прирост уходит в минус.
call('POST', f"/api/games/{hunger_id}/planets/{hunger_colony['id']}/population",
     {'accessToken': hunger_tok, 'farmers': 0, 'workers': hunger_colony['population'],
      'scientists': 0})
starving = hunger_home()
check('колония без фермеров уходит в минус по приросту — п. 4.1.1',
      starving['colony']['growthK'] < 0, f"прирост {starving['colony']['growthK']}")

end_turn(hunger_id, hunger_tok)
hunger_report = call_auth('GET', f'/api/games/{hunger_id}/turn/report', hunger_tok)
hunger_events = hunger_report['events']
check('голод колонии попадает в отчёт хода — п. 11.1',
      any(e['code'] == 'POPULATION' and 'теряет население' in e['text'] for e in hunger_events),
      str([e['text'] for e in hunger_events]))
# Убыточной колония становится от содержания зданий, а их у молодой империи ещё нет:
# проверяем обратное — что событие не выдумывается на пустом месте.
check('прибыльная колония убыточной не объявляется — п. 10',
      not any(e['code'] == 'LOSS' for e in hunger_events),
      str([e['code'] for e in hunger_events]))
check('пополнение казны в отчёте есть, но само по себе окна не стоит — п. 11.1',
      any(e['code'] == 'INCOME' for e in hunger_events),
      str([e['code'] for e in hunger_events]))

call('DELETE', f'/api/games/{hunger_id}?accessToken={hunger_tok}')

# --- 22. Ошибка клиента не выглядит поломкой сервера ---
# Общий обработчик Exception превращал в 500 всё подряд: и запрос чужим методом, и чужой
# Content-Type, и кривой UUID в пути. Вызывающий шёл искать поломку не в своём коде.
zero = '00000000-0000-0000-0000-000000000000'
wrong_method = probe('GET', f'/api/games/{zero}/planets/{zero}/project')
check('чужой метод — 405, а не 500', wrong_method['status'] == 405, str(wrong_method['status']))
check('405 называет настоящие методы заголовком Allow',
      'POST' in wrong_method['allow'], wrong_method['allow'])
check('несуществующий путь — 404', probe('GET', '/api/nope')['status'] == 404)
check('чужой Content-Type — 415',
      probe('POST', '/api/games', b'x', 'text/plain')['status'] == 415)
check('кривой UUID в пути — 400', probe('GET', '/api/games/not-a-uuid')['status'] == 400)
check('сломанный JSON — 400', probe('POST', '/api/games', b'{ne json')['status'] == 400)

# --- 23. Дизайн кораблей (п. 8) ---
# Корабль перестал быть безымянным: империя собирает проект в окне дизайна, колония
# строит выбранный проект, а флот знает, из каких кораблей он собран.
# Зерно задано: в этой галактике в родной системе есть свободная пригодная планета, и
# правило верфи проверяется на молодой колонии каждый прогон, а не когда повезёт.
ship_game = call('POST', '/api/games', {'name': 'Верфь', 'playerName': 'Инженер',
                                        'homeStarName': 'Верфь', 'galaxySize': 'SMALL',
                                        'seed': 6})
ship_id = ship_game['game']['id']
ship_tok = ship_game['credentials']['accessToken']
ship_pid = ship_game['credentials']['playerId']
call('POST', f'/api/games/{ship_id}/start', {'accessToken': ship_tok})

ship_catalog = call_auth('GET', f'/api/games/{ship_id}/ship-designs/catalog', ship_tok)
hull_codes = [x['code'] for x in ship_catalog['hulls']]
check('справочник отдаёт шесть корпусов MOO II — п. 8',
      hull_codes == ['frigate', 'destroyer', 'cruiser', 'battleship', 'titan', 'doom-star'],
      str(hull_codes))
check('ячеек проектов шесть, как в оригинале — п. 8', ship_catalog['maxDesigns'] == 6)
check('крупные корпуса закрыты до своих технологий — п. 8',
      [x['code'] for x in ship_catalog['hulls'] if not x['available']] == ['titan', 'doom-star'],
      str([x['code'] for x in ship_catalog['hulls'] if not x['available']]))
open_parts = [x['code'] for x in ship_catalog['components'] if x['available']]
# Стартовые технологии (п. 9) дают не только двигатель: Chemistry несёт броню и ракету,
# Physics — лазер. Ровно на это и опирается кораблестроение с первого хода.
check('империя начинает с двигателя, брони и пушек — п. 8, п. 9',
      set(open_parts) == {'nuclear-drive', 'titanium-armor', 'mass-driver',
                          'laser-cannon', 'nuclear-missile'}, str(open_parts))

# Вид выстрела нужен окну дизайна: им оно раскладывает пушки лучами, снарядами и
# ракетами — полосой BEAM/MISSILE/BOMB оригинала. У всего, что не оружие, вида нет.
weapon_kinds = {x['code']: x.get('weaponKind') for x in ship_catalog['components']
                if x['slot'] == 'WEAPON'}
check('у каждой пушки в справочнике есть вид выстрела — п. 8',
      all(kind in ('BEAM', 'PROJECTILE', 'MISSILE') for kind in weapon_kinds.values()),
      str(sorted({k for k in weapon_kinds.values()})))
check('вид выстрела есть только у оружия — п. 8',
      all(x.get('weaponKind') is None for x in ship_catalog['components']
          if x['slot'] != 'WEAPON'),
      str([x['code'] for x in ship_catalog['components']
           if x['slot'] != 'WEAPON' and x.get('weaponKind')]))

start_designs = call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok)
# Ячейки дизайна не пустуют: игра держит в них проект на каждый корпус, который империя
# уже вправе строить, — п. 8. Их четыре, пока не изучены титан и звезда смерти.
check('империя входит в партию с проектом на каждый доступный корпус — п. 8',
      len(start_designs) >= 4
      and [x['hullCode'] for x in sorted(start_designs, key=lambda d: d['slot'])][:4]
          == ['frigate', 'destroyer', 'cruiser', 'battleship']
      and all(x['spaceUsed'] <= x['space'] for x in start_designs),
      str([(x['slot'], x['name'], x['hullCode']) for x in start_designs]))

# Изучив лучшую пушку, империя получает НОВЫЕ автопроекты, а прежние уходят из окна
# дизайна — п. 8: построенный корабль в MOO II остаётся с тем, с чем построен, поэтому
# состав правится не на месте, а новой строкой (`replaceAutoDesign`).
#
# Проверяется это прогоном, а не чтением кода, и вот почему. Составы проектов вычитываются
# теперь ОДНОЙ выборкой на вызов, а не по проекту в цикле (выборка стеков на круге 7
# показала `items`/`itemsOf` почти третью всего хода). Ошибка в такой правке молчит:
# проект просто перестанет обновляться, флот останется с ядерными ракетами, и заметить это
# можно будет только по балансовому прогону через несколько часов.
# Признака «сделан игрой» в ответе нет, да он и не нужен: до этого места игрок не сохранял
# ни одного проекта, значит все шесть ячеек заняты автоматическими.
#
# Изучается БРОНЯ, а не пушка, и это не мелочь: числа оружия взяты у оригинала, а там
# термоядерный луч масс-драйвера НЕ лучше (6 урона против 6 при том же месте — вики прямо
# говорит, что неулучшенный масс-драйвер выигрывает за счёт неослабевающего удара). Пушкой
# ниже нейтронного бластера этой проверке двигать нечего, а броня даёт тот же путь:
# ResearchService.store -> ensureAutoDesigns -> replaceAutoDesign.
auto_before = {x['slot']: x['id'] for x in start_designs}
call('POST', f'/api/games/{ship_id}/research',
     {'accessToken': ship_tok, 'categoryCode': 'chemistry', 'levelOrder': 2,
      'optionCode': 'tritanium-armor'})
for _ in range(60):
    end_turn(ship_id, ship_tok)
    if any(x['optionCode'] == 'tritanium-armor'
           for x in call_auth('GET', f'/api/games/{ship_id}/research', ship_tok)['acquired']):
        break
designs_after = call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok)
auto_after = {x['slot']: x['id'] for x in designs_after}
replaced = [slot for slot, old in auto_before.items() if auto_after.get(slot) != old]
check('изучив лучшую броню, империя получает новые автопроекты — п. 8',
      bool(auto_before) and bool(replaced),
      f"ячеек было {len(auto_before)}, сменилось {len(replaced)}")
check('вытесненный автопроект уходит из окна дизайна — п. 8',
      all(auto_before[slot] not in {x['id'] for x in designs_after} for slot in replaced),
      str(replaced))

# Кораблестроение открывается базовыми уровнями Power и Chemistry (п. 8), и оба даются
# на старте (п. 9): окно дизайна открыто с первого хода, а не после первых исследований.
# Раньше здесь проверялось обратное — и проходило ровно потому, что стартовые технологии
# не выдавались никому.
check('окно дизайна открыто с первого хода — п. 8, п. 9',
      ship_catalog['available'] is True and ship_catalog.get('requirement') is None,
      str(ship_catalog.get('requirement')))
early_save = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 2, 'name': 'Ранний', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1}]})
check('проект сохраняется с первого хода — п. 8', early_save.get('ERROR') is None,
      str(early_save.get('body', {}).get('message')))

ship_map = call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)
ship_home = [x for st in ship_map['systems'] for x in st['planets']
             if x.get('ownerPlayerId') == ship_pid][0]
check('родной мир начинает со звёздной базой — п. 8',
      any(b['code'] == 'star-base' for b in ship_home['colony']['buildings']),
      str([b['code'] for b in ship_home['colony']['buildings']]))
# Казармы морской пехоты стоят на родном мире с первого хода, как в MOO II, где это
# стартовая технология, — п. 12. Колонию защищают обученные бойцы, а не жители с винтовками.
check('родной мир начинает с казармами — п. 12',
      any(b['code'] == 'marine-barracks' for b in ship_home['colony']['buildings']),
      str([b['code'] for b in ship_home['colony']['buildings']]))
check('корабль в стройке есть с первого хода — п. 8, п. 9',
      any(x['code'].startswith('SHIP:') for x in ship_home['colony']['available']),
      str([x['code'] for x in ship_home['colony']['available']]))

research_shipbuilding(ship_id, ship_tok)
ship_catalog = call_auth('GET', f'/api/games/{ship_id}/ship-designs/catalog', ship_tok)
check('после базовых технологий окно дизайна открыто — п. 8',
      ship_catalog['available'] is True and ship_catalog['hullSizeWithoutStarBase'] == 2,
      str(ship_catalog.get('requirement')))

hunter = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 2, 'name': 'Охотник', 'hullCode': 'destroyer',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'titanium-armor', 'count': 1},
                   {'code': 'mass-driver', 'count': 4}]})
# Эсминец: множитель корпуса 2, и вдвое растёт то, что обслуживает весь корабль, —
# двигатель и броня (п. 8). Ствол своего места не меняет, и место его — оригинала:
# у масс-драйвера десять клеток. Место: 4*2 + 3*2 + 4 ствола по 10 = 54 из 60.
# Цена: 42 (корпус) + 12*2 + 3*2 + 4 * 4 = 88. Залп: 4 ствола по 6 = 24.
check('проект считается по корпусу и компонентам — п. 8',
      hunter.get('spaceUsed') == 8 + 6 + 40 and hunter.get('cost') == 42 + 24 + 6 + 16
      and hunter.get('attack') == 24 and hunter.get('structure') == 40,
      f"место {hunter.get('spaceUsed')}, цена {hunter.get('cost')}, "
      f"залп {hunter.get('attack')}, отказ {hunter.get('message')}")
# Пушка своё место не меняет, а броня растёт вместе с корпусом (п. 8): четыре
# масс-драйвера занимают 40 места (по 10, как в оригинале), броня эсминца — шесть
# вместо трёх.
check('оружие не растёт с корпусом, а броня растёт — п. 8',
      bool(hunter.get('components'))
      and [x for x in hunter['components'] if x['code'] == 'mass-driver'][0]['space'] == 40
      and [x for x in hunter['components'] if x['code'] == 'titanium-armor'][0]['space'] == 6,
      str(hunter.get('components'))[:160])
# Размер ствола — оригинала (п. 8): у лучей со снарядами база десять клеток, а у Копья
# новы пятьдесят, и на эсминец оно не встаёт вовсе — место корпуса всего шестьдесят.
gun_space = {x['code']: x['space'] for x in ship_catalog['components']
             if x['slot'] == 'WEAPON'}
check('размеры стволов взяты у оригинала — п. 8',
      gun_space.get('mass-driver') == 10 and gun_space.get('laser-cannon') == 10
      and gun_space.get('plasma-cannon') == 10 and gun_space.get('graviton-beam') == 15
      and gun_space.get('ion-pulse-cannon') == 20 and gun_space.get('mauler-device') == 50
      and gun_space.get('stellar-converter') == 50,
      str(sorted(gun_space.items()))[:200])
# Урон — тоже оригинала (п. 8): у оружия с разбросом взята верхняя граница (лазер 1-4,
# плазма 6-30), а выстрел в залпе ОДИН у всех — больше даёт только модификация.
guns = {x['code']: x for x in ship_catalog['components'] if x['slot'] == 'WEAPON'}


def gun_amount(code, effect):
    rows = [e['amount'] for e in guns.get(code, {}).get('effects', []) if e['type'] == effect]
    return rows[0] if rows else None


check('урон стволов взят у оригинала — п. 8',
      gun_amount('laser-cannon', 'WEAPON_DAMAGE') == 4
      and gun_amount('mass-driver', 'WEAPON_DAMAGE') == 6
      and gun_amount('fusion-beam', 'WEAPON_DAMAGE') == 6
      and gun_amount('ion-pulse-cannon', 'WEAPON_DAMAGE') == 10
      and gun_amount('phasor', 'WEAPON_DAMAGE') == 20
      and gun_amount('plasma-cannon', 'WEAPON_DAMAGE') == 30
      and gun_amount('mauler-device', 'WEAPON_DAMAGE') == 100
      and gun_amount('stellar-converter', 'WEAPON_DAMAGE') == 400,
      str({k: gun_amount(k, 'WEAPON_DAMAGE') for k in sorted(guns)})[:220])
check('урон ракет и торпед взят у оригинала — п. 8',
      gun_amount('nuclear-missile', 'WEAPON_DAMAGE') == 8
      and gun_amount('merculite-missile', 'WEAPON_DAMAGE') == 14
      and gun_amount('pulson-missile', 'WEAPON_DAMAGE') == 20
      and gun_amount('zeon-missile', 'WEAPON_DAMAGE') == 30
      and gun_amount('anti-matter-torpedoes', 'WEAPON_DAMAGE') == 25
      and gun_amount('proton-torpedo', 'WEAPON_DAMAGE') == 40
      and gun_amount('plasma-torpedo', 'WEAPON_DAMAGE') == 120,
      str({k: gun_amount(k, 'WEAPON_DAMAGE') for k in sorted(guns)})[:220])
check('выстрел в залпе один у каждого ствола — п. 8',
      all(gun_amount(code, 'WEAPON_SHOTS') == 1 for code in guns),
      str({k: gun_amount(k, 'WEAPON_SHOTS') for k in sorted(guns)})[:220])
# Плазменная пушка и Копьё новы обволакивают цель сами собой, и модификация ENV им уже не
# предлагается — так и в оригинале.
check('плазма и Копьё новы обволакивают цель сами собой — п. 8',
      guns.get('plasma-cannon', {}).get('envelops') is True
      and guns.get('stellar-converter', {}).get('envelops') is True
      and guns.get('phasor', {}).get('envelops') in (False, None)
      and 'enveloping' not in guns.get('plasma-cannon', {}).get('modifications', []),
      f"плазма {guns.get('plasma-cannon', {}).get('envelops')},"
      f" Копьё {guns.get('stellar-converter', {}).get('envelops')},"
      f" модификации плазмы {guns.get('plasma-cannon', {}).get('modifications')}")

no_engine = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 3, 'name': 'Без хода', 'hullCode': 'frigate',
    'components': [{'code': 'mass-driver', 'count': 1}]})
check('без двигателя проект не сохранить — п. 8', no_engine.get('ERROR') == 409)
too_big = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 3, 'name': 'Толстяк', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1}, {'code': 'mass-driver', 'count': 20}]})
check('проект, не влезающий в корпус, не сохранить — п. 8', too_big.get('ERROR') == 409)
locked_hull = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 3, 'name': 'Титан', 'hullCode': 'titan',
    'components': [{'code': 'nuclear-drive', 'count': 1}]})
check('неизученный корпус не поставить — п. 8', locked_hull.get('ERROR') == 409)
# Оружие для этой проверки берётся ИЗ СПРАВОЧНИКА — то, что он сам назвал недоступным,
# а не выбранное по имени. Имя привязывает отрицательную проверку к тому, что империя
# успела изучить: лазер перестал годиться, когда завели стартовые технологии, а
# термоядерный луч — когда соседний раздел начал его изучать. Держаться надо за сам
# запрет (CLAUDE.md, «отрицательную проверку пиши так, чтобы она держалась на запрете»).
locked_weapons = [x['code'] for x in ship_catalog['components']
                  if x['slot'] == 'WEAPON' and not x['available']]
check('справочник знает неизученное оружие — п. 8', bool(locked_weapons),
      str(len(locked_weapons)))
locked_part = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 3, 'name': 'Незнакомый', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': locked_weapons[0], 'count': 1}]})
check('неизученное оружие не поставить — п. 8', locked_part.get('ERROR') == 409,
      f"пробовали {locked_weapons[0] if locked_weapons else '—'}")
two_engines = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 3, 'name': 'Двухмоторный', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 2}]})
check('второй двигатель кораблю не поставить — п. 8', two_engines.get('ERROR') == 409)

# Ячейка переписывается, а построенные корабли остаются прежними — п. 8.
rewritten = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 2, 'name': 'Охотник-2', 'hullCode': 'destroyer',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'titanium-armor', 'count': 1},
                   {'code': 'mass-driver', 'count': 3}]})
after_rewrite = call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok)
check('переделка ячейки заводит новый проект, а не правит старый — п. 8',
      rewritten.get('id') != hunter.get('id')
      and [x['name'] for x in after_rewrite if x['slot'] == 2] == ['Охотник-2']
      and len({x['slot'] for x in after_rewrite}) == len(after_rewrite),
      str([(x['slot'], x['name']) for x in after_rewrite]))

# Модификации ствола — п. 8: тяжёлое орудие бьёт больнее, стоит и занимает вдвое больше,
# бронебойное проходит броню. Числа приходят с сервера, и проверяются именно они.
# Корпус здесь ЭСМИНЕЦ, а не фрегат: тяжёлая установка удваивает место ствола, а у
# ствола оригинала оно десять — пара тяжёлых масс-драйверов (40) во фрегат не влезает.
plain_gun = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 4, 'name': 'Обычный', 'hullCode': 'destroyer',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'mass-driver', 'count': 2}]})
heavy_gun = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 5, 'name': 'Тяжёлый', 'hullCode': 'destroyer',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'mass-driver', 'count': 2,
                    'modifications': ['heavy-mount']}]})
plain_row = [x for x in plain_gun['components'] if x['code'] == 'mass-driver'][0]
heavy_row = [x for x in heavy_gun['components'] if x['code'] == 'mass-driver'][0]
check('модификация приходит вместе с проектом — п. 8',
      heavy_row['modifications'] == ['heavy-mount'] and plain_row['modifications'] == [],
      f"{heavy_row['modifications']} и {plain_row['modifications']}")
check('тяжёлое орудие бьёт в полтора раза больнее — п. 8',
      heavy_row['damage'] == plain_row['damage'] * 3 // 2,
      f"{plain_row['damage']} -> {heavy_row['damage']}")
check('тяжёлое орудие стоит и занимает вдвое больше — п. 8',
      heavy_row['cost'] == plain_row['cost'] * 2 and heavy_row['space'] == plain_row['space'] * 2,
      f"цена {plain_row['cost']} -> {heavy_row['cost']},"
      f" место {plain_row['space']} -> {heavy_row['space']}")
check('залп проекта считается с поправкой модификации — п. 8',
      heavy_gun['attack'] > plain_gun['attack'],
      f"{plain_gun['attack']} -> {heavy_gun['attack']}")

# Модификации бывают только у оружия: двигатель с тяжёлым орудием — отказ.
bad_mod = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 6, 'name': 'Нелепый', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1, 'modifications': ['heavy-mount']},
                   {'code': 'mass-driver', 'count': 1}]})
check('модификации бывают только у оружия — п. 8', bad_mod.get('ERROR') == 409,
      str(bad_mod.get('body', {}).get('message')))

# Набор модификаций свой у каждого вида оружия и у каждого ствола — п. 8.
# Лучи со снарядами переделывают установкой (HV, PD, AP, SP, CO, NR, AF, ENV), ракеты —
# собой самой (ARM, FST, OVR, ECCM, MIRV, EMG), и оружие вправе сузить этот список.
mod_codes = {x['code']: x for x in ship_catalog['modifications']}
check('справочник отдаёт все модификации MOO II — п. 8',
      len(mod_codes) == 14 and 'heavy-mount' in mod_codes and 'mirv' in mod_codes,
      str(sorted(mod_codes)))
weapon_mods = {x['code']: x.get('modifications', []) for x in ship_catalog['components']
               if x['slot'] == 'WEAPON'}
check('ракетные модификации стволу не предлагаются, а ракете — только они — п. 8',
      'mirv' not in weapon_mods['mass-driver'] and 'heavy-mount' in weapon_mods['mass-driver']
      and 'mirv' in weapon_mods['nuclear-missile']
      and 'heavy-mount' not in weapon_mods['nuclear-missile'],
      f"масс-драйвер {weapon_mods['mass-driver']}, ракета {weapon_mods['nuclear-missile']}")
check('обволакивающий удар и непрерывный луч снаряду недоступны — п. 8',
      'enveloping' not in weapon_mods['mass-driver']
      and 'continuous' not in weapon_mods['mass-driver']
      and 'enveloping' in weapon_mods['laser-cannon'],
      f"масс-драйвер {weapon_mods['mass-driver']}, лазер {weapon_mods['laser-cannon']}")
check('тяжёлый луч ближней обороной не становится, а лёгкий — да — п. 8',
      'point-defense' in weapon_mods['laser-cannon']
      and 'point-defense' not in weapon_mods['plasma-cannon']
      and 'heavy-mount' in weapon_mods['plasma-cannon'],
      f"лазер {weapon_mods['laser-cannon']}, плазма {weapon_mods['plasma-cannon']}")
check('звёздный конвертер не переделывают вовсе — п. 8',
      weapon_mods['stellar-converter'] == [], str(weapon_mods['stellar-converter']))
check('у модификаций приходят все числа: выстрелы, дальность и ракетные свойства — п. 8',
      mod_codes['heavy-mount']['rangePercent'] == 100
      and mod_codes['point-defense']['rangePercent'] == -50
      and mod_codes['auto-fire']['shotsPercent'] == 200
      and mod_codes['auto-fire']['attackPercent'] == -20
      and mod_codes['mirv']['shotsPercent'] == 300
      and mod_codes['eccm']['halvesEvasion'] is True
      and mod_codes['emissions-guidance']['hitsEngine'] is True
      and mod_codes['enveloping']['envelops'] is True
      and mod_codes['no-range-penalty']['noRangePenalty'] is True,
      str({k: (v['shotsPercent'], v['rangePercent']) for k, v in mod_codes.items()}))
check('неизученная модификация помечена в справочнике — п. 8',
      mod_codes['emissions-guidance']['available'] is False
      and mod_codes['heavy-mount']['available'] is True,
      str(mod_codes['emissions-guidance']))

# Чужую модификацию сервер не принимает, даже если клиент её прислал.
alien_mod = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 6, 'name': 'Разделяющийся', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'mass-driver', 'count': 1, 'modifications': ['mirv']}]})
check('ракетную модификацию на ствол не поставить — п. 8', alien_mod.get('ERROR') == 409,
      str(alien_mod.get('body', {}).get('message')))

# Ближняя оборона бьёт вдвое ближе и вдвое слабее, зато метче — числа оригинала.
defence_gun = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 6, 'name': 'Ближний', 'hullCode': 'destroyer',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'mass-driver', 'count': 2,
                    'modifications': ['point-defense']}]})
defence_row = [x for x in defence_gun['components'] if x['code'] == 'mass-driver'][0]
# Цена и место в строке состава даны на все стволы разом, и делится надвое каждый
# по отдельности: у ствола в четыре цены выходит два, а не два с половиной.
each_cost = max(1, (plain_row['cost'] // 2) // 2) * 2
each_space = max(1, (plain_row['space'] // 2) // 2) * 2
check('ближняя оборона вдвое слабее, дешевле и мельче — п. 8',
      defence_row['damage'] == plain_row['damage'] // 2
      and defence_row['cost'] == each_cost and defence_row['space'] == each_space,
      f"урон {plain_row['damage']} -> {defence_row['damage']},"
      f" цена {plain_row['cost']} -> {defence_row['cost']},"
      f" место {plain_row['space']} -> {defence_row['space']}")

# Постановщик помех сбивает ракеты, и только их — п. 8.
jammer_design = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 6, 'name': 'Помехи', 'hullCode': 'frigate',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'ecm-jammer', 'count': 1}]})
if jammer_design.get('ERROR') is None:
    check('постановщик помех даёт уклонение от ракет — п. 8',
          jammer_design['missileEvasion'] > 0 and plain_gun['missileEvasion'] == 0,
          f"с помехами {jammer_design['missileEvasion']}, без них {plain_gun['missileEvasion']}")
check('боевая скорость двигателя приходит проектом — п. 8',
      plain_gun['combatSpeed'] == 12,
      f"боевая скорость {plain_gun.get('combatSpeed')} при скорости {plain_gun['speed']}")

# Стройка: колония строит именно выбранный проект, и он приходит в свой флот.
ship_map = call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)
ship_home = [x for st in ship_map['systems'] for x in st['planets']
             if x.get('ownerPlayerId') == ship_pid][0]
ship_options = [x for x in ship_home['colony']['available'] if x['code'].startswith('SHIP:')]
# Проекты берутся свежими: к этому месту их прибавилось — модификации проверялись выше,
# и сравнивать список стройки со старым снимком значило бы поймать самих себя.
designs_now = call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok)
check('в списке стройки по строке на проект — п. 8',
      len(ship_options) == len(designs_now)
      and all(x['name'].startswith('Корабль: ') for x in ship_options),
      f"{[x['name'] for x in ship_options]} при проектах"
      f" {[x['name'] for x in designs_now]}")
chosen = [x for x in ship_options if x['name'] == 'Корабль: Охотник-2'][0]
check('цена стройки — цена проекта — п. 8',
      chosen['cost'] == rewritten['cost'], f"{chosen['cost']} и {rewritten['cost']}")

call('POST', f"/api/games/{ship_id}/planets/{ship_home['id']}/project",
     {'accessToken': ship_tok, 'projectCode': chosen['code']})
building = [x for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
            for x in st['planets'] if x['id'] == ship_home['id']][0]
check('колония строит выбранный проект — п. 8',
      building['colony']['projectCode'] == chosen['code']
      and building['colony']['projectName'] == 'Корабль: Охотник-2'
      and building['colony']['projectCost'] == rewritten['cost'],
      str(building['colony']['projectName']))

ship_fleet = []
for _ in range(40):
    end_turn(ship_id, ship_tok)
    ship_fleet = call_auth('GET', f'/api/games/{ship_id}/fleets', ship_tok)
    if ship_fleet:
        break
    current = [x for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
               for x in st['planets'] if x['id'] == ship_home['id']][0]
    if not current['colony'].get('projectCode'):
        call('POST', f"/api/games/{ship_id}/planets/{ship_home['id']}/project",
             {'accessToken': ship_tok, 'projectCode': chosen['code']})

check('построенный корабль встал во флот со своим проектом — п. 8',
      len(ship_fleet) == 1 and ship_fleet[0]['composition'][0]['designName'] == 'Охотник-2'
      and ship_fleet[0]['composition'][0]['ships'] == ship_fleet[0]['ships'],
      str(ship_fleet))
check('сила флота считается по проектам его кораблей — п. 8',
      ship_fleet[0]['power'] == rewritten['power'] * ship_fleet[0]['ships'],
      f"сила флота {ship_fleet[0]['power']}, сила корабля {rewritten['power']}")

# Верфь — п. 8: без звёздной базы со стапеля сходят только два наименьших корпуса.
cruiser = call_auth('POST', f'/api/games/{ship_id}/ship-designs', ship_tok, {
    'slot': 3, 'name': 'Крейсер', 'hullCode': 'cruiser',
    'components': [{'code': 'nuclear-drive', 'count': 1},
                   {'code': 'mass-driver', 'count': 2}]})


def ship_planet(planet_id):
    return [x for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
            for x in st['planets'] if x['id'] == planet_id][0]


home_now = ship_planet(ship_home['id'])
check('со звёздной базой колония строит крупный корпус — п. 8',
      any(x['code'] == 'SHIP:' + cruiser['id'] for x in home_now['colony']['available']),
      str([x['name'] for x in home_now['colony']['available'] if x['code'].startswith('SHIP')]))

# Молодая колония базы ещё не построила: ей доступны только фрегат и эсминец.
ship_system = [st for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
               if any(pl['id'] == ship_home['id'] for pl in st['planets'])][0]
ship_free = [pl for pl in ship_system['planets']
             if pl['colonizable'] and not pl.get('ownerPlayerId')]
if ship_free:
    call('POST', f"/api/games/{ship_id}/planets/{ship_home['id']}/project",
         {'accessToken': ship_tok, 'projectCode': 'COLONY_BASE'})
    for _ in range(40):
        end_turn(ship_id, ship_tok)
        if ship_planet(ship_home['id'])['colonyBaseReady']:
            break
    call('POST', f"/api/games/{ship_id}/planets/{ship_home['id']}/colonize",
         {'accessToken': ship_tok, 'targetPlanetId': ship_free[0]['id']})
    young = ship_planet(ship_free[0]['id'])
    young_ships = [x['name'] for x in young['colony']['available'] if x['code'].startswith('SHIP')]
    check('без звёздной базы колония крупный корпус не поднимает — п. 8',
          'Корабль: Крейсер' not in young_ships and len(young_ships) > 0, str(young_ships))
    check('звёздная база предлагается колонии без неё — п. 8',
          any(x['code'] == 'star-base' for x in young['colony']['available']),
          str([x['code'] for x in young['colony']['available']]))
    # Казармы — второе и последнее здание игры без технологии (п. 12): молодая колония
    # строит их с первого своего хода, ничего не изучая. Проверяется именно молодая: на
    # родном мире они уже стоят, и список стройки их не предложит.
    barracks = [x for x in young['colony']['available'] if x['code'] == 'marine-barracks']
    check('казармы строятся без технологий и стоят 60 — п. 12',
          len(barracks) == 1 and barracks[0]['cost'] == 60 and barracks[0]['upkeep'] == 1,
          str(barracks))
    check('бронеказармы без своей технологии не предлагают — п. 12',
          not any(x['code'] == 'armor-barracks' for x in young['colony']['available']))

# Оборона колонии — п. 8, п. 11: звёздная база, боевая станция, звёздная крепость и
# наземные батареи выходят в бой платформами. Проверяется здесь то, что легко сломать
# молча: платформа не должна попадать ни в окно дизайна, ни в список стройки кораблей —
# она здание, а не корабль, и переделать её нельзя.
ship_catalog_hulls = [x['code'] for x in ship_catalog['hulls']]
check('платформы обороны не корпуса окна дизайна — п. 8, п. 11',
      not any(code in ship_catalog_hulls for code in
              ('star-base', 'battle-station', 'star-fortress', 'planetary-battery')),
      str(ship_catalog_hulls))
check('проектов у империи не больше шести ячеек — п. 8',
      len(call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok)) <= 6,
      str(len(call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok))))
ship_home_now = ship_planet(ship_home['id'])
defence_offered = {x['code'] for x in ship_home_now['colony']['available']}
check('оборона без своей технологии не предлагается — п. 11',
      not ({'battle-station', 'star-fortress', 'missile-base', 'ground-batteries',
            'fighter-garrison'} & defence_offered),
      str(sorted(defence_offered)))
check('на родном мире звёздная база уже стоит и второй раз не предлагается — п. 8',
      'star-base' not in defence_offered
      and any(b['code'] == 'star-base' for b in ship_home_now['colony']['buildings']),
      str([b['code'] for b in ship_home_now['colony']['buildings']]))

# Слепок партии помнит и проекты, и состав флотов — п. 8.
ship_save = call('POST', f'/api/games/{ship_id}/save', {'accessToken': ship_tok})
ship_loaded = call('POST', f"/api/saves/{ship_save['id']}/load")
ship_id2 = ship_loaded['game']['id']
ship_tok2 = ship_loaded['credentials']['accessToken']
designs_before_save = call_auth('GET', f'/api/games/{ship_id}/ship-designs', ship_tok)
designs2 = call_auth('GET', f'/api/games/{ship_id2}/ship-designs', ship_tok2)
fleets2 = call_auth('GET', f'/api/games/{ship_id2}/fleets', ship_tok2)
check('загрузка: проекты кораблей на месте — п. 8',
      sorted(x['name'] for x in designs2) == sorted(x['name'] for x in designs_before_save),
      f"{[x['name'] for x in designs2]} вместо"
      f" {[x['name'] for x in designs_before_save]}")
check('загрузка: состав флота на месте — п. 8',
      len(fleets2) == 1 and fleets2[0]['composition'][0]['designName'] == 'Охотник-2'
      and fleets2[0]['power'] == ship_fleet[0]['power'],
      str(fleets2))

call('DELETE', f'/api/games/{ship_id2}?accessToken={ship_tok2}')
call('DELETE', f"/api/saves/{ship_save['id']}")

# Флот делится: игрок отбирает корабли и отправляет часть, остальные держат систему — п. 8.
# Строим второй корабль: на одном корабле делить нечего.
split_fleet = ship_fleet
for _ in range(40):
    split_fleet = call_auth('GET', f'/api/games/{ship_id}/fleets', ship_tok)
    if split_fleet and split_fleet[0]['ships'] >= 2:
        break
    yard = [x for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
            for x in st['planets'] if x['id'] == ship_home['id']][0]
    # Достроив корабль, верфь переходит на товары (п. 10) — значит, освободилась,
    # и второй корабль закладывается именно тогда.
    if yard['colony'].get('projectCode') in (None, 'TRADE_GOODS'):
        call('POST', f"/api/games/{ship_id}/planets/{ship_home['id']}/project",
             {'accessToken': ship_tok, 'projectCode': chosen['code']})
    end_turn(ship_id, ship_tok)

source = split_fleet[0]
check('во флоте набралось два корабля — п. 8', source['ships'] >= 2, f"кораблей {source['ships']}")

split_design = source['composition'][0]['designId']
# Цель берём из достижимых: дальше дальности топлива приказ не отдать — п. 8.
split_reach = call_auth('GET', f'/api/games/{ship_id}/ships', ship_tok)['reachableSystemIds']
split_target = [st for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
                if st['id'] != source['starSystemId'] and st['id'] in split_reach][0]

too_many = call('POST', f"/api/games/{ship_id}/fleets/{source['id']}/move",
                {'accessToken': ship_tok, 'targetSystemId': split_target['id'],
                 'ships': [{'designId': split_design, 'ships': source['ships'] + 1}]})
check('больше кораблей, чем есть, отправить нельзя — п. 8', too_many.get('ERROR') == 409,
      str(too_many.get('body', {}).get('message')))

sent = call('POST', f"/api/games/{ship_id}/fleets/{source['id']}/move",
            {'accessToken': ship_tok, 'targetSystemId': split_target['id'],
             'ships': [{'designId': split_design, 'ships': 1}]})
split_after = call_auth('GET', f'/api/games/{ship_id}/fleets', ship_tok)
staying = [f for f in split_after if f['id'] == source['id']]
check('часть флота уходит, а остальные остаются на месте — п. 8',
      sent['ships'] == 1 and len(split_after) == 2
      and staying and staying[0]['ships'] == source['ships'] - 1,
      str([(f['systemName'], f['ships']) for f in split_after]))
check('ушедшие корабли идут к цели, а не оказываются там сразу — п. 8',
      sent['targetSystemId'] == split_target['id'] and sent['arrivalTurn'] > sent['departureTurn']
      and sent['starSystemId'] == source['starSystemId'],
      f"курс {sent.get('targetSystemName')}, прибытие на ходу {sent.get('arrivalTurn')}")
check('ушедшие корабли забрали свой проект — п. 8',
      sent['composition'][0]['ships'] == 1
      and sent['composition'][0]['designId'] == split_design,
      str(sent['composition']))

# Отобрать весь состав — то же, что отправить флот целиком: пустой флот оставаться не должен.
rest = staying[0]
call('POST', f"/api/games/{ship_id}/fleets/{rest['id']}/move",
     {'accessToken': ship_tok, 'targetSystemId': split_target['id'],
      'ships': [{'designId': c['designId'], 'ships': c['ships']} for c in rest['composition']]})
split_final = call_auth('GET', f'/api/games/{ship_id}/fleets', ship_tok)
check('отправка всего состава — перелёт целиком, пустого флота не остаётся — п. 8',
      len(split_final) == 2 and all(f['targetSystemId'] == split_target['id'] for f in split_final),
      str([(f['systemName'], f['ships'], f.get('targetSystemName')) for f in split_final]))

# Флот в пути приказов не слышит: связи с ним нет, пока не изучена Hyperspace Communications.
redirect_to = [st for st in call_auth('GET', f'/api/games/{ship_id}/map', ship_tok)['systems']
               if st['id'] not in (split_target['id'], source['starSystemId'])
               and st['id'] in split_reach]
if redirect_to:
    denied = call('POST', f"/api/games/{ship_id}/fleets/{split_final[0]['id']}/move",
                  {'accessToken': ship_tok, 'targetSystemId': redirect_to[0]['id']})
    check('курс флота в пути не сменить без Hyperspace Communications — п. 8',
          denied.get('ERROR') == 409, str(denied.get('body', {}).get('message')))

# Обе части приходят к цели и встают в один строй: двух своих флотов в системе не держим.
for _ in range(20):
    split_final = call_auth('GET', f'/api/games/{ship_id}/fleets', ship_tok)
    if all(not f.get('targetSystemId') for f in split_final):
        break
    end_turn(ship_id, ship_tok)
check('пришедшие корабли встают в общий строй — п. 8',
      len(split_final) == 1 and split_final[0]['ships'] == source['ships']
      and split_final[0]['starSystemId'] == split_target['id'],
      str([(f['systemName'], f['ships']) for f in split_final]))

call('DELETE', f'/api/games/{ship_id}?accessToken={ship_tok}')

# --- 24. Стоимость особенностей рас: правка справочника (п. 7) ---
# Цены сторон расы правятся из главного меню и ложатся в JSON-справочник сервера.
# Проверка возвращает справочник как был: баланс игры она менять не должна.
def trait_picks(design, code):
    return [o['picks'] for g in design['groups'] for o in g['options'] if o['code'] == code][0]


costs_before = call('GET', '/api/reference/race-traits')
costs_budget = costs_before['picks']
costs_growth = trait_picks(costs_before, 'growth-fast')

costs_saved = call('PUT', '/api/reference/race-traits',
                   {'picks': costs_budget + 2, 'traits': [{'code': 'growth-fast',
                                                           'picks': costs_growth + 2}]})
check('правка цен возвращает справочник с новыми числами — п. 7',
      costs_saved['picks'] == costs_budget + 2
      and trait_picks(costs_saved, 'growth-fast') == costs_growth + 2,
      f"бюджет {costs_saved['picks']}, цена {trait_picks(costs_saved, 'growth-fast')}")

costs_read = call('GET', '/api/reference/race-traits')
check('правка пережила запрос: справочник читает её с диска — п. 7',
      costs_read['picks'] == costs_budget + 2
      and trait_picks(costs_read, 'growth-fast') == costs_growth + 2)

check('правится только цена: действия и описания особенности на месте — п. 7',
      [o for g in costs_read['groups'] for o in g['options'] if o['code'] == 'growth-fast'][0]['effects']
      == [o for g in costs_before['groups'] for o in g['options'] if o['code'] == 'growth-fast'][0]['effects'])

# Новая цена действует сразу: раса с подорожавшей стороной проходит проверку сервера по
# НОВОМУ бюджету.
#
# Вторая сторона подбирается ПО ЦЕНАМ, а не вписана кодом. Здесь стояла пара
# «плодовитые + хорошие фермеры»: она укладывалась в бюджет ровно до того дня, когда еда
# подорожала (журнал цен, п. 3.31), — и пара стала стоить восемнадцать при бюджете в
# семнадцать. Цены двигаются каждый цикл балансировки, поэтому спутник выбирается из того,
# что осталось от бюджета, и берётся только сторона без запретов на соседство.
costs_room = (costs_budget + 2) - (costs_growth + 2)
costs_mate = next((o['code'] for g in costs_read['groups'] for o in g['options']
                   if g['code'] != 'growth' and 0 < o['picks'] <= costs_room
                   and not o.get('excludes')), None)
costs_game = call('POST', '/api/games',
                  {'name': 'Цена расы', 'playerName': 'Тест', 'homeStarName': 'Ц',
                   'galaxySize': 'SMALL', 'raceName': 'Дорогие',
                   'raceTraits': ['growth-fast'] + ([costs_mate] if costs_mate else [])})
check('новая цена действует на конструктор расы сразу — п. 7', 'ERROR' not in costs_game,
      str(costs_game.get('body', {}).get('message')))
if 'ERROR' not in costs_game:
    call('DELETE', f"/api/games/{costs_game['game']['id']}"
                   f"?accessToken={costs_game['credentials']['accessToken']}")

unknown_trait = call('PUT', '/api/reference/race-traits',
                     {'traits': [{'code': 'no-such-trait', 'picks': 3}]})
check('неизвестная особенность в правке — 404 — п. 7', unknown_trait.get('ERROR') == 404,
      str(unknown_trait.get('body', {}).get('message')))
out_of_range = call('PUT', '/api/reference/race-traits',
                    {'traits': [{'code': 'growth-fast', 'picks': 500}]})
check('цена за границами разумного не принимается — п. 7', out_of_range.get('ERROR') == 400)
bad_budget = call('PUT', '/api/reference/race-traits', {'picks': 0, 'traits': []})
check('нулевой бюджет очков не принимается — п. 7', bad_budget.get('ERROR') == 400)

costs_restored = call('PUT', '/api/reference/race-traits',
                      {'picks': costs_budget, 'traits': [{'code': 'growth-fast', 'picks': costs_growth}]})
check('справочник вернулся к прежним ценам — п. 7',
      costs_restored['picks'] == costs_budget
      and trait_picks(costs_restored, 'growth-fast') == costs_growth)

# --- 25. Тактический бой (п. 8) ---
# Бой перестал быть формулой: корабли стоят на поле, ходят по инициативе и гибнут по
# одному. Проверяется весь путь — от «атаковать вручную» до списанных потерь.
# Случайные события в этой партии выключены намеренно: до встречи флотов проходит под
# сотню ходов, и пиратский налёт успевал утопить один из двух кораблей обороняющегося —
# проверка «в бой развёрнуты оба» проваливалась не на бое, а на невезении.
#
# Зерно у партии своё и постоянное: до встречи флотов идёт под сотню ходов по цепочке
# промежуточных звёзд, и на случайной галактике соседи через раз оказывались дальше, чем
# флот успевает пройти за отведённые шестьдесят перелётов. Проваливалась при этом не одна
# проверка: следом МОЛЧА пропускался весь блок боя — три десятка проверок, — и заметить это
# можно было только по упавшему счётчику. С зерном партия одна и та же, и сломается она
# одинаково у всех, а не через раз.
# ПАРТИЯ РОВНО НА ДВОИХ: сценарий про двух людей, и живой сосед ему не нужен. Добор ИИ
# попадал сюда молча, а с тех пор как ИИ обзавёлся разведкой и водит флоты сам, его
# корабли перехватывали одинокий корабль-курьер, а то и отнимали родной мир —
# проверки валились ЧЕРЕЗ РАЗ, не отличая поломку от невезения. Двое участников
# стали возможны после того, как из игры убрали победу на выборах Высшего совета:
# прежде партия из двух империй обрывалась голосованием на двадцать пятом ходу.
duel = call('POST', '/api/games', {'name': 'Тактика', 'playerName': 'Адмирал',
                                   'homeStarName': 'Флагман', 'galaxySize': 'SMALL',
                                   'galacticEvents': False, 'seed': 20260915,
                                   'totalPlayers': 2})
tac_id = duel['game']['id']
tac_tok = duel['credentials']['accessToken']
tac_pid = duel['credentials']['playerId']
tac_join = call('POST', f'/api/games/{tac_id}/join',
                {'playerName': 'Сосед', 'homeStarName': 'Сосед'})
tac_tok2 = tac_join['credentials']['accessToken']
tac_pid2 = tac_join['credentials']['playerId']
call('POST', f'/api/games/{tac_id}/start', {'accessToken': tac_tok})


def tac_home(player_id, token):
    """Родная система и планета игрока — с его же карты: чужие ему не видны."""
    mm = call_auth('GET', f'/api/games/{tac_id}/map', token)
    for st in mm['systems']:
        for pl in st['planets']:
            if pl.get('ownerPlayerId') == player_id:
                return st, pl
    return None, None


def tac_end_turn():
    end_turn(tac_id, tac_tok, tac_tok2)


def tac_build(token, player_id, count):
    """Строит кораблей, сколько просили: воевать нужно чем-то."""
    system, planet = tac_home(player_id, token)
    # Без базовых уровней Power и Chemistry корабля в стройке нет — п. 8.
    research_shipbuilding(tac_id, token, tac_tok if token != tac_tok else tac_tok2)
    for _ in range(90):
        fleets = call_auth('GET', f'/api/games/{tac_id}/fleets', token)
        if fleets and sum(f['ships'] for f in fleets) >= count:
            return system, planet
        mm = call_auth('GET', f'/api/games/{tac_id}/map', token)
        colony = [x for st in mm['systems'] for x in st['planets'] if x['id'] == planet['id']][0]
        # Колонии может уже не быть: в живой галактике ИИ водит флоты сам и родной мир
        # отнимают. Строить тогда нечем и негде — возвращаемся, и проверка выше честно
        # провалится своим сообщением, а не уронит весь прогон KeyError'ом.
        if 'colony' not in colony:
            return system, planet
        # Родной мир начинает с «Товаров»: проект корабля ставим, пока строится не он.
        if not (colony['colony'].get('projectCode') or '').startswith('SHIP'):
            ship = [x for x in colony['colony']['available'] if x['code'].startswith('SHIP:')][0]
            call('POST', f"/api/games/{tac_id}/planets/{planet['id']}/project",
                 {'accessToken': token, 'projectCode': ship['code']})
        tac_end_turn()
    return system, planet


tac_sys, _ = tac_build(tac_tok, tac_pid, 2)
_, tac_planet2 = tac_build(tac_tok2, tac_pid2, 2)
# Флот идёт к чужой звезде столько ходов, сколько нужно, и встреча заводится в конце
# того хода, когда он пришёл, — п. 8. Дальше своей дальности он не летает, поэтому
# сперва империя дотягивается до чужой звезды топливом и заставами.
reach_towards(tac_id, tac_tok2, tac_planet2['id'], tac_sys['id'], tac_tok)
# ПОГИБШИЙ КУРЬЕР — НЕ ПОВОД ВАЛИТЬ ПРОВЕРКУ. Галактика перестала быть безопасной: ИИ
# обзавёлся разведкой и водит флоты сам, и одинокий корабль на пути к чужой звезде
# гибнет — не всегда, а через раз. Проверка от этого стала ПЛАВАЮЩЕЙ, а плавающая хуже
# падающей: она не отличает поломку от невезения. Игрок в таком случае строит новый
# корабль и шлёт снова — так теперь делает и прогон.
tac_arrived = None
for tac_try in range(3):
    tac_arrived = fly_to(tac_id, tac_tok2, tac_sys['id'], tac_tok, limit=60)
    if tac_arrived is not None:
        break
    tac_build(tac_tok2, tac_pid2, 2)

# Встреча берётся с ЭТИМ соперником: по галактике теперь ходят и корабли ИИ, и лишняя
# встреча с забредшим соседом не должна валить проверку про двух людей.
tac_all = call_auth('GET', f'/api/games/{tac_id}/encounters', tac_tok)
tac_encounters = [e for e in tac_all if e.get('opponentPlayerId') == tac_pid2]
check('флоты встретились в системе — п. 8', len(tac_encounters) == 1,
      f'с соперником {len(tac_encounters)}, всего {len(tac_all)}: {tac_all}')

if tac_encounters:
    tac_enc = tac_encounters[0]
    tac_first = tac_tok if tac_enc['yourTurn'] else tac_tok2
    tac_second = tac_tok2 if tac_enc['yourTurn'] else tac_tok
    attacker_id = tac_pid if tac_enc['yourTurn'] else tac_pid2

    tac_started = call('POST', f"/api/games/{tac_id}/encounters/{tac_enc['id']}",
                       {'accessToken': tac_first, 'decision': 'ATTACK', 'auto': False})
    check('ручной бой открывает сцену, а не считает исход — п. 8',
          tac_started['state'] == 'IN_BATTLE' and tac_started.get('battleId'),
          f"состояние {tac_started['state']}")

    tac_battles = call_auth('GET', f'/api/games/{tac_id}/battles', tac_first)
    check('бой виден обеим сторонам — п. 8',
          len(tac_battles) == 1
          and len(call_auth('GET', f'/api/games/{tac_id}/battles', tac_second)) == 1,
          str(len(tac_battles)))

    tac_battle = tac_battles[0]
    # Поле размечено под боевую скорость оригинала: ход корабля — около трети поля, и вся
    # таблица ослабления луча (до 35 % на 21–23 клетках) на нём помещается — п. 8.
    check('поле боя 32 на 20 клеток, дальность залпа двадцать четыре — п. 8',
          tac_battle['width'] == 32 and tac_battle['height'] == 20
          and tac_battle['weaponRange'] == 24,
          f"{tac_battle['width']}x{tac_battle['height']}, дальность {tac_battle['weaponRange']}")

    sides = {'ATTACKER': [], 'DEFENDER': []}
    for tac_ship in tac_battle['ships']:
        sides[tac_ship['side']].append(tac_ship)
    # Оборона колонии в бою — п. 11: если бой идёт у чужой колонии, её платформа стоит в
    # строю обороняющегося. Проверка мягкая: колония на пути боя бывает не в каждой партии.
    tac_platforms = [x for x in tac_battle['ships'] if x['hullSize'] > 6]
    if tac_platforms:
        check('платформа обороны колонии вышла в бой — п. 11',
              all(x['side'] == 'DEFENDER' for x in tac_platforms),
              str([(x['designName'], x['side'], x['hullSize']) for x in tac_platforms]))

    # Правило проверяется ПО СОСТАВУ ФЛОТА, а не по числу «хотя бы два с каждой стороны».
    # Здесь стояло «два и два», и проверка опиралась на то, что построенные корабли доживут
    # до боя. С тех пор как ИИ водит флоты сам, обороняющийся успевает потерять корабль в
    # набеге — и проверка валилась там, где поломки нет. Суть же её в другом: флот выходит
    # в бой ПОИМЁННО, корабль за кораблём, а не одной фишкой.
    tac_attackers = tac_arrived['ships'] if tac_arrived else 0
    check('флоты развёрнуты в бой поимённо, по кораблю на единицу — п. 8',
          tac_attackers and len(sides['ATTACKER']) == tac_attackers
          and len(sides['DEFENDER']) >= 1,
          f"нападающих {len(sides['ATTACKER'])} при флоте в {tac_attackers},"
          f" обороняющихся {len(sides['DEFENDER'])}")
    check('стороны входят с разных краёв поля — п. 8',
          all(x['x'] <= 2 for x in sides['ATTACKER'])
          and all(x['x'] >= tac_battle['width'] - 3 for x in sides['DEFENDER']),
          str([(x['side'], x['x']) for x in tac_battle['ships']]))
    # Корпусов у корабля шесть, а с седьмого начинаются платформы обороны колонии
    # (п. 11): они выходят в бой наравне с кораблями, и сцена рисует их тем же способом.
    check('у корабля свой размер корпуса для сцены — п. 8',
          all(1 <= x['hullSize'] <= 10 for x in tac_battle['ships']),
          str({x['hullSize'] for x in tac_battle['ships']}))

    tac_queue = [next(s for s in tac_battle['ships'] if s['id'] == q) for q in tac_battle['queue']]
    check('очередь хода идёт по инициативе кораблей, а не по игрокам — п. 8',
          tac_queue and all(tac_queue[i]['initiative'] >= tac_queue[i + 1]['initiative']
                            for i in range(len(tac_queue) - 1))
          and len({x['ownerPlayerId'] for x in tac_queue}) == 2,
          str([(x['designName'], x['initiative']) for x in tac_queue]))
    check('ход у корабля из очереди, и он же первый — п. 8',
          tac_battle['currentShipId'] == tac_battle['queue'][0])

    tokens_by_owner = {tac_pid: tac_tok, tac_pid2: tac_tok2}
    tac_current = next(s for s in tac_battle['ships'] if s['id'] == tac_battle['currentShipId'])
    owner_token = tokens_by_owner[tac_current['ownerPlayerId']]
    other_token = tac_tok2 if owner_token == tac_tok else tac_tok

    denied = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                       other_token, {'shipId': tac_current['id'], 'action': 'PASS'})
    check('чужим кораблём не походить — п. 8', denied.get('ERROR') == 403,
          str(denied.get('body', {}).get('message')))

    not_now = next(s for s in tac_battle['ships']
                   if s['ownerPlayerId'] == tac_current['ownerPlayerId']
                   and s['id'] != tac_current['id'])
    early = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                      owner_token, {'shipId': not_now['id'], 'action': 'PASS'})
    check('вне очереди корабль не ходит — п. 8', early.get('ERROR') == 409,
          str(early.get('body', {}).get('message')))

    # На клетку дальше, чем корабль может пройти: своя скорость у каждого своя (боевая
    # скорость двигателя — п. 8), поэтому отсчёт идёт от неё, а не от размеров поля.
    over = tac_current['moveLeft'] + 1
    far_x = (tac_current['x'] + over if tac_current['x'] + over < tac_battle['width']
             else tac_current['x'] - over)
    far_move = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                         owner_token, {'shipId': tac_current['id'], 'action': 'MOVE',
                                       'x': far_x, 'y': tac_current['y']})
    check('дальше своей скорости корабль не ходит — п. 8', far_move.get('ERROR') == 409,
          str(far_move.get('body', {}).get('message')))

    far_enemy = next(s for s in tac_battle['ships'] if s['side'] != tac_current['side'])
    far_shot = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                         owner_token, {'shipId': tac_current['id'], 'action': 'FIRE',
                                       'targetShipId': far_enemy['id']})
    check('через всё поле залп не достаёт — п. 8', far_shot.get('ERROR') == 409,
          str(far_shot.get('body', {}).get('message')))

    friend_shot = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                            owner_token, {'shipId': tac_current['id'], 'action': 'FIRE',
                                          'targetShipId': not_now['id']})
    check('по своим не стреляем — п. 8', friend_shot.get('ERROR') == 409,
          str(friend_shot.get('body', {}).get('message')))

    # Играем бой до конца: сходимся и стреляем, пока одна сторона не кончится.
    tac_state = tac_battle
    tac_destroyed = 0
    for _ in range(400):
        if tac_state['state'] != 'IN_PROGRESS':
            break
        acting = next((s for s in tac_state['ships'] if s['id'] == tac_state['currentShipId']), None)
        if acting is None:
            break
        token = tokens_by_owner[acting['ownerPlayerId']]
        foes = [s for s in tac_state['ships']
                if s['side'] != acting['side'] and not s['destroyed']]
        if not foes:
            break
        aim = min(foes, key=lambda e: max(abs(e['x'] - acting['x']), abs(e['y'] - acting['y'])))
        gap = max(abs(aim['x'] - acting['x']), abs(aim['y'] - acting['y']))

        if gap > tac_state['weaponRange'] and acting['moveLeft'] > 0:
            busy = {(s['x'], s['y']) for s in tac_state['ships'] if not s['destroyed']}
            best = None
            for dx in range(-acting['moveLeft'], acting['moveLeft'] + 1):
                for dy in range(-acting['moveLeft'], acting['moveLeft'] + 1):
                    cx, cy = acting['x'] + dx, acting['y'] + dy
                    if not (0 <= cx < tac_state['width'] and 0 <= cy < tac_state['height']):
                        continue
                    if (cx, cy) in busy:
                        continue
                    value = max(abs(aim['x'] - cx), abs(aim['y'] - cy))
                    if best is None or value < best[0]:
                        best = (value, cx, cy)
            move = {'shipId': acting['id'], 'action': 'MOVE', 'x': best[1], 'y': best[2]} \
                if best else {'shipId': acting['id'], 'action': 'PASS'}
            step = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                             token, move)
        elif gap <= tac_state['weaponRange'] and not acting['fired']:
            step = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                             token, {'shipId': acting['id'], 'action': 'FIRE',
                                     'targetShipId': aim['id']})
        else:
            step = call_auth('POST', f"/api/games/{tac_id}/battles/{tac_battle['id']}/action",
                             token, {'shipId': acting['id'], 'action': 'PASS'})

        if 'ERROR' in step:
            break
        tac_destroyed += len([e for e in step['events'] if e['type'] == 'DESTROYED'])
        tac_state = step

    check('бой кончается сам, когда сторона разбита — п. 8',
          tac_state['state'] == 'FINISHED' and tac_state.get('outcome'),
          f"состояние {tac_state['state']}, исход {tac_state.get('outcome')}")
    check('корабли гибнут поимённо — п. 8', tac_destroyed > 0,
          f"уничтожено {tac_destroyed}")
    check('погибшие ушли с поля, живые остались — п. 8',
          all(x['destroyed'] or x['structure'] > 0 for x in tac_state['ships'])
          and any(not x['destroyed'] for x in tac_state['ships']),
          str([(x['designName'], x['structure'], x['destroyed']) for x in tac_state['ships']]))

    # Платформы обороны в счёт флотов не идут: они здания колонии, а не корабли (п. 11),
    # и разбитая платформа уносит своё здание, а не единицу флота. Отличаются они по
    # корпусу: у кораблей он от первого до шестого.
    survivors = {}
    for tac_ship in tac_state['ships']:
        if not tac_ship['destroyed'] and tac_ship['hullSize'] <= 6:
            survivors[tac_ship['ownerPlayerId']] = survivors.get(tac_ship['ownerPlayerId'], 0) + 1
    fleets_after = {
        tac_pid: sum(f['ships'] for f in call_auth('GET', f'/api/games/{tac_id}/fleets', tac_tok)),
        tac_pid2: sum(f['ships'] for f in call_auth('GET', f'/api/games/{tac_id}/fleets', tac_tok2)),
    }
    check('потери боя списаны с флотов — п. 8',
          fleets_after[tac_pid] == survivors.get(tac_pid, 0)
          and fleets_after[tac_pid2] == survivors.get(tac_pid2, 0),
          f"во флотах {fleets_after}, уцелело {survivors}")

    closed = call_auth('GET', f'/api/games/{tac_id}/encounters', tac_tok)
    check('встреча закрыта исходом боя — п. 8', closed == [],
          str(closed))
    # Событие боя ложится в отчёт того хода, в котором бой случился, а отчёт выдаётся
    # в конце хода — поэтому сперва ход, потом чтение. Читать раньше значило бы читать
    # отчёт прошлого хода, где боя ещё не было.
    tac_end_turn()
    report_after = call_auth('GET', f'/api/games/{tac_id}/turn/report', tac_tok)
    check('бой попал в итоги хода обеим сторонам — п. 8',
          any(e['code'] == 'BATTLE' for e in report_after['events']),
          str([e['code'] for e in report_after['events']]))

call('DELETE', f'/api/games/{tac_id}?accessToken={tac_tok}')

# --- 26. Демонстрационный бой (п. 8) ---
# Сцену боя можно посмотреть, не начиная партии: две эскадры из разных кораблей сходятся
# сами. Проверяется состав, обещанный перевес в силе и то, что бой доигрывается до конца.
demo = call('POST', '/api/reference/demo-battle')
check('демонстрация заводится без партии и пропуска — п. 8',
      'ERROR' not in demo and demo.get('battle', {}).get('state') == 'IN_PROGRESS',
      str(demo)[:200] if 'ERROR' in demo else demo['battle']['stateLabel'])

if 'ERROR' not in demo:
    demo_battle = demo['battle']
    demo_sides = {'ATTACKER': [], 'DEFENDER': []}
    for demo_ship in demo_battle['ships']:
        demo_sides[demo_ship['side']].append(demo_ship)

    check('в демонстрации не больше шести кораблей со стороны — п. 8',
          len(demo_sides['ATTACKER']) <= 6 and len(demo_sides['DEFENDER']) <= 6
          and len(demo_sides['ATTACKER']) == len(demo_sides['DEFENDER']) == 6,
          f"{len(demo_sides['ATTACKER'])} против {len(demo_sides['DEFENDER'])}")

    demo_hulls = {x['hullName'] for x in demo_battle['ships']}
    check('корабли демонстрации разных типов — п. 8', len(demo_hulls) >= 3, str(sorted(demo_hulls)))
    # Разное оружие видно по залпу: одинаковые корпуса с одинаковым оружием били бы ровно.
    demo_salvos = {(x['hullName'], x['attack']) for x in demo_battle['ships']}
    check('вооружение у кораблей разное — п. 8', len(demo_salvos) >= 6, str(sorted(demo_salvos)))

    demo_cells = [(x['x'], x['y']) for x in demo_battle['ships']]
    check('корабли демонстрации стоят по своим клеткам — п. 8',
          len(demo_cells) == len(set(demo_cells)), str(demo_cells))

    demo_gap = demo['rightPower'] / demo['leftPower'] * 100 - 100
    check('одна сторона сильнее ровно настолько, насколько обещано — п. 8',
          abs(demo_gap - demo['advantagePercent']) <= 2,
          f"сила {demo['leftPower']} против {demo['rightPower']}, перевес {demo_gap:.1f} %"
          f" при обещанных {demo['advantagePercent']} %")
    check('силы сторон видны в самом бою — п. 8',
          demo_battle['attackerPower'] == demo['leftPower']
          and demo_battle['defenderPower'] == demo['rightPower'],
          f"{demo_battle['attackerPower']} и {demo_battle['defenderPower']}")

    # Бой идёт сам: шаг за шагом, без единого хода человека.
    demo_state = demo_battle
    demo_destroyed = 0
    demo_steps = 0
    demo_kinds = set()
    demo_shots = 0
    for demo_steps in range(1, 800):
        demo_step = call('POST', f"/api/reference/demo-battle/{demo_battle['id']}/step")
        if 'ERROR' in demo_step:
            break
        demo_destroyed += len([e for e in demo_step['events'] if e['type'] == 'DESTROYED'])
        for demo_event in demo_step['events']:
            if demo_event['type'] == 'FIRE':
                demo_shots += 1
                demo_kinds.add(demo_event.get('weaponKind'))
        demo_state = demo_step
        if demo_state['state'] != 'IN_PROGRESS':
            break

    check('демонстрация доигрывается сама до конца — п. 8',
          demo_state['state'] == 'FINISHED' and demo_state.get('outcome'),
          f"за {demo_steps} шагов: {demo_state.get('outcome')}")
    check('в демонстрации гибнут корабли — п. 8', demo_destroyed > 0,
          f"уничтожено {demo_destroyed}")
    # Сцена рисует луч, снаряд и ракету по-разному, поэтому вид оружия обязан приходить
    # с каждым залпом — без него было бы не видно, кто в кого стреляет и чем.
    check('у каждого залпа известен вид оружия — п. 8',
          demo_shots > 0 and None not in demo_kinds,
          f"залпов {demo_shots}, виды {sorted(k for k in demo_kinds if k)}")
    check('в демонстрации стреляют всеми тремя видами оружия — п. 8',
          demo_kinds >= {'BEAM', 'PROJECTILE', 'MISSILE'},
          str(sorted(k for k in demo_kinds if k)))

    # Эндпоинт демонстрации открыт, поэтому он не должен двигать что попало: чужой бой
    # он отвергает (проверка «это демонстрационный бой» в DemoBattleService), а боя,
    # которого нет, не находит вовсе.
    stranger = call('POST', '/api/reference/demo-battle/00000000-0000-0000-0000-000000000000/step')
    check('шагом демонстрации нельзя подвигать чужой или несуществующий бой — п. 8',
          stranger.get('ERROR') in (403, 404),
          str(stranger.get('body', {}).get('message')))

# --- 13.5. Расселение, застава и десант (п. 4.1, п. 8, п. 12) ---
#
# Здесь проверяются правила, а не постройка: колониальный корабль стоит 500 единиц
# производства, и ждать его в прогоне пришлось бы сотню ходов. Что он строится и летает,
# показывает прогон партии ИИ (tools\ai_game.py); здесь — список стройки, цены оригинала
# и отказы, которые должен давать сервер.
expansion = call('POST', '/api/games', {
    'name': 'Расселение', 'galaxySize': 'SMALL', 'playerName': 'Колонист',
    'homeStarName': 'Дом', 'seed': 5150})
egid, etok = expansion['game']['id'], expansion['credentials']['accessToken']
call('POST', f'/api/games/{egid}/start', {'accessToken': etok})
research_shipbuilding(egid, etok)

def expansion_home():
    return [x for st in call_auth('GET', f'/api/games/{egid}/map', etok)['systems']
            for x in st['planets'] if x.get('homeworld')][0]


# До своей технологии (Power, уровень 2 «Cold Fusion») гражданского корабля в списке
# стройки нет вовсе: строить то, что ещё не изучено, нельзя — п. 4.1.
before_civil = {x['code'] for x in expansion_home()['colony']['available']}
check('до изучения гражданских кораблей их в списке стройки нет — п. 4.1',
      not ({'COLONY_SHIP', 'OUTPOST_SHIP', 'TRANSPORT'} & before_civil),
      str(sorted(before_civil)))
denied = call('POST', f"/api/games/{egid}/planets/{expansion_home()['id']}/project",
              {'accessToken': etok, 'projectCode': 'COLONY_SHIP'})
check('неизученный колониальный корабль не заложить и напрямую — п. 4.1',
      denied.get('ERROR') == 409, str(denied.get('body', {}).get('message')))

research_civil_ships(egid, etok)
ehome = expansion_home()
eprojects = {x['code']: x for x in ehome['colony']['available']}

check('колониальный корабль в списке стройки — п. 4.1', 'COLONY_SHIP' in eprojects,
      str(sorted(eprojects)))
check('цена колониального корабля — 500, как в MOO II — п. 4.1',
      eprojects.get('COLONY_SHIP', {}).get('cost') == 500,
      str(eprojects.get('COLONY_SHIP', {}).get('cost')))
check('корабль-застава в списке стройки и стоит 100 — п. 8',
      eprojects.get('OUTPOST_SHIP', {}).get('cost') == 100,
      str(eprojects.get('OUTPOST_SHIP', {}).get('cost')))
check('транспорт в списке стройки и стоит 100 — п. 12',
      eprojects.get('TRANSPORT', {}).get('cost') == 100,
      str(eprojects.get('TRANSPORT', {}).get('cost')))
check('колониальная база, если её есть куда ставить, стоит 200 — п. 4.1',
      'COLONY_BASE' not in eprojects or eprojects['COLONY_BASE']['cost'] == 200,
      str(eprojects.get('COLONY_BASE', {}).get('cost', 'селиться в системе некуда')))
check('шпион стоит 100, как в оригинале — п. 13',
      eprojects.get('SPY', {}).get('cost') == 100,
      str(eprojects.get('SPY', {}).get('cost')))

# Гражданские корабли строятся по своей твёрдой цене и в шести ячейках дизайна не стоят:
# в списке проектов империи их быть не должно — п. 8.
edesigns = call_auth('GET', f'/api/games/{egid}/ship-designs', etok)
# Проверка держится на ЯЧЕЙКЕ, а не на названиях: имена проектов — хранимый текст, и
# сверка по ним ломается от первого же переименования (так и вышло, когда имена стали
# английскими вместе с переводом справочников).
check('гражданские корабли не занимают ячеек окна дизайна — п. 8',
      all(d['slot'] >= 1 for d in edesigns),
      str([(d['slot'], d['name']) for d in edesigns]))

# Флот у империи один и боевой: высаживать ему нечего, и сервер обязан это сказать,
# а не молча основать колонию.
efleets = build_ship(egid, etok, ehome['id'])
if efleets:
    esystem = [st for st in call_auth('GET', f'/api/games/{egid}/map', etok)['systems']
               if st['id'] == efleets[0]['starSystemId']][0]
    espare = [x for x in esystem['planets'] if x['id'] != ehome['id']]
    ecivil = [x for x in efleets[0]['composition'] if x['role'] != 'WARSHIP']
    check('у боевого корабля роль боевая — п. 8',
          all(x['role'] == 'WARSHIP' and x['cargo'] == 0 for x in efleets[0]['composition'])
          and not ecivil,
          str([(x['designName'], x['role'], x['cargo']) for x in efleets[0]['composition']]))
    if espare:
        bad = call('POST', f"/api/games/{egid}/fleets/{efleets[0]['id']}/colonize",
                   {'accessToken': etok, 'targetPlanetId': espare[0]['id']})
        check('без колониального корабля колонию не основать — п. 4.1',
              bad.get('ERROR') == 409, str(bad.get('body', {}).get('message')))
        bad = call('POST', f"/api/games/{egid}/fleets/{efleets[0]['id']}/outpost",
                   {'accessToken': etok, 'targetPlanetId': espare[0]['id']})
        check('без корабля-заставы заставу не поставить — п. 8',
              bad.get('ERROR') == 409, str(bad.get('body', {}).get('message')))
        bad = call('POST', f"/api/games/{egid}/fleets/{efleets[0]['id']}/invade",
                   {'accessToken': etok, 'targetPlanetId': espare[0]['id']})
        check('десант без транспортов невозможен — п. 12',
              bad.get('ERROR') == 409, str(bad.get('body', {}).get('message')))

# --- 13.6. Партия без игрока-человека и конец партии (п. 3, п. 3.2, п. 15) ---
#
# Восемь империй ИИ играют сами: наблюдатель только объявляет конец хода. Проверяется,
# что ход при этом считается сразу (ждать некого) и что империи ИИ и правда играют —
# расселяются, а не сидят на родной звезде.
watch = call('POST', '/api/games', {
    'name': 'Наблюдение', 'galaxySize': 'SMALL', 'playerName': 'Наблюдатель',
    'observer': True, 'seed': 8080})
wgid, wtok = watch['game']['id'], watch['credentials']['accessToken']
wstart = call('POST', f'/api/games/{wgid}/start', {'accessToken': wtok})
check('в партии наблюдателя людей нет — п. 3.2',
      all(p['playerType'] == 'AI' for p in wstart['players']),
      str(sorted({p['playerType'] for p in wstart['players']})))

wturn = call('POST', f'/api/games/{wgid}/turn/end', {'accessToken': wtok})
check('ход партии без людей считается сразу — п. 11.1',
      wturn.get('advanced') is True and not wturn.get('waitingFor'),
      str(wturn.get('waitingFor')))
check('победителя у идущей партии нет — п. 3',
      wturn['state']['game'].get('winnerPlayerId') is None
      and wturn['state']['game'].get('victoryKind') is None,
      str(wturn['state']['game'].get('victoryKind')))


# --- 13.6a. Память проверяемых связок (этап 2, п. 2.16) ---
#
# Связок из полусотни сторон больше двадцати тысяч, и перебирать их незачем: смысл имеют
# считанные — те, о которых есть догадка. Догадка живёт ДО прогона и из замеров не
# восстанавливается, поэтому у неё своя память, и она обязана держать три правила: связка
# это две или три стороны, порядок в ней ничего не значит, дважды одна и та же не заводится.
combo = call('POST', '/api/balance/combinations',
             {'traits': ['cybernetic', 'industry-great'], 'note': 'проверка сквозного прогона'})
check('связка запоминается вместе с догадкой — этап 2',
      combo.get('id') is not None and combo.get('note') == 'проверка сквозного прогона',
      str(combo)[:200])
check('запомненная связка ещё не проверена — этап 2',
      combo.get('checkedAt') is None and combo.get('verdict') is None,
      str(combo.get('verdict')))
again = call('POST', '/api/balance/combinations',
             {'traits': ['industry-great', 'cybernetic'], 'note': 'та же, но задом наперёд'})
check('связка это НАБОР сторон: порядок в ней ничего не значит — этап 2',
      again.get('ERROR') == 409, str(again.get('body', again))[:200])
lonely = call('POST', '/api/balance/combinations', {'traits': ['cybernetic']})
check('связка из одной стороны не заводится — этап 2',
      lonely.get('ERROR') in (400, 409), str(lonely.get('ERROR')))
listed = call('GET', '/api/balance/combinations')
check('запомненная связка видна в списке с названиями сторон — этап 2',
      any(one['id'] == combo['id'] and 'Киборги' in one['names'] for one in listed),
      str(listed)[:200])
call('DELETE', f"/api/balance/combinations/{combo['id']}")
check('связку можно забыть — этап 2',
      all(one['id'] != combo['id'] for one in call('GET', '/api/balance/combinations')))

# Список прогонов обязан открываться при ЛЮБОМ содержимом старых записей. Связки в слепках
# меняли форму (были «первая и вторая сторона», стал набор из двух-трёх), и прочитанная
# нынешним разбором старая связка приходит с пустым составом — на этом весь список прогонов
# отвечал 500, а не одна строка. Проверка дешёвая, а ловит целый класс поломок: слепок
# старой версии.
listed_runs = call('GET', '/api/balance/runs')
check('список прогонов открывается при любых старых слепках — этап 2',
      isinstance(listed_runs, list), str(listed_runs)[:200])

# Подсадить можно и ОДНУ сторону — это не связка, а способ намерить дорогую: сторона за
# десять очков из пятнадцати попадает в случайную сборку два раза из ста, и прибор не
# отличает её цену от нуля. Подсаженная попадёт в половину сборок.
one_side = call('POST', '/api/balance/runs', {
    'galaxySize': 'SMALL', 'empires': 4, 'games': 1, 'turns': 10,
    'combination': ['creative']})
check('подсадить одну сторону можно — этап 3',
      one_side.get('combination') == ['creative'],
      str(one_side.get('combination') or one_side.get('body', one_side))[:200])
if one_side.get('id'):
    for _ in range(90):
        if call('GET', f"/api/balance/runs/{one_side['id']}").get('status') != 'RUNNING':
            break
        time.sleep(2)
# Одна подсаженная сторона в память СВЯЗОК не попадает: связка — это две или три, а ответ
# про одну лежит в приговорах ценам.
check('одна подсаженная сторона не заводит связку в памяти — этап 2',
      all(len(one['traits']) >= 2 for one in call('GET', '/api/balance/combinations')),
      str([one['traits'] for one in call('GET', '/api/balance/combinations')])[:200])

# Оракул сборок (этап 3) — второй род прогона в той же таблице. Проверяется малым прогоном:
# сборки играют поколениями с отсевом, и на выходе обязан быть ладдер с ответом на два
# условия плана — есть ли доминирующая сборка и какие стороны в верхушку не вошли.
oracle = call('POST', '/api/balance/runs', {
    'galaxySize': 'SMALL', 'empires': 4, 'games': 2, 'turns': 12,
    'kind': 'ORACLE', 'population': 8, 'seed': 4242})
check('оракул сборок заводится как прогон — этап 3',
      oracle.get('kind') == 'ORACLE' and oracle.get('population') == 8,
      str(oracle.get('kind')) + ', сборок ' + str(oracle.get('population')))
if oracle.get('id'):
    for _ in range(120):
        found = call('GET', f"/api/balance/runs/{oracle['id']}")
        if found.get('status') != 'RUNNING':
            break
        time.sleep(2)
    check('оракул доигрывает и оставляет ладдер — этап 3',
          found.get('status') == 'FINISHED' and (found.get('oracle') or {}).get('ladder'),
          f"{found.get('status')}, {found.get('failure') or ''}")
    ladder = (found.get('oracle') or {}).get('ladder') or []
    check('сборки ладдера законны: ровно бюджет — этап 3',
          all(b['budget'] == design['picks'] for b in ladder),
          str(sorted({b['budget'] for b in ladder})))
    check('оракул отвечает на оба условия плана — этап 3',
          'dominated' in (found.get('oracle') or {})
          and 'deadTraits' in (found.get('oracle') or {}),
          str(sorted((found.get('oracle') or {}).keys())))
    # Оракул сам решает, чем играют империи, поэтому мест заказа у него нет вовсе.
    check('у оракула нет заказанных мест — этап 3', not found.get('races'),
          str(found.get('races')))

# Подсаженная связка проверяется НА ЗАКАЗЕ, а не через час игры: пара из одной группы
# оставила бы долю «все сразу» пустой, и мерить было бы нечего.
refused = call('POST', '/api/balance/runs', {
    'galaxySize': 'SMALL', 'empires': 2, 'games': 1, 'turns': 10,
    'combination': ['food-good', 'food-great']})
check('связка из одной группы отвергается на заказе — этап 2, п. 2.14',
      refused.get('ERROR') == 409, str(refused.get('body', refused))[:200])

# --- 13.7. Прогон партий для балансировки (этап 0, balance-metrics-works.txt) ---
#
# Балансировка мерит ценность особенностей расы прогонами партий, и держится это на трёх
# вещах: партию можно прогнать сразу на много ходов, у партии можно снять летопись всех
# империй, и та же партия с тем же зерном повторяется до последнего числа.
bal = call('POST', '/api/games', {
    'name': 'Баланс прогон', 'galaxySize': 'SMALL', 'playerName': 'Наблюдатель',
    'observer': True, 'seed': 909, 'totalPlayers': 3})
bgid, btok = bal['game']['id'], bal['credentials']['accessToken']
check('число империй задаётся самой партией — п. 3',
      bal['game']['totalPlayers'] == 3, str(bal['game']['totalPlayers']))
call('POST', f'/api/games/{bgid}/start', {'accessToken': btok})

advanced = call('POST', f'/api/games/{bgid}/turn/advance', {'accessToken': btok, 'turns': 12})
check('прогон считает запрошенные ходы разом — этап 0',
      advanced.get('played') == 12 and advanced.get('turn') == 13,
      f"сыграно {advanced.get('played')}, ход {advanced.get('turn')}")
check('прогон говорит, чем кончилась партия — этап 0',
      advanced.get('status') == 'IN_PROGRESS' and advanced.get('winnerSlot') is None,
      str(advanced.get('status')))
too_many = call('POST', f'/api/games/{bgid}/turn/advance', {'accessToken': btok, 'turns': 5000})
check('прогон длиннее тысячи ходов не заказать — этап 0', too_many.get('ERROR') == 400,
      str(too_many.get('ERROR')))

telemetry = call_auth('GET', f'/api/games/{bgid}/telemetry', btok)
check('телеметрия отдаёт все империи партии — этап 0',
      len(telemetry['empires']) == 3 and telemetry['seed'] == 909,
      f"империй {len(telemetry['empires'])}, зерно {telemetry['seed']}")
first = telemetry['empires'][0]
check('у империи в телеметрии есть раса, строй и характер — этап 0',
      first['traits'] is not None and first['government'] and first['personality'],
      f"{first['government']}, {first['personality']}, сторон {len(first['traits'])}")
check('стартовое положение приходит ковариатами — этап 0',
      first['homeSize'] and first['homeClimate'] and first['nearbyPlanets'] >= 0
      and first['nearestRival'] > 0,
      f"мир {first['homeSize']}/{first['homeClimate']}, рядом планет {first['nearbyPlanets']},"
      f" сосед в {first['nearestRival']} пк")
check('летопись идёт по ходам и несёт мощь — этап 0',
      len(first['history']) >= 12
      and [row['turn'] for row in first['history']][:3] == [1, 2, 3]
      and all(row['might'] is not None for row in first['history']),
      f"замеров {len(first['history'])}")


def balance_fingerprint(game_id, token):
    """Отпечаток партии: места, ходы и замеры — по нему сверяются повторы."""
    snapshot = call_auth('GET', f'/api/games/{game_id}/telemetry', token)
    return [(empire['slot'], row['turn'], row['might'], row['colonies'], row['populationK'])
            for empire in sorted(snapshot['empires'], key=lambda e: e['slot'])
            for row in empire['history']]


# Повторимость — то, ради чего этап 0 и затевался: парные прогоны сравнивают расы, а не
# разную удачу. Зерно одно, партии две, летопись обязана совпасть до последнего числа.
twins = []
for name in ('Повтор 1', 'Повтор 2'):
    twin = call('POST', '/api/games', {
        'name': name, 'galaxySize': 'SMALL', 'playerName': 'Наблюдатель',
        'observer': True, 'seed': 5150, 'totalPlayers': 3})
    tgid, ttok = twin['game']['id'], twin['credentials']['accessToken']
    call('POST', f'/api/games/{tgid}/start', {'accessToken': ttok})
    call('POST', f'/api/games/{tgid}/turn/advance', {'accessToken': ttok, 'turns': 25})
    twins.append((tgid, ttok, balance_fingerprint(tgid, ttok)))

check('та же партия с тем же зерном повторяется до последнего числа — этап 0',
      twins[0][2] == twins[1][2] and len(twins[0][2]) > 0,
      f"замеров {len(twins[0][2])} и {len(twins[1][2])},"
      f" расхождений {sum(1 for a, b in zip(twins[0][2], twins[1][2]) if a != b)}")


# --- 13.8. Империи ИИ и правда играют: наука по нуждам, флот, десант (п. 15) ---
#
# Прогон балансировки показал, за что эти проверки: устремление правителя водило науку по
# любимым разделам, и четыре из шести не изучали расселение никогда — империи сидели на
# родной звезде до конца партии, боевого корабля не строили вовсе и десант не высаживали.
war = call('POST', '/api/games', {
    'name': 'Война ИИ', 'galaxySize': 'SMALL', 'playerName': 'Наблюдатель',
    'observer': True, 'seed': 424242, 'totalPlayers': 8})
wargid, wartok = war['game']['id'], war['credentials']['accessToken']
call('POST', f'/api/games/{wargid}/start', {'accessToken': wartok})
call('POST', f'/api/games/{wargid}/turn/advance', {'accessToken': wartok, 'turns': 120})

war_map = call_auth('GET', f'/api/games/{wargid}/map?revealAll=true', wartok)
war_colonies = sum(1 for st in war_map['systems'] for pl in st['planets']
                   if (pl.get('population') or 0) > 0)
war_telemetry = call_auth('GET', f'/api/games/{wargid}/telemetry', wartok)
war_fleets = sum(e['history'][-1]['fleetPower'] for e in war_telemetry['empires'] if e['history'])
war_colonies_max = max(e['history'][-1]['colonies'] for e in war_telemetry['empires'] if e['history'])

check('империи ИИ выходят за родную звезду — п. 15',
      war_colonies > 8 and war_colonies_max > 1,
      f'колоний в галактике {war_colonies}, у лучшей империи {war_colonies_max}')
check('империи ИИ держат флот и в мирное время — п. 8',
      war_fleets > 0, f'суммарная боевая сила {war_fleets}')

# Счётчики использования механик — этап 1 балансировки. Цена стороны расы измерима ровно
# настолько, насколько работает механика, к которой сторона привязана: пустой счётчик
# значит «игра её не тронула», а не «сторона слаба». Счётчики уже ЧЕТЫРЕЖДЫ молчали не
# потому, что механика спала, а потому, что запись стояла не в том месте: бой считался
# только тактический (ИИ бьётся «авто»), договор не считался вовсе, колония,
# заселённая колониальной базой, не считалась колонией, а из двух заданий агента
# считалась одна кража — саботаж уходил мимо счёта.
war_used = collections.Counter()
for empire in war_telemetry['empires']:
    for code, times in (empire.get('used') or {}).items():
        war_used[code] += times
check('телеметрия считает, чем империя пользовалась — этап 1',
      war_used['LEADER'] > 0 and war_used['COLONIZED'] > 0,
      f"лидеров {war_used['LEADER']}, колоний {war_used['COLONIZED']},"
      f" перелётов {war_used['FLIGHT']}, войн {war_used['WAR']}, боёв {war_used['BATTLE']}")
check('счётчик колоний сходится с картой — этап 1',
      war_used['COLONIZED'] + war_used['CAPTURE'] + 8 >= war_colonies,
      f'колоний на карте {war_colonies}, основано {war_used["COLONIZED"]},'
      f' захвачено {war_used["CAPTURE"]}, родных 8')
# Наземное мерило считает разность взятого и потерянного (этап 2), и потерянное — это
# отдельный счётчик у ТОГО, У КОГО ОТНЯЛИ. Сумма по партии обязана сходиться копейка в
# копейку: всякий захват это чья-то потеря, третьего не дано. Проверять это чтением кода
# нельзя — запись счётчика уже пятикратно стояла не там, где событие.
check('потерянные колонии считаются, и их ровно столько же, сколько захваченных — этап 2',
      war_used['COLONY_LOST'] == war_used['CAPTURE'],
      f'захвачено {war_used["CAPTURE"]}, потеряно {war_used["COLONY_LOST"]}')
check('высадка считается обеими дорогами — п. 12',
      war_used['INVASION'] >= war_used['CAPTURE'],
      f'высадок {war_used["INVASION"]}, из них взяли колонию {war_used["CAPTURE"]}')

call('DELETE', f'/api/games/{wargid}?accessToken={wartok}')

# Расы империй ИИ задаются прогоном (`aiEmpires` при старте): без этого курс
# «очко → сила» не измерить — сравнивать нечего, расы раздаются жребием.
seated = call('POST', '/api/games', {
    'name': 'Посадка ИИ', 'galaxySize': 'SMALL', 'playerName': 'Наблюдатель',
    'observer': True, 'seed': 7373, 'totalPlayers': 3})
sgid, stok = seated['game']['id'], seated['credentials']['accessToken']
call('POST', f'/api/games/{sgid}/start', {
    'accessToken': stok,
    'aiEmpires': [{'name': 'Подопытные', 'traits': ['gov-dictatorship', 'growth-fast']}]})
seated_empires = call_auth('GET', f'/api/games/{sgid}/telemetry', stok)['empires']
seated_first = next((e for e in seated_empires if e.get('raceName') == 'Подопытные'), None)
check('прогон сажает свою расу за империю ИИ — этап 1',
      seated_first is not None and set(seated_first['traits']) == {'gov-dictatorship', 'growth-fast'},
      str(seated_first and seated_first['traits']))
check('остальным империям достаются готовые расы — этап 1',
      all(e['traits'] for e in seated_empires),
      str([(e.get('raceName') or e.get('raceCode'), len(e['traits'])) for e in seated_empires]))
call('DELETE', f'/api/games/{sgid}?accessToken={stok}')

for tgid, ttok, _ in twins:
    call('DELETE', f'/api/games/{tgid}?accessToken={ttok}')
call('DELETE', f'/api/games/{bgid}?accessToken={btok}')


def watched_colonies():
    """Сколько колоний у всех империй партии: карта наблюдателя открыта целиком."""
    systems = call_auth('GET', f'/api/games/{wgid}/map?revealAll=true', wtok)['systems']
    return sum(1 for st in systems for pl in st['planets']
               if pl.get('ownerPlayerId') and (pl.get('population') or 0) > 0)


wbefore = watched_colonies()
for _ in range(60):
    call('POST', f'/api/games/{wgid}/turn/end', {'accessToken': wtok})
wafter = watched_colonies()
check('империи ИИ расселяются сами — п. 15', wafter > wbefore,
      f'колоний было {wbefore}, стало {wafter}')

# Стартовые системы у ВСЕХ империй одинаковы — требование хозяина проекта. Замер прогонами
# мерит сторону расы, и случайная родная система подмешивает в него удачу карты: соседний
# газовый гигант или пустая система из одного пояса астероидов двигают выработку сильнее
# иной стороны за десять очков. Разным остаётся только родной мир, и ровно настолько,
# насколько его правит раса (большой, богатый, бедный мир и климат справочной расы).
wsystems = call_auth('GET', f'/api/games/{wgid}/map?revealAll=true', wtok)['systems']
whomes = {}
for st in wsystems:
    for pl in st['planets']:
        if pl.get('homeworld'):
            whomes[st['id']] = st
            break
wshapes = set()
for st in whomes.values():
    outer = tuple(sorted(
        (pl['orbit'], pl['size'], pl['climate'], pl['minerals'])
        for pl in st['planets'] if not pl.get('homeworld')))
    wshapes.add((len(st['planets']), outer))
check('стартовые системы у всех империй одинаковы — п. 3.2',
      len(whomes) >= 2 and len(wshapes) == 1,
      f'родных систем {len(whomes)}, разных обликов {len(wshapes)}')

# Родной мир при этом отличается РОВНО настолько, насколько его правит раса. Сравнивать
# все родные миры подряд нельзя: империи ИИ играют готовыми расами MOO II, и у половины из
# них есть стороны родного мира — большой, богатый, бедный, мир артефактов. Поэтому берутся
# те империи, у которых таких сторон НЕТ: их родные миры обязаны совпасть до буквы.
HOME_TRAITS = {'world-large', 'world-rich', 'world-poor', 'world-artifacts'}
wtel = call_auth('GET', f'/api/games/{wgid}/telemetry', wtok)
wplain = {(e['homeSize'], e['homeMinerals']) for e in wtel['empires']
          if not (set(e.get('traits') or []) & HOME_TRAITS)}
check('родной мир отличается только тем, что правит раса — п. 7',
      len(wplain) == 1,
      f"империй без сторон родного мира {len([1 for e in wtel['empires'] if not (set(e.get('traits') or []) & HOME_TRAITS)])},"
      f' разных родных миров {len(wplain)}: {sorted(wplain)}')

# И орбита родного мира одна у всех — это уже следствие общего чертежа, а не расы.
worbits = {[pl for pl in st['planets'] if pl.get('homeworld')][0]['orbit']
           for st in whomes.values()}
check('родной мир у всех на одной орбите — п. 3.2', len(worbits) == 1, str(sorted(worbits)))

wpop = [pl for st in call_auth('GET', f'/api/games/{wgid}/map?revealAll=true', wtok)['systems']
        for pl in st['planets'] if (pl.get('population') or 0) > 0]
check('жителей больше, чем вмещает планета, не бывает — п. 4.1',
      all(pl['population'] <= (pl.get('colony') or {}).get('maxPopulation', pl['population'])
          for pl in wpop),
      str([(pl['name'], pl['population'], (pl.get('colony') or {}).get('maxPopulation'))
           for pl in wpop[:3]]))

call('DELETE', f'/api/games/{egid}?accessToken={etok}')
call('DELETE', f'/api/games/{wgid}?accessToken={wtok}')

# --- 13.7. Лидеры (п. 6) ---
#
# Лидер сам предлагает службу, и ждать предложения приходится ходами: жребий бросается
# каждый ход на каждый род. Поэтому проверка сперва крутит ходы, пока предложение не
# придёт, а уже потом смотрит наём, назначение и увольнение. Заодно копится казна: наём
# стоит денег, и с пустой казной сервер обязан отказать, а не нанять в долг.
lead = call('POST', '/api/games', {
    'name': 'Лидеры', 'galaxySize': 'SMALL', 'playerName': 'Наниматель',
    'homeStarName': 'Ставка', 'seed': 6060})
lgid, ltok = lead['game']['id'], lead['credentials']['accessToken']
call('POST', f'/api/games/{lgid}/start', {'accessToken': ltok})


def leaders_state():
    return call_auth('GET', f'/api/games/{lgid}/leaders', ltok)


lstate = leaders_state()
check('мест для лидеров по четыре на род, как в MOO II — п. 6',
      lstate.get('colonySlots') == 4 and lstate.get('shipSlots') == 4,
      str((lstate.get('colonySlots'), lstate.get('shipSlots'))))
check('в начале партии на службе никого нет — п. 6',
      not lstate.get('colony') and not lstate.get('ship'),
      str((lstate.get('colony'), lstate.get('ship'))))

# Ждём предложения долго и намеренно: жребий лидеров (шесть процентов за ход) бросает
# сам сервер своим `Random`, а не зерном партии, — за сорок ходов пустой ряд выпадал
# примерно раз на десять прогонов, и проверка падала не по делу. Восьмидесяти ходов
# хватает, чтобы такой ряд стал редкостью.
for _ in range(80):
    if lstate.get('offers'):
        break
    end_turn(lgid, ltok)
    lstate = leaders_state()
check('лидеры сами предлагают службу — п. 6', bool(lstate.get('offers')),
      'за сорок ходов не предложил никто')

if lstate.get('offers'):
    hero = min(lstate['offers'], key=lambda x: x['hireCost'])
    check('у предложения есть звание, цена и срок ответа — п. 6',
          bool(hero.get('rank')) and hero['hireCost'] > 0
          and hero.get('expiresTurn', 0) > hero.get('offeredTurn', -1),
          str((hero.get('rank'), hero.get('hireCost'),
               hero.get('offeredTurn'), hero.get('expiresTurn'))))
    check('способности лидера приходят пересчитанными под звание — п. 6',
          all(x.get('name') and x.get('value') is not None for x in hero.get('skills', [])),
          str([(x.get('code'), x.get('value')) for x in hero.get('skills', [])]))

    # Казна пуста — наниматься не на что, и сервер обязан сказать об этом отказом.
    if lstate['credits'] < hero['hireCost']:
        poor = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/hire", ltok)
        check('без денег лидера не нанять — п. 6', poor.get('ERROR') == 409,
              str(poor.get('body', {}).get('message')))

    for _ in range(60):
        lstate = leaders_state()
        alive = [x for x in lstate.get('offers', []) if x['hireCost'] <= lstate['credits']]
        if alive:
            break
        end_turn(lgid, ltok)

    if alive:
        hero = min(alive, key=lambda x: x['hireCost'])
        purse = lstate['credits']
        hired = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/hire", ltok)
        serving = [x for x in hired.get('colony', []) + hired.get('ship', [])
                   if x['id'] == hero['id']]
        check('нанятый лидер переходит на службу — п. 6', bool(serving),
              str([x['name'] for x in hired.get('colony', []) + hired.get('ship', [])]))
        check('плата за наём списывается сразу — п. 6',
              hired.get('credits') == purse - hero['hireCost'],
              f"было {purse}, стало {hired.get('credits')}, цена {hero['hireCost']}")
        # А ТЕПЕРЬ ТО ЖЕ САМОЕ, НО ПЕРЕСПРОСИВ СЕРВЕР. Проверка выше смотрит ответ самого
        # найма, а он собран из объекта игрока в памяти: списание там видно и тогда, когда
        # в базу оно не ушло. Ровно так и было — игрок приходит в службу отсоединённым от
        # сессии, правка казны не сохранялась, и наём выходил ДАРОМ, а проверка молчала
        # месяцами. У империи ИИ, которая нанимает изнутри хода, всё списывалось честно.
        check('и в базе казна уменьшилась, а не только в ответе — п. 6',
              leaders_state().get('credits') == purse - hero['hireCost'],
              f"было {purse}, переспросили {leaders_state().get('credits')},"
              f" цена {hero['hireCost']}")
        check('нанятый из предложений уходит — п. 6',
              all(x['id'] != hero['id'] for x in hired.get('offers', [])),
              str([x['name'] for x in hired.get('offers', [])]))
        again = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/hire", ltok)
        check('дважды одного лидера не нанять — п. 6', again.get('ERROR') == 409,
              str(again.get('body', {}).get('message')))

        # Назначение: колониального — в систему, корабельного — во флот. В родную
        # систему лидер попадает сразу, в прочие добирается пять ходов — п. 6.
        home_id = [st['id'] for st in call_auth('GET', f'/api/games/{lgid}/map', ltok)['systems']
                   for x in st['planets'] if x.get('homeworld')][0]
        kind = serving[0]['kind'] if serving else None
        if kind == 'COLONY':
            placed = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/assign",
                               ltok, {'targetId': home_id})
            spot = ([x for x in placed.get('colony', []) if x['id'] == hero['id']] or [{}])[0]
            check('колониальный лидер занимает свою систему — п. 6',
                  spot.get('systemId') == home_id and spot.get('arrivesTurn') is not None,
                  str((spot.get('systemName'), spot.get('arrivesTurn'))))
            back = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/assign",
                             ltok, {'targetId': None})
            spot = ([x for x in back.get('colony', []) if x['id'] == hero['id']] or [{}])[0]
            check('пустая цель возвращает лидера в резерв — п. 6',
                  spot.get('systemId') is None and spot.get('arrivesTurn') is None,
                  str((spot.get('systemId'), spot.get('arrivesTurn'))))
        elif kind == 'SHIP':
            nowhere = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/assign",
                                ltok, {'targetId': home_id})
            check('корабельного лидера в звёздную систему не назначить — п. 6',
                  nowhere.get('ERROR') in (404, 409),
                  str(nowhere.get('body', {}).get('message')))

        left = call_auth('POST', f"/api/games/{lgid}/leaders/{hero['id']}/dismiss", ltok)
        check('уволенный освобождает место — п. 6',
              all(x['id'] != hero['id']
                  for x in left.get('colony', []) + left.get('ship', [])),
              str([x['name'] for x in left.get('colony', []) + left.get('ship', [])]))

call('DELETE', f'/api/games/{lgid}?accessToken={ltok}')

# --- 13.8. Тактический бой с чудищем (п. 8, п. 11.1) ---
# Чудище дерётся с человеком НА ПОЛЕ, а не одной формулой: ради этой встречи игрок и
# копит флот. Проверяется вся дорога — бой заводится сам при заходе в сторожевую систему,
# чудище на поле помечено чудищем, а строка, за которой оно стоит, не считается империей
# НИГДЕ: ни в списке игроков, ни в окне «Инфо», ни в справочнике кораблей.
mon_game = call('POST', '/api/games', {'name': 'Чудище', 'playerName': 'Охотник',
                                       'galaxySize': 'SMALL', 'seed': 4242,
                                       'totalPlayers': 3, 'galacticEvents': False,
                                       'council': False})
if 'game' in mon_game:
    mgid = mon_game['game']['id']
    mtok = mon_game['credentials']['accessToken']
    mpid = mon_game['credentials']['playerId']
    call('POST', f'/api/games/{mgid}/start', {'accessToken': mtok})

    # Тела и части чудищ прячет сам справочник: иначе тело дракона встало бы в окно
    # дизайна, а когти — в список оружия, изученного с первого хода.
    mcat = call_auth('GET', f'/api/games/{mgid}/ship-designs/catalog', mtok)
    hull_codes = [h['code'] for h in mcat.get('hulls', [])]
    comp_codes = [c['code'] for c in mcat.get('components', [])]
    check('тела чудищ в окно дизайна не попадают — п. 11.1',
          not any(code.endswith('-body') for code in hull_codes), str(hull_codes))
    check('когти и шкура чудища не значатся оружием империи — п. 11.1',
          not any(code.startswith('monster-') for code in comp_codes),
          str([c for c in comp_codes if c.startswith('monster-')]))

    mmap = call('GET', f'/api/games/{mgid}/map?accessToken={mtok}&revealAll=true')
    mhome = [st for st in mmap['systems']
             if any(pl.get('ownerPlayerId') == mpid for pl in st['planets'])][0]
    mcolony = [pl for pl in mhome['planets'] if pl.get('ownerPlayerId') == mpid][0]
    guarded = [st for st in mmap['systems'] if st.get('monster')]
    check('в галактике есть системы под сторожем — п. 11.1', len(guarded) >= 1,
          f'сторожевых систем {len(guarded)}')

    def far(one, other):
        return ((one['x'] - other['x']) ** 2 + (one['y'] - other['y']) ** 2) ** 0.5

    guarded.sort(key=lambda st: far(mhome, st))
    lair = guarded[0] if guarded else None

    # Четыре фрегата: одного чудищу мало даже на залп, а нам нужен бой, а не казнь.
    frigate = [a for a in mcolony['colony']['available']
               if a['code'].startswith('SHIP:') and a['name'].endswith('Frigate')]
    if lair and frigate:
        code = frigate[0]['code']
        call('POST', f"/api/games/{mgid}/planets/{mcolony['id']}/project",
             {'accessToken': mtok, 'projectCode': code})
        for _ in range(3):
            call('POST', f"/api/games/{mgid}/planets/{mcolony['id']}/queue",
                 {'accessToken': mtok, 'projectCode': code})
        call('POST', f'/api/games/{mgid}/turn/advance', {'accessToken': mtok, 'turns': 30})

        mfleets = call_auth('GET', f'/api/games/{mgid}/fleets', mtok)
        mine = [f for f in mfleets if f.get('starSystemId') == mhome['id']] \
            if isinstance(mfleets, list) else []
        check('колония построила флот для похода на чудище',
              bool(mine) and mine[0]['ships'] >= 2,
              str([(f['systemName'], f['ships']) for f in mfleets])
              if isinstance(mfleets, list) else str(mfleets))

        if mine:
            call('POST', f"/api/games/{mgid}/fleets/{mine[0]['id']}/move",
                 {'accessToken': mtok, 'targetSystemId': lair['id']})
            fight = None
            for _ in range(12):
                call('POST', f'/api/games/{mgid}/turn/end', {'accessToken': mtok})
                open_battles = call_auth('GET', f'/api/games/{mgid}/battles', mtok)
                if isinstance(open_battles, list) and open_battles:
                    fight = open_battles[0]
                    break
            check('чудище встречает флот человека тактическим боем — п. 8, п. 11.1',
                  fight is not None and fight.get('systemName') == lair['name'],
                  str(fight)[:160] if fight else 'боя не случилось')

            if fight:
                beast = [one for one in fight['ships'] if one.get('monster')]
                check('чудище на поле помечено чудищем, а не кораблём — п. 11.1',
                      len(beast) == 1 and not beast[0]['yours'],
                      str([(one['designName'], one.get('monster')) for one in fight['ships']]))
                check('сторона чудищ подписана переводимым именем, а не служебным',
                      bool(fight.get('defenderName'))
                      and 'Space Monsters' not in fight['defenderName'],
                      str(fight.get('defenderName')))
                # Встречи у такого боя нет вовсе: чудище не спрашивает «драться ли».
                enc = call_auth('GET', f'/api/games/{mgid}/encounters', mtok)
                check('бой с чудищем обходится без встречи — п. 11.1',
                      isinstance(enc, list) and not enc, str(enc)[:120])

                # Поломка систем и самоподрыв — п. 8. Проверяется КОНТРАКТ (сцене
                # должно быть видно, что у корабля разбито) и самоподрыв, который
                # случайности не знает вовсе: нажал — корабль взорвался.
                check('сцена видит, что у корабля разбито — п. 8',
                      all('engineWrecked' in one and 'shieldWrecked' in one
                          and 'computerWrecked' in one for one in fight['ships'])
                      and all('wrecked' in gun
                              for one in fight['ships'] for gun in one['weapons']),
                      str(fight['ships'][0])[:200])

                mine_ships = [one for one in fight['ships'] if one['yours']]
                if len(mine_ships) >= 2:
                    bomb = mine_ships[0]
                    # Сосед по строю стоит вплотную: взрыв обязан его задеть.
                    neighbour = min(
                        (one for one in mine_ships[1:]),
                        key=lambda one: max(abs(one['x'] - bomb['x']), abs(one['y'] - bomb['y'])))
                    before = neighbour['structure'] + neighbour['armour']
                    boom = call('POST', f"/api/games/{mgid}/battles/{fight['id']}/action",
                                {'accessToken': mtok, 'shipId': bomb['id'],
                                 'action': 'SELF_DESTRUCT'})
                    after_blast = call_auth('GET', f"/api/games/{mgid}/battles/{fight['id']}", mtok)
                    blown = [one for one in after_blast.get('ships', [])
                             if one['id'] == bomb['id']]
                    hurt = [one for one in after_blast.get('ships', [])
                            if one['id'] == neighbour['id']]
                    check('самоподрыв уничтожает свой корабль наверняка — п. 8',
                          bool(blown) and blown[0]['destroyed'],
                          str(boom.get('message') or boom.get('ERROR') or '')[:120])
                    check('взрыв бьёт соседей, и своих тоже — п. 8',
                          bool(hurt)
                          and hurt[0]['structure'] + hurt[0]['armour'] < before,
                          f"было {before}, стало "
                          f"{hurt[0]['structure'] + hurt[0]['armour'] if hurt else '?'}")

                # Строка чудища — не империя: ни в списке игроков, ни в окне «Инфо».
                mstate = call('GET', f'/api/games/{mgid}')
                check('чудище не значится участником партии — п. 11.1',
                      len(mstate['players']) == 3
                      and all(pl['playerType'] != 'MONSTER' for pl in mstate['players']),
                      str([(pl['name'], pl['playerType']) for pl in mstate['players']]))
                minfo = call_auth('GET', f'/api/games/{mgid}/info', mtok)
                check('чудища нет и в летописи империй — п. 11.1',
                      all(e.get('name') != 'Space Monsters' for e in minfo.get('empires', [])),
                      str([e.get('name') for e in minfo.get('empires', [])]))

                # Бой доигрывается до конца: флот бьёт, пока может.
                for _ in range(400):
                    state = call_auth('GET', f"/api/games/{mgid}/battles/{fight['id']}", mtok)
                    if not isinstance(state, dict) or state.get('state') != 'IN_PROGRESS':
                        break
                    if not state.get('yourTurn'):
                        break
                    ship = [one for one in state['ships'] if one['id'] == state.get('currentShipId')]
                    foes = [one for one in state['ships']
                            if one['side'] != state['yourSide'] and not one['destroyed']]
                    if not ship or not foes:
                        break
                    ship, foe = ship[0], foes[0]
                    reach = max(abs(foe['x'] - ship['x']), abs(foe['y'] - ship['y']))
                    if reach <= (ship.get('weaponRange') or 8):
                        step = {'shipId': ship['id'], 'action': 'FIRE', 'targetShipId': foe['id']}
                    else:
                        step = {'shipId': ship['id'], 'action': 'MOVE',
                                'x': ship['x'] + (1 if foe['x'] > ship['x'] else -1)
                                * min(ship.get('moveLeft', 1), abs(foe['x'] - ship['x'])),
                                'y': ship['y'] + (1 if foe['y'] > ship['y'] else -1)
                                * min(ship.get('moveLeft', 1), abs(foe['y'] - ship['y']))}
                    answer = call('POST', f"/api/games/{mgid}/battles/{fight['id']}/action",
                                  dict(step, accessToken=mtok))
                    if answer.get('ERROR'):
                        # Ход не прошёл (клетка занята, шагов не осталось) — пропускаем
                        # ход этим кораблём, как поступил бы игрок. Без этого бой
                        # оставался бы недоигранным, и проверка врала бы о поломке.
                        answer = call('POST', f"/api/games/{mgid}/battles/{fight['id']}/action",
                                      {'accessToken': mtok, 'shipId': ship['id'], 'action': 'PASS'})
                        if answer.get('ERROR'):
                            break
                done = call_auth('GET', f"/api/games/{mgid}/battles/{fight['id']}", mtok)
                check('бой с чудищем доигрывается до конца — п. 8',
                      done.get('state') == 'FINISHED' and bool(done.get('outcome')),
                      str(done.get('state')) + ' ' + str(done.get('outcome'))[:100])
                after = call('GET', f'/api/games/{mgid}/map?accessToken={mtok}&revealAll=true')
                lair_now = [st for st in after['systems'] if st['id'] == lair['id']][0]
                beaten = [one for one in done.get('ships', []) if one.get('monster')]
                # Судьбу системы решает исход боя, а не жребий: уцелел сторож — он на
                # карте, убит — системы больше никто не стережёт.
                check('исход боя решает судьбу системы — п. 11.1',
                      bool(beaten)
                      and (not beaten[0]['destroyed']) == bool(lair_now.get('monster')),
                      f"сторож {'погиб' if beaten and beaten[0]['destroyed'] else 'уцелел'}, "
                      f"на карте {lair_now.get('monster')}")
    call('DELETE', f'/api/games/{mgid}?accessToken={mtok}')

# --- 13.9. Выборы Высшего совета (п. 3) ---
# Совет вернулся в игру с тремя оговорками, и проверяются именно они: он не собирается
# раньше пятидесятого хода, не собирается в поединке двоих и не собирается вовсе там, где
# его выключили. Числа правил закрыты юнит-тестами (CouncilRulesTest), а здесь живая
# партия: голоса записаны, счёт сходится, а избрание согласовано с двумя третями.
council_game = call('POST', '/api/games', {'name': 'Совет', 'playerName': 'Наблюдатель',
                                           'galaxySize': 'SMALL', 'observer': True,
                                           'seed': 909090, 'totalPlayers': 3,
                                           'galacticEvents': False})
if 'game' in council_game:
    council_id = council_game['game']['id']
    council_tok = council_game['credentials']['accessToken']
    call('POST', f'/api/games/{council_id}/start', {'accessToken': council_tok})

    early = call('GET', f'/api/games/{council_id}/council?accessToken={council_tok}')
    check('до пятидесятого хода совет не собирается — п. 3',
          not early or 'turn' not in early, str(early)[:120])

    call('POST', f'/api/games/{council_id}/turn/advance',
         {'accessToken': council_tok, 'turns': 52})
    session = call('GET', f'/api/games/{council_id}/council?accessToken={council_tok}')
    check('совет собрался и записал голоса — п. 3',
          isinstance(session, dict) and session.get('turn', 0) >= 50
          and len(session.get('voters', [])) >= 3,
          str(session)[:160])

    if isinstance(session, dict) and session.get('voters'):
        weights = sum(one['weight'] for one in session['voters'])
        check('всего голосов — это население всех империй',
              weights == session['totalVotes'],
              f"сумма {weights}, в заголовке {session['totalVotes']}")
        needed = (session['totalVotes'] * 2 + 2) // 3
        check('для избрания нужны две трети голосов, округлённые вверх — п. 3',
              session['requiredVotes'] == needed,
              f"нужно {session['requiredVotes']}, две трети {needed}")
        check('кандидатов ровно двое и оба голосуют за себя — п. 3',
              len(session['candidates']) == 2
              and all(one['choicePlayerId'] == one['playerId'] for one in session['candidates']),
              str([(one['name'], one['choicePlayerId'] == one['playerId'])
                   for one in session['candidates']]))
        tally = {}
        for one in session['voters']:
            if one.get('choicePlayerId'):
                tally[one['choicePlayerId']] = tally.get(one['choicePlayerId'], 0) + one['weight']
        elected = session.get('electedPlayerId')
        check('избран ровно тот, кто набрал две трети, — и никто иной — п. 3',
              (elected is None and all(v < session['requiredVotes'] for v in tally.values()))
              or (elected is not None and tally.get(elected, 0) >= session['requiredVotes']),
              f'избран {elected}, счёт {tally}, нужно {session["requiredVotes"]}')

    # Отказ подчиниться — п. 3. Совет собирается раз в двадцать пять ходов, и с первого
    # раза две трети набираются не всегда, поэтому партия играется дальше до первого
    # избрания. Блок под условием был бы скрытым пропуском: если правитель не избран и за
    # четыреста ходов — это провал проверки, а не повод молча её не делать.
    #
    # Число кругов ИЗМЕРЕНО, а не выбрано на глаз: на этом зерне правитель избирается на
    # 350-м ходу (замер 23.09.2026, после перевода оружия на числа оригинала — прежние
    # двенадцать кругов упирались в 325-й ход и проваливались, хотя механика цела).
    elected = None
    for _ in range(16):
        session = call('GET', f'/api/games/{council_id}/council?accessToken={council_tok}')
        elected = session.get('electedPlayerId') if isinstance(session, dict) else None
        if elected:
            break
        call('POST', f'/api/games/{council_id}/turn/advance',
             {'accessToken': council_tok, 'turns': 25})
    check('рано или поздно совет избирает правителя галактики — п. 3', elected is not None,
          str(session)[:160])

    if elected:
        finished = call('GET', f'/api/games/{council_id}')
        check('избрание кончает партию победой советом — п. 3',
              finished.get('game', {}).get('status') == 'FINISHED'
              and finished.get('game', {}).get('victoryKind') == 'COUNCIL',
              str(finished.get('game', {}))[:180])

        # Отказ предлагается ПРОИГРАВШЕМУ, и наблюдатель этой партии сам бывает избранным:
        # его империей играет ИИ, и соседи любят её не меньше прочих. Поэтому проверяются
        # обе половины правила — какая именно выпала, видно по избранному, и утверждается
        # в любом случае что-то своё. Война отказника проверена вживую и закрыта
        # юнит-тестами правил; сыграть её прогоном нельзя, не подставив выборы руками.
        mine = council_game['credentials']['playerId']
        if elected == mine:
            denied = call_auth('POST', f'/api/games/{council_id}/council/refuse', council_tok)
            check('избранный не отказывается от собственного избрания — п. 3',
                  session.get('canRefuse') is False and denied.get('ERROR') == 409,
                  f"canRefuse={session.get('canRefuse')}, отказ={str(denied)[:120]}")
        else:
            check('проигравшему предлагают не подчиниться — п. 3',
                  session.get('canRefuse') is True and session.get('refused') is False,
                  f"canRefuse={session.get('canRefuse')}, refused={session.get('refused')}")
            refused = call_auth('POST', f'/api/games/{council_id}/council/refuse', council_tok)
            check('отказ записан, и второго предложения не будет — п. 3',
                  refused.get('refused') is True and refused.get('canRefuse') is False,
                  str(refused)[:160])
            alive = call('GET', f'/api/games/{council_id}')
            check('после отказа партия оживает: победы больше нет — п. 3',
                  alive.get('game', {}).get('status') == 'IN_PROGRESS'
                  and not alive.get('game', {}).get('winnerPlayerId'),
                  str(alive.get('game', {}))[:180])
            # Война объявлена ровно теми, кто голосовал за избранного: воздержавшиеся
            # остаются в стороне, а строка отношений заводится даже без знакомства —
            # приговор совета галактический.
            supporters = [one for one in session['voters']
                          if one.get('choicePlayerId') == elected and one['playerId'] != mine]
            wars = call_auth('GET', f'/api/games/{council_id}/diplomacy', council_tok)
            at_war = [r for r in wars if r.get('stance') == 'WAR'] if isinstance(wars, list) else []
            check('голосовавшие за избранного объявили отказнику войну — п. 3',
                  len(at_war) >= len(supporters) and len(at_war) > 0,
                  f'войн {len(at_war)}, за избранного голосовало {len(supporters)}')
            twice = call_auth('POST', f'/api/games/{council_id}/council/refuse', council_tok)
            check('отказаться дважды нельзя — п. 3', twice.get('ERROR') == 409,
                  str(twice)[:160])
    call('DELETE', f'/api/games/{council_id}?accessToken={council_tok}')

# Выключенный совет — это не украшение настройки, а защита замеров: балансовый прогон
# играет партию на заданное число ходов, и избрание обрывало бы её раньше срока.
off_game = call('POST', '/api/games', {'name': 'Совет выключен', 'playerName': 'Наблюдатель',
                                       'galaxySize': 'SMALL', 'observer': True,
                                       'seed': 909091, 'totalPlayers': 3,
                                       'galacticEvents': False, 'council': False})
if 'game' in off_game:
    off_id = off_game['game']['id']
    off_tok = off_game['credentials']['accessToken']
    call('POST', f'/api/games/{off_id}/start', {'accessToken': off_tok})
    call('POST', f'/api/games/{off_id}/turn/advance', {'accessToken': off_tok, 'turns': 52})
    off_session = call('GET', f'/api/games/{off_id}/council?accessToken={off_tok}')
    check('в партии с выключенным советом выборов не случается — п. 3',
          not off_session or 'turn' not in off_session, str(off_session)[:120])
    # Отказываться не от чего там, где никого не избирали: отказ оживляет партию, и без
    # этой проверки им можно было бы снять чужую победу покорением.
    nothing = call_auth('POST', f'/api/games/{off_id}/council/refuse', off_tok)
    check('без избрания отказываться не от чего — п. 3', nothing.get('ERROR') == 409,
          str(nothing)[:120])
    call('DELETE', f'/api/games/{off_id}?accessToken={off_tok}')

# --- 14. Удаление партии ---
bad = call('DELETE', f'/api/games/{gid}?accessToken=nosuchtoken')
check('удаление чужой партии запрещено', bad.get('ERROR') in (403, 400))
call('DELETE', f'/api/games/{gid}?accessToken={tok}')
check('партия удалена', call('GET', f'/api/games/{gid}').get('ERROR') == 404)
call('DELETE', f'/api/games/{gid2}?accessToken={tok2}')
call('DELETE', f"/api/saves/{sv['id']}")

# Уборка партий конструктора расы. Раньше её не сделать: `plain` служит образцом
# сравнения до самого раздела флотов. Позже — тоже: следующий раздел нарочно исчерпывает
# предел частоты запросов, и удаления начинали получать 429, отчего одна-две партии
# каждый прогон оставались в базе.
for race_id, race_token in race_games:
    call('DELETE', f'/api/games/{race_id}?accessToken={race_token}')

# --- 15. Пределы для сервера в интернете (п. 3.1) ---
# Стоит последним разделом намеренно: обе проверки нарочно упираются в предел и оставляют
# его исчерпанным. Всё, что идёт после них, получало бы отказы не по делу.

# Регистраций с адреса — счётное число: каждая шлёт письмо на чужую почту. Считаются
# заведённые записи, поэтому проверка их и заводит, пока не упрётся.
flood = []
for i in range(14):
    stamp = 'naplyv%d-%d@example.test' % (int(time.time()), i)
    answer = register({'email': stamp, 'name': 'Наплыв', 'password': 'orion-2026'},
                      expect_limit=True)
    flood.append(answer.get('ERROR') or 200)
    if answer.get('ERROR') == 429:
        break
check('поток регистраций с одного адреса упирается в предел — п. 3.1', 429 in flood, str(flood))

# Выдуманный пропуск не даёт своего ведра: ведро адреса тратится всегда. Раньше предел
# снимался одной строкой — на каждый запрос новый пропуск, и на каждый пропуск полное
# ведро. Бьём в несколько потоков: последовательный клиент до предела адреса не дотянется.
burst_codes = {}
burst_lock = threading.Lock()


def burst_worker():
    for _ in range(40):
        # На каждый запрос свой выдуманный пропуск — та самая строка, которой раньше
        # снимался предел: непроверенный заголовок давал ключ, а ключ — новое ведро.
        request = urllib.request.Request(BASE + '/api/reference/galaxy-sizes',
                                         headers={'X-Access-Token': uuid.uuid4().hex})
        try:
            with urllib.request.urlopen(request) as answer:
                status, retry = answer.status, ''
        except urllib.error.HTTPError as error:
            status, retry = error.code, error.headers.get('Retry-After', '')
        with burst_lock:
            burst_codes[status] = burst_codes.get(status, 0) + 1
            if status == 429:
                burst_codes['retry_after'] = retry


burst_threads = [threading.Thread(target=burst_worker) for _ in range(20)]
[t.start() for t in burst_threads]
[t.join() for t in burst_threads]
check('наплыв запросов с одного адреса упирается в предел частоты',
      burst_codes.get(429, 0) > 0, str(burst_codes))
check('предел частоты называет, сколько ждать',
      str(burst_codes.get('retry_after', '')).isdigit(), str(burst_codes.get('retry_after')))

# СВОИ УЧЁТНЫЕ ЗАПИСИ ПРОГОН СНОСИТ ЗДЕСЬ — последним делом, когда все проверки уже
# отработали: до этой строки они ещё нужны (вход, повторная отправка письма, подбор
# пароля, наплыв). Убирается тем же правилом, что и в начале, — по тестовому домену.
own_accounts = sweep_accounts()
# Список записей приходит отказом, когда прав нет или предел частоты исчерпан (второй
# прогон в тот же час). Отказ обязан проваливать ОДНУ проверку, а не ронять прогон
# целиком: дважды подряд он падал здесь, уже отработав все пятьсот проверок.
accounts_now = call('GET', '/api/auth/accounts')
accounts_now = accounts_now if isinstance(accounts_now, list) else []
still_there = [one for one in accounts_now
               if isinstance(one, dict)
               and str(one.get('login', '')).lower().endswith(TEST_MAIL_DOMAIN)]
check('прогон убрал свои учётные записи — уборка за собой',
      not still_there, f'снесено {own_accounts}, осталось {len(still_there)}')
changes.append(f'убрано своих учётных записей: {own_accounts}')

passed = len(report) - len(failures)
spent_total = sum(row[1] for row in timing.values())

# ОТЧЁТ КОРОТКИЙ И ИЗ ТРЁХ ЧАСТЕЙ — решение хозяина проекта 18.09.2026: входные параметры,
# результат и то, что прогон изменил на сервере. Строки «OK | такая-то проверка» в файл
# больше не идут: их пятьсот, читать их незачем, и за ними терялось единственное, что
# нужно, — провалы. Провалившиеся проверки печатаются ПОЛНОСТЬЮ, с пояснением: провал и
# есть результат прогона, а остальное — то, как он к нему пришёл.
summary = []
summary.append('СКВОЗНОЙ ПРОГОН — ' + time.strftime('%Y-%m-%d %H:%M:%S'))
summary.append('')
summary.append('ВХОДНЫЕ ПАРАМЕТРЫ')
summary.append('  сервер             ' + BASE)
summary.append('  начат              ' + time.strftime('%H:%M:%S', time.localtime(run_started)))
summary.append('  длился             %.1f мин' % ((time.time() - run_started) / 60))
summary.append('  python             ' + sys.version.split()[0])
summary.append('  пульт балансировки ' + ('занят прогоном' if running_run else 'свободен'))
summary.append('  подробный вывод    ' + ('включён' if trace else 'выключен') + ' (MOO3_TRACE)')
summary.append('')
summary.append('РЕЗУЛЬТАТ')
summary.append('  проверок           %d' % len(report))
summary.append('  пройдено           %d' % passed)
summary.append('  провалено          %d' % len(failures))
summary.append('  время в запросах   %.1f с' % spent_total)
if failures:
    summary.append('')
    summary.append('  ПРОВАЛИЛИСЬ:')
    for line in report:
        if line.startswith('ПРОВАЛ'):
            summary.append('    ' + line.split('|', 1)[1].strip())
summary.append('')
summary.append('ЧТО ИЗМЕНЕНО ПРОГОНОМ НА СЕРВЕРЕ')
for line in changes:
    summary.append('  ' + line)

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'regress-report.txt')
io.open(out, 'w', encoding='utf-8').write(chr(10).join(summary) + chr(10))
print('проверок %d, провалено %d; отчёт: %s' % (len(report), len(failures), out))
