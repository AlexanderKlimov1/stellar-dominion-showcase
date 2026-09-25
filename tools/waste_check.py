# -*- coding: utf-8 -*-
"""Механики, которые ТРАТЯТ и не дают — проверка на холостой ход (этап 3).

Вопрос, на который отвечает этот скрипт, один: за что империя платит производством, не
получая ничего в течение следующих двадцати ходов. Такая механика хуже дорогой — она не
«слаба», её просто нет, и любая цена рядом с ней измеряется шумом.

КАК МЕРИТСЯ. Партия играется целиком, а потом спрашивается телеметрия: там лежит всё
построенное с ХОДОМ ПОСТРОЙКИ и летопись каждой империи по ходам. Для каждой постройки
берётся прирост её владельца за следующие двадцать ходов — население, флот, колонии, казна,
технологии — и сравнивается с ФОНОМ: тем, что за те же двадцать ходов прибавилось у империй,
которые в этот отрезок не строили ничего. Галактика растёт сама, и без сравнения с фоном
любая постройка выглядит полезной.

ПОЧЕМУ ЧЕРЕЗ API, А НЕ ЧЕРЕЗ БАЗУ. Прежде проверка ходила в Postgres напрямую и опрашивала
партию каждый ход. С переездом прогонов в память (H2, журнал п. 3.90) она ослепла целиком:
колоний ноль, построек ноль — и отчёт печатал «холостых механик не найдено», не измерив
ничего. Ответ, которого никто не измерял, хуже отсутствия ответа. Теперь спрашивается игра,
а не база, и проверка видит партию на любом движке. Заодно она стала дешевле: один запрос в
конце вместо тысячи по ходу.

ЧЕГО ЭТА ПРОВЕРКА НЕ ДЕЛАЕТ. Она не говорит, что механика сломана в коде, — только что игра
за неё платит и ничего не получает. Причина бывает любой: нет пути у ИИ, эффект не читается,
условие никогда не выполняется. Разбираться приходится глазами, но искать больше не
приходится.

  python tools\\waste_check.py [ходов] [зерно] [империй] [размер галактики]
"""
import io
import json
import os
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = 'http://localhost:8080'
REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'balance', 'waste-report.txt')
HORIZON = 20

TURNS = int(sys.argv[1]) if len(sys.argv) > 1 else 200
SEED = int(sys.argv[2]) if len(sys.argv) > 2 else 4242
EMPIRES = int(sys.argv[3]) if len(sys.argv) > 3 else 8
# Размер галактики — параметром: круги балансировки перешли на HUGE (решение хозяина
# проекта 18.09.2026), и проверка обязана идти на той же галактике, что и замер, —
# иначе она отвечает про другую игру.
SIZE = sys.argv[4] if len(sys.argv) > 4 else 'HUGE'

# Пароль администратора один на оба режима: учётные записи живут в постоянном источнике
# данных, а не в памятной базе партий (см. run-balance.cmd).
ADMIN_FILES = (r'C:\Works\moo3\admin.txt',)

report = []


def say(line=''):
    print(line, flush=True)
    report.append(line)


def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header('Content-Type', 'application/json')
    if token:
        request.add_header('X-Account-Token', token)
    with urllib.request.urlopen(request, timeout=3600) as answer:
        text = answer.read().decode()
        return json.loads(text) if text else {}


def admin_password():
    """Пароль администратора — из admin.txt, и он один на оба режима сервера."""
    for path in ADMIN_FILES:
        try:
            found = re.search(r'пароль:\s*(\S+)', io.open(path, encoding='utf-8').read())
        except OSError:
            continue
        if not found:
            continue
        try:
            call('POST', '/api/auth/login', {'login': 'admin', 'password': found.group(1)})
            return found.group(1)
        except Exception:
            continue
    raise SystemExit('не подошёл ни один пароль администратора')


def median(values):
    values = sorted(values)
    if not values:
        return 0.0
    middle = len(values) // 2
    return float(values[middle]) if len(values) % 2 else (values[middle - 1] + values[middle]) / 2


token = call('POST', '/api/auth/login',
             {'login': 'admin', 'password': admin_password()})['token']
created = call('POST', '/api/games',
               {'name': 'Холостой ход', 'playerName': 'Проверяющий', 'galaxySize': SIZE,
                'seed': SEED, 'aiEmpires': EMPIRES, 'galacticEvents': False}, token)
GAME = created['game']['id']
ACCESS = created['credentials']['accessToken']
call('POST', '/api/games/%s/start' % GAME, {'accessToken': ACCESS})
say('Проверка холостого хода: партия %s, галактика %s, %d ходов, империй %d, зерно %d'
    % (GAME[:8], SIZE, TURNS, EMPIRES, SEED))

call('POST', '/api/games/%s/turn/advance' % GAME, {'accessToken': ACCESS, 'turns': TURNS})
telemetry = call('GET', '/api/games/%s/telemetry?accessToken=%s' % (GAME, ACCESS))
call('DELETE', '/api/games/%s/admin' % GAME, None, token)

built = telemetry.get('built') or []
empires = telemetry.get('empires') or []
# Летопись по местам: ход -> строка. Фон и прирост считаются по ней одной.
history = {}
for empire in empires:
    rows = {}
    for row in empire.get('history') or []:
        rows[row['turn']] = row
    history[empire['slot']] = rows

say('империй в телеметрии %d, построек за партию %d' % (len(empires), len(built)))

# ПУСТОЙ ЗАМЕР — ЭТО НЕ «ВСЁ ЧИСТО», А «МЕРИТЬ БЫЛО НЕЧЕМ». Отличать это надо ДО вывода:
# у живой партии восьми империй постройки есть всегда, и их отсутствие значит поломку
# самой проверки, а не безупречность игры.
if not built or not history:
    say('')
    say('ИЗМЕРИТЬ НЕ УДАЛОСЬ: телеметрия не отдала ни построек, ни летописи.')
    say('Это НЕ «холостых механик нет»: про механики сказать нечего.')
    io.open(REPORT, 'w', encoding='utf-8', newline='\n').write('\n'.join(report) + '\n')
    raise SystemExit(1)

FIELDS = (('население', 'populationK'), ('выработка', 'production'),
          ('наука', 'research'), ('казна', 'credits'), ('флот', 'fleetPower'),
          ('колонии', 'colonies'), ('технологии', 'technologies'))

# Единиц производства за кредит — курс самой игры (PopulationCalculator.UNITS_PER_CREDIT):
# в MOO II излишки и товары продаются по два к одному.
UNITS_PER_CREDIT = 2


def worth(marks):
    """Польза постройки В КРЕДИТАХ за горизонт — п. 10, п. 11.1.

    ЗАЧЕМ ЭТОТ СТОЛБЕЦ. Голая казна у любой постройки уходит в минус: содержание платится
    каждый ход, а то, ради чего здание ставили, приходит выработкой, наукой и людьми — и
    в кредитах не считается вовсе. Читать такую таблицу нельзя: выходит, что строить
    что угодно хуже, чем не строить.

    Поэтому выработка и наука приводятся к деньгам курсом самой игры (два к одному), а
    казна берётся как есть — она уже в кредитах и уже за вычетом содержания. Прирост
    ПОТОКА умножается на горизонт: это верхняя оценка (поток поднимается не мгновенно),
    но вопрос, на который отвечает проверка, — ЗНАК, а не величина.

    ФЛОТ И КОЛОНИИ СЮДА НЕ ИДУТ: безопасность деньгами не меряется, и складывать её с
    кредитами значило бы выдумать курс, которого в игре нет. Они стоят своими столбцами.
    """
    by_name = dict(marks)
    flow = by_name.get('выработка', 0.0) + by_name.get('наука', 0.0)
    return by_name.get('казна', 0.0) + flow * HORIZON / UNITS_PER_CREDIT


def growth(slot, since, field):
    """Прирост показателя у этой империи за двадцать ходов после постройки."""
    rows = history.get(slot) or {}
    before, after = rows.get(since), rows.get(since + HORIZON)
    if not before or not after:
        return None
    return (after.get(field) or 0) - (before.get(field) or 0)


# Кто и когда строил: по этому же списку считается ФОН — отрезки без единой постройки.
building_turns = {}
for one in built:
    building_turns.setdefault(one['ownerSlot'], set()).add(one['builtTurn'])

background = {name: [] for name, _ in FIELDS}
for slot, rows in history.items():
    busy = building_turns.get(slot, set())
    for turn in rows:
        # Отрезок считается спокойным, только если в нём не достроено НИЧЕГО: иначе фон
        # вбирает в себя как раз то, с чем его собираются сравнивать.
        if any(turn <= one <= turn + HORIZON for one in busy):
            continue
        for name, field in FIELDS:
            value = growth(slot, turn, field)
            if value is not None:
                background[name].append(value)

say('')
say('ФОН — сколько прибавляется за двадцать ходов САМО ПО СЕБЕ (отрезков %d):'
    % len(background['население']))
say('  ' + ' | '.join('%s %+.1f' % (name, median(background[name])) for name, _ in FIELDS))
say('  фон в деньгах: %+.0f кр. за двадцать ходов'
    % worth([(name, median(background[name])) for name, _ in FIELDS]))

gains = {}
for one in built:
    row = gains.setdefault(one['code'], {name: [] for name, _ in FIELDS})
    row.setdefault('раз', 0)
    row['раз'] += 1
    for name, field in FIELDS:
        value = growth(one['ownerSlot'], one['builtTurn'], field)
        if value is not None:
            row[name].append(value)

say('')
say('%-24s %4s %11s %10s %9s %9s %10s %9s'
    % ('что построено', 'раз', 'польза, кр.', 'выработка', 'наука', 'казна', 'население', 'флот'))
suspects = []
for what, row in sorted(gains.items(), key=lambda kv: -kv[1]['раз']):
    marks = [(name, median(row[name]) - median(background[name])) for name, _ in FIELDS]
    by_name = dict(marks)
    say('%-24s %4d %+11.0f %+10.1f %+9.1f %+9.0f %+10.0f %+9.0f'
        % (what, row['раз'], worth(marks), by_name['выработка'], by_name['наука'],
           by_name['казна'], by_name['население'], by_name['флот']))
    # Подозрение — только у того, что строили не раз и не два: одна постройка, совпавшая
    # с фоном, это совпадение, а не приговор. И судится оно по ПОЛЬЗЕ и безопасности
    # разом: здание, не давшее ни кредита сверх фона, но поднявшее флот, холостым не
    # является — оно куплено ради обороны.
    if row['раз'] >= 5 and abs(worth(marks)) < 1e-9 and abs(by_name['флот']) < 1e-9:
        suspects.append(what)

say('')
if suspects:
    say('ПОДОЗРЕНИЕ НА ХОЛОСТОЙ ХОД (построено не меньше пяти раз, и за двадцать ходов')
    say('после постройки НИЧЕГО не прибавилось сверх фона):')
    for what in suspects:
        say('   %s — построено %d раз' % (what, gains[what]['раз']))
else:
    say('Холостых механик не найдено: у каждой постройки есть отклонение от фона.')

io.open(REPORT, 'w', encoding='utf-8', newline='\n').write('\n'.join(report) + '\n')
print('отчёт: %s' % REPORT)
