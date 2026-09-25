"""
Балансовый прогон партий — этап 0 плана (balance-metrics-works.txt).

Заводит партии по заданию, прогоняет их ходы, снимает телеметрию и убирает за собой.
Телеметрия ложится построчным JSONL в tools/balance/telemetry.jsonl: строка на
«партия — империя — ход». Это единственный вход будущего анализа, отчёт человеку
печатается из него же.

    python tools\\balance_run.py                       — 4 партии по 120 ходов, 2 потока
    python tools\\balance_run.py --games 40 --turns 150 --workers 4
    python tools\\balance_run.py --check-determinism    — та же партия дважды, сверка

Партии заводятся под учётной записью администратора (admin.txt) и удаляются в конце —
накапливать их в базе нельзя, на этом уже погорели (в базе набралось 830 штук).

Печать по-русски на консоли Windows падает без перенастройки вывода — она первой строкой,
как в regress.py и ai_game.py.
"""
import argparse
import io
import json
import os
import re
import sys
import random
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:8080'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, 'tools', 'balance')
DEFAULT_OUT = os.path.join(OUT_DIR, 'telemetry.jsonl')
REPORT = os.path.join(OUT_DIR, 'balance-run-report.txt')
#: Партии, заведённые прогоном, — по строке «идентификатор пропуск» на каждую.
#: Нужен потому, что прерванный прогон до своей уборки не доходит, а пропуск создателя
#: нигде, кроме базы, не хранится: по одному API брошенную партию уже не удалить.
#: Следующий прогон подметает оставленное этим.
LEDGER = os.path.join(OUT_DIR, 'running.txt')

report = []
report_lock = threading.Lock()


def say(line=''):
    print(line)
    with report_lock:
        report.append(line)


def call(method, path, body=None, headers=None, timeout=900):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    head = {'Content-Type': 'application/json'}
    head.update(headers or {})
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=head)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        return {'ERROR': error.code, 'body': error.read().decode('utf-8')[:400]}
    except urllib.error.URLError as error:
        return {'ERROR': 0, 'body': str(error.reason)}


COSTS = {}


def load_costs():
    """Цены особенностей из справочника сервера: по ним считается бюджет сборки."""
    design = call('GET', '/api/reference/race-traits')
    if isinstance(design, dict) and 'ERROR' in design:
        raise SystemExit('справочник особенностей не отдан: %s' % design.get('body'))
    for group in design['groups']:
        for option in group['options']:
            COSTS[option['code']] = option['picks']
    return design


def remember(game_id, token):
    """Записывает партию в список заведённых — до того, как с ней что-то делают."""
    with report_lock:
        with io.open(LEDGER, 'a', encoding='utf-8') as ledger:
            ledger.write('%s %s%s' % (game_id, token, chr(10)))


def sweep():
    """
    Убирает партии, брошенные прерванным прогоном.

    Прогон удаляет свои партии сам, но убитый на полпути — не успевает, и база копит
    мусор, которого потом не удалить: пропуск создателя выдаётся один раз при заведении
    партии. Поэтому список ведётся файлом, и подметает его следующий прогон.
    """
    if not os.path.exists(LEDGER):
        return
    left = [line.split() for line in io.open(LEDGER, encoding='utf-8') if line.split()]
    removed = 0
    for game_id, token in left:
        answer = call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token), timeout=60)
        # 404 — партия уже убрана самим прогоном: это обычное дело, а не отказ.
        if not (isinstance(answer, dict) and 'ERROR' in answer):
            removed += 1
    io.open(LEDGER, 'w', encoding='utf-8').write('')
    if removed:
        say('Убрано партий от прерванного прогона: %d' % removed)


def admin_token():
    """Пропуск администратора: партии заводятся под ним, как и в сквозном прогоне."""
    password = None
    admin = os.path.join(ROOT, 'admin.txt')
    if not os.path.exists(admin):
        raise SystemExit('нет admin.txt — сервер ещё ни разу не запускался?')
    for line in io.open(admin, encoding='utf-8'):
        match = re.match(r'^\s*(?:пароль|password)\s*:\s*(\S+)', line, re.IGNORECASE)
        if match:
            password = match.group(1)
    signed = call('POST', '/api/auth/login', {'login': 'admin', 'password': password})
    if 'ERROR' in signed:
        raise SystemExit('администратор не вошёл: %s' % signed.get('body'))
    return signed['token']


def play(account, seed, turns, size, name, empires=None, designs=None):
    """
    Одна партия: завести, прогнать ходы, снять телеметрию, удалить.

    Первая сборка списка достаётся империи наблюдателя, остальные — империям ИИ. Империю
    наблюдателя ведёт тот же ИИ (партия заводится с observer), но раса ей назначается при
    создании партии, а не при старте: без этого одно место из шести играло бы готовой
    расой за десять очков, и в замере курса «очко → сила» точка десяти очков оказывалась
    бы наполовину собрана вручную.
    """
    # СОВЕТ ВЫКЛЮЧЕН: прогон играет партию на заданное число ходов и читает её летопись,
    # а избранный Высшим советом правитель обрывает партию раньше срока — партии выходили
    # бы разной длины, и сравнивать их было бы нельзя (п. 3).
    request = {'name': name, 'playerName': 'Наблюдатель', 'galaxySize': size,
               'observer': True, 'seed': seed, 'council': False}
    if empires:
        request['totalPlayers'] = empires
    if designs:
        request['raceName'] = designs[0]['name']
        request['raceTraits'] = designs[0]['traits']
    created = call('POST', '/api/games', request, {'X-Account-Token': account})
    if 'ERROR' in created:
        return {'seed': seed, 'error': created.get('body')}

    game_id = created['game']['id']
    token = created['credentials']['accessToken']
    remember(game_id, token)
    start = {'accessToken': token}
    if designs:
        start['aiEmpires'] = designs[1:]
    started = call('POST', '/api/games/%s/start' % game_id, start)
    if isinstance(started, dict) and 'ERROR' in started:
        call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))
        return {'seed': seed, 'error': started.get('body')}

    started_at = time.time()
    advanced = call('POST', '/api/games/%s/turn/advance' % game_id,
                    {'accessToken': token, 'turns': turns})
    if 'ERROR' in advanced:
        call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))
        return {'seed': seed, 'error': advanced.get('body')}

    telemetry = call('GET', '/api/games/%s/telemetry?accessToken=%s' % (game_id, token))
    call('DELETE', '/api/games/%s?accessToken=%s' % (game_id, token))
    if 'ERROR' in telemetry:
        return {'seed': seed, 'error': telemetry.get('body')}

    telemetry['seconds'] = round(time.time() - started_at, 1)
    telemetry['played'] = advanced['played']
    return telemetry


def rows(telemetry):
    """Разворачивает слепок партии в строки «партия — империя — ход»."""
    out = []
    for empire in telemetry['empires']:
        head = {
            'game': telemetry['gameId'],
            'seed': telemetry['seed'],
            'galaxy': telemetry['galaxySize'],
            'stars': telemetry['starCount'],
            'turns': telemetry['turn'],
            'status': telemetry['status'],
            'winner_slot': telemetry.get('winnerSlot'),
            'victory': telemetry.get('victoryKind'),
            'slot': empire['slot'],
            'race': empire.get('raceCode'),
            'race_name': empire.get('raceName'),
            'traits': empire.get('traits') or [],
            'government': empire.get('government'),
            'personality': empire.get('personality'),
            'objective': empire.get('objective'),
            'ai': empire.get('ai'),
            'home_size': empire.get('homeSize'),
            'home_climate': empire.get('homeClimate'),
            'home_minerals': empire.get('homeMinerals'),
            'nearby_planets': empire.get('nearbyPlanets'),
            'nearest_rival': empire.get('nearestRival'),
            # Чем империя за партию пользовалась — п. 6 плана: без этого ноль в замере
            # читается как «сторона слаба», хотя значит «механика не сработала».
            'used': empire.get('used') or {},
            'budget': sum(COSTS.get(code, 0) for code in (empire.get('traits') or [])),
        }
        for row in empire['history']:
            line = dict(head)
            line.update({
                'turn': row['turn'],
                'population_k': row['populationK'],
                'colonies': row['colonies'],
                'buildings': row['buildings'],
                'production': row['production'],
                'research': row['research'],
                'fleet_power': row['fleetPower'],
                'technologies': row['technologies'],
                'credits': row['credits'],
                'might': row['might'],
            })
            out.append(line)
    return out


def fingerprint(telemetry):
    """Отпечаток партии: по нему сверяются два прогона одного зерна."""
    marks = []
    for empire in sorted(telemetry['empires'], key=lambda e: e['slot']):
        for row in empire['history']:
            marks.append((empire['slot'], row['turn'], row['might'], row['colonies'],
                          row['populationK'], row['research'], row['fleetPower']))
    return marks


def determinism(account, seed, turns, size, empires=None):
    """Та же партия дважды: парные прогоны балансировки держатся на этом."""
    say('Проверка повторимости: зерно %d, %d ходов' % (seed, turns))
    first = play(account, seed, turns, size, 'Повтор 1', empires)
    second = play(account, seed, turns, size, 'Повтор 2', empires)
    if 'error' in first or 'error' in second:
        say('  прогон не удался: %s' % (first.get('error') or second.get('error')))
        return False

    left, right = fingerprint(first), fingerprint(second)
    if left == right:
        say('  совпало полностью: %d замеров' % len(left))
        return True

    say('  РАСХОЖДЕНИЕ: замеров %d и %d' % (len(left), len(right)))
    for index, (one, two) in enumerate(zip(left, right)):
        if one != two:
            say('  первое расхождение на замере %d:' % index)
            say('    место %d, ход %d: мощь %s против %s, колоний %s против %s,'
                ' жителей %s против %s, науки %s против %s, флот %s против %s'
                % (one[0], one[1], one[2], two[2], one[3], two[3],
                   one[4], two[4], one[5], two[5], one[6], two[6]))
            break
    return False


def random_build(design, budget, random, anti_budget=0):
    """
    Случайная законная сборка ровно на заданный бюджет — этап 1, курс «очко → сила»;
    с продажей слабостей — п. 2.1 этапа 2.

    Из группы без пометки multiple берётся не больше одной стороны: правила конструктора
    для прогона те же, что для игрока.

    Бесплатное добирается в конце — там, где группа осталась незанятой. Так в сборке
    всегда есть строй: в конструкторе MOO II строй есть у каждой расы, диктатура просто
    не стоит ничего. Без этого сборка на ноль очков оказывалась расой вовсе без строя —
    состоянием, в котором игрок не бывает, и мерить относительно него было бы нечестно.

    `anti_budget` — потолок возврата слабостями. Ноль значит «только плюсовые стороны»:
    так меряется курс «очко → сила», где сравниваются именно траты. Больше нуля — сборка
    сперва решает, СКОЛЬКО вернуть, и притом ровно: жадный набор брал бы слабости всегда
    (они ведь только освобождают очки) и упирался бы в потолок в каждой сборке, а замер
    тогда сравнивал бы не «со слабостью и без», а «с этой слабостью и с той». Без продажи
    слабостей минусовая половина таблицы не попадает в замер ни разу.
    """
    options, weak, free = [], [], []
    for group in design['groups']:
        for option in group['options']:
            entry = (group['code'], bool(group.get('multiple')), option)
            if option['picks'] > 0:
                options.append(entry)
            elif option['picks'] < 0:
                weak.append(entry)
            else:
                free.append((group['code'], option))

    def with_free(chosen, taken_groups):
        return [option['code'] for option in chosen] + [
            option['code'] for group_code, option in free if group_code not in taken_groups]

    def fill(source, target, chosen, taken_groups):
        """Набирает ровно target очков (по модулю); возвращает, получилось ли."""
        random.shuffle(source)
        total = 0
        for group_code, multiple, option in source:
            if abs(total + option['picks']) > abs(target):
                continue
            if not multiple and group_code in taken_groups:
                continue
            if any(code in [c['code'] for c in chosen] for code in option.get('excludes') or []):
                continue
            if any(option['code'] in (c.get('excludes') or []) for c in chosen):
                continue
            chosen.append(option)
            taken_groups.add(group_code)
            total += option['picks']
            if total == target:
                return True
        return False

    for _ in range(200):
        refund = random.randint(0, anti_budget) if anti_budget > 0 else 0
        chosen, taken_groups = [], set()
        if refund > 0 and not fill(weak, -refund, chosen, taken_groups):
            continue
        if budget + refund == 0:
            return with_free(chosen, taken_groups)
        if fill(options, budget + refund, chosen, taken_groups):
            return with_free(chosen, taken_groups)
    return None


def curve(account, options, design):
    """
    Курс «очко → сила» — этап 1.

    В каждой партии империи получают сборки разного бюджета, и сравниваются они внутри
    одной галактики: так из сравнения уходит главный источник разброса — щедрость
    стартового угла. Между партиями бюджеты перетасовываются по местам, чтобы удачное
    место не досталось одному и тому же бюджету дважды.
    """
    budgets = [int(x) for x in options.budgets.split(',')]
    say('Курс «очко → сила»: бюджеты %s, партий %d по %d ходов, империй %d'
        % (budgets, options.games, options.turns, options.empires or len(budgets)))

    empires = options.empires or len(budgets)
    jobs = []
    for index in range(options.games):
        seed = options.seed + index
        generator = random.Random(seed)
        order = budgets[index % len(budgets):] + budgets[:index % len(budgets)]
        designs = []
        for slot in range(empires):
            budget = order[slot % len(order)]
            traits = random_build(design, budget, generator) or []
            designs.append({'name': 'Бюджет %d' % budget, 'traits': traits})
        jobs.append((seed, designs))

    written = 0
    with io.open(options.out, 'w', encoding='utf-8') as sink:
        with ThreadPoolExecutor(max_workers=options.workers) as pool:
            futures = [pool.submit(play, account, seed, options.turns, options.size,
                                   'Курс %d' % seed, empires, designs)
                       for seed, designs in jobs]
            for future in futures:
                telemetry = future.result()
                if 'error' in telemetry:
                    say('  отказ: %s' % telemetry['error'])
                    continue
                lines = rows(telemetry)
                for line in lines:
                    sink.write(json.dumps(line, ensure_ascii=False) + chr(10))
                written += len(lines)
                say('  зерно %s: ходов %d, замеров %d, %.1f с'
                    % (telemetry['seed'], telemetry['played'], len(lines), telemetry['seconds']))
    say()
    say('Записано замеров: %d в %s' % (written, options.out))


def main():
    parser = argparse.ArgumentParser(description='Балансовый прогон партий (этап 0)')
    parser.add_argument('--games', type=int, default=4, help='сколько партий сыграть')
    parser.add_argument('--turns', type=int, default=120, help='ходов в партии')
    parser.add_argument('--size', default='SMALL', help='размер галактики')
    parser.add_argument('--workers', type=int, default=2, help='сколько партий разом')
    parser.add_argument('--empires', type=int, default=None,
                        help='сколько империй в партии (2..8); пусто — как у сервера')
    parser.add_argument('--seed', type=int, default=1000, help='зерно первой партии')
    parser.add_argument('--out', default=DEFAULT_OUT, help='куда писать JSONL')
    parser.add_argument('--check-determinism', action='store_true',
                        help='сыграть одну партию дважды и сверить')
    parser.add_argument('--curve', action='store_true',
                        help='курс «очко → сила»: империи получают сборки разного бюджета')
    parser.add_argument('--budgets', default='0,2,4,6,8,10',
                        help='какие бюджеты сравнивать в режиме --curve')
    options = parser.parse_args()

    # Пустой список открытых партий — это «сервер жив», а не «нет ответа»: проверять надо
    # именно признак отказа, иначе пустая галактика читается как упавший сервер.
    alive = call('GET', '/api/games?openOnly=true')
    if isinstance(alive, dict) and 'ERROR' in alive:
        raise SystemExit('сервер не отвечает на %s — поднимите его run.cmd' % BASE)

    account = admin_token()
    os.makedirs(OUT_DIR, exist_ok=True)
    sweep()
    design = load_costs()

    if options.curve:
        curve(account, options, design)
        io.open(REPORT, 'w', encoding='utf-8').write(chr(10).join(report) + chr(10))
        raise SystemExit(0)

    if options.check_determinism:
        ok = determinism(account, options.seed, options.turns, options.size, options.empires)
        io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(report) + '\n')
        raise SystemExit(0 if ok else 1)

    seeds = [options.seed + index for index in range(options.games)]
    say('Прогон: партий %d, ходов %d, галактика %s, империй %s, потоков %d'
        % (options.games, options.turns, options.size,
           options.empires or 'по настройке', options.workers))

    started = time.time()
    written = 0
    finished = 0
    with io.open(options.out, 'w', encoding='utf-8') as sink:
        with ThreadPoolExecutor(max_workers=options.workers) as pool:
            futures = [pool.submit(play, account, seed, options.turns, options.size,
                                   'Баланс %d' % seed, options.empires)
                       for seed in seeds]
            for future in futures:
                telemetry = future.result()
                if 'error' in telemetry:
                    say('  зерно %s: отказ — %s' % (telemetry['seed'], telemetry['error']))
                    continue
                lines = rows(telemetry)
                for line in lines:
                    sink.write(json.dumps(line, ensure_ascii=False) + '\n')
                written += len(lines)
                if telemetry['status'] == 'FINISHED':
                    finished += 1
                say('  зерно %s: ходов %d, %s, замеров %d, %.1f с'
                    % (telemetry['seed'], telemetry['played'],
                       'победа места %s (%s)' % (telemetry['winnerSlot'], telemetry['victoryKind'])
                       if telemetry['status'] == 'FINISHED' else 'победителя нет',
                       len(lines), telemetry['seconds']))

    say()
    say('Записано замеров: %d в %s' % (written, options.out))
    say('Партий с победителем: %d из %d' % (finished, options.games))
    say('Время прогона: %.1f с' % (time.time() - started))
    io.open(REPORT, 'w', encoding='utf-8').write('\n'.join(report) + '\n')


if __name__ == '__main__':
    main()
