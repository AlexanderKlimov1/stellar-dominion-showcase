# -*- coding: utf-8 -*-
"""Реплей партии ИИ: что сильнейшая раса строила каждый ход и как действовала.

Зачем. Замер отвечает числом, оракул — сборкой, а «почему она сильна» не отвечает никто:
и то и другое сводит партию к одной величине. Для разбора самого алгоритма нужна летопись
решений — ход за ходом, с тем, что стояло на стапеле у каждой колонии, что достроилось,
куда пошли флоты и что случилось с соседями.

Как пользоваться:

    python tools\\ai_replay.py [ходов] [зерно] [файл.html] [--traits код,код,...]

Играется партия HUGE на восемь империй; первая империя ИИ собирается из названных сторон
(по умолчанию — сборка, которую назвал сильнейшей последний оракул), и записывается
ИМЕННО ОНА. Остальные семь мест — случайные законные сборки того же бюджета, как в прогоне.

ЧИТАЕТ ПАРТИЮ ИЗ POSTGRES, поэтому сервер нужен ОБЫЧНОГО режима: в памятном (H2) партии
не видно вовсе — на этом уже ослепла проверка холостого хода (журнал, п. 3.93).
"""
import io
import json
import os
import re
import subprocess
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
BASE = 'http://localhost:8080'
TURNS = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 200
SEED = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 500000005
OUT = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3].endswith('.html') else r'C:\Docs\AI-replay.html'
PSQL = r'C:\PostgreSQL15\bin\psql.exe'
EMPIRES = 8

# По умолчанию — лидер оракула круга 4 без тех сторон, что перестали в него влезать после
# правки цен (журнал, п. 3.93): сборка нужна лишь для проверки самого инструмента, а для
# разбора всегда передаётся своя, из последнего оракула.
TRAITS = ['repulsive', 'cybernetic', 'growth-fast', 'omniscient', 'ground-good',
          'gov-dictatorship']
for arg in sys.argv[1:]:
    if arg.startswith('--traits='):
        TRAITS = [one.strip() for one in arg.split('=', 1)[1].split(',') if one.strip()]

# Пароль один на оба режима сервера: учётные записи живут в постоянном источнике данных.
ADMIN_FILES = (r'C:\Works\moo3\admin.txt',)


def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header('Content-Type', 'application/json')
    if token:
        request.add_header('X-Account-Token', token)
    with urllib.request.urlopen(request, timeout=3600) as answer:
        text = answer.read().decode()
        return json.loads(text) if text else {}


def sql(query):
    env = dict(os.environ, PGPASSWORD='moo3', PGCLIENTENCODING='UTF8')
    done = subprocess.run([PSQL, '-h', 'localhost', '-p', '5433', '-U', 'moo3', '-d', 'moo3',
                           '-t', '-A', '-F', '|', '-c', query],
                          capture_output=True, text=True, encoding='utf-8', env=env)
    if done.returncode != 0:
        raise RuntimeError((done.stderr or done.stdout)[:400])
    return [line.split('|') for line in done.stdout.splitlines() if line.strip()]


def admin_token():
    for path in ADMIN_FILES:
        try:
            found = re.search(r'пароль:\s*(\S+)', io.open(path, encoding='utf-8').read())
        except OSError:
            continue
        if not found:
            continue
        try:
            return call('POST', '/api/auth/login',
                        {'login': 'admin', 'password': found.group(1)})['token']
        except Exception:
            continue
    raise SystemExit('не подошёл ни один пароль администратора')


token = admin_token()
design = call('GET', '/api/reference/race-traits')
names = {option['code']: option['name']
         for group in design['groups'] for option in group['options']}
cost = sum(option['picks'] for group in design['groups'] for option in group['options']
           if option['code'] in TRAITS)
print('сборка наблюдаемой империи (%d очков из %d): %s'
      % (cost, design['picks'], ', '.join(names.get(code, code) for code in TRAITS)))
# Проверяем ДО партии, а не ловим отказом на старте: 409 посреди прогона выглядит поломкой
# инструмента, хотя дело в сборке. Цены двигаются каждый круг, и вчера законная сборка
# сегодня может не влезать в бюджет — так и вышло с лидером круга 4 после правки цен.
unknown = [code for code in TRAITS if code not in names]
if unknown:
    raise SystemExit('нет таких сторон в справочнике: %s' % ', '.join(unknown))
if cost > design['picks']:
    raise SystemExit('сборка стоит %d очков при бюджете %d — передай другую через --traits='
                     % (cost, design['picks']))

created = call('POST', '/api/games',
               {'name': 'Реплей ИИ', 'playerName': 'Летописец', 'galaxySize': 'HUGE',
                'seed': SEED, 'aiEmpires': EMPIRES, 'galacticEvents': False}, token)
GAME = created['game']['id']
ACCESS = created['credentials']['accessToken']
# Первое место — наблюдаемая сборка, остальные пусты: пустое место значит «случайная
# законная сборка», ровно как в прогоне.
call('POST', '/api/games/%s/start' % GAME,
     {'accessToken': ACCESS,
      'aiEmpires': [{'name': 'Наблюдаемая', 'traits': TRAITS}]
                   + [{'name': None, 'traits': []} for _ in range(EMPIRES - 1)]})
print('партия %s: HUGE, %d империй, зерно %d, %d ходов' % (GAME[:8], EMPIRES, SEED, TURNS))

hero = sql("select id::text, name, slot::text from player where game_id='%s' "
           "and player_type='AI' order by slot limit 1" % GAME)[0]
HERO, HERO_NAME = hero[0], hero[1]
print('наблюдаем империю %s (место %s)' % (HERO_NAME, hero[2]))

COLONIES = ("select p.name, p.population_k::text, coalesce(p.project_code,'-'), "
            "coalesce(p.project_points::text,'0') from planet p "
            "join star_system s on s.id=p.star_system_id "
            "where s.game_id='{0}' and p.owner_player_id='{1}' order by p.name")
BUILT = ("select p.name, b.building_code from planet_building b join planet p on p.id=b.planet_id "
         "join star_system s on s.id=p.star_system_id "
         "where s.game_id='{0}' and p.owner_player_id='{1}' order by p.name, b.building_code")
FLEETS = ("select coalesce(s.name,'?'), coalesce(t.name,''), "
          "coalesce((select sum(fs.ships) from fleet_ship fs where fs.fleet_id=f.id),0)::text "
          "from fleet f left join star_system s on s.id=f.star_system_id "
          "left join star_system t on t.id=f.target_system_id "
          "where f.owner_player_id='{1}' order by 1, 2")
STATE = ("select credits::text, coalesce((select count(*) from player_technology t "
         "where t.player_id=pl.id),0)::text from player pl where pl.id='{1}'")
WARS = ("select count(*)::text from diplomacy_relation r "
        "where r.player_id='{1}' and r.stance='WAR'")
ACTS = ("select code, times::text from empire_activity where player_id='{1}' order by code")

frames = []
seen_buildings = set()
seen_acts = {}
for turn in range(1, TURNS + 1):
    call('POST', '/api/games/%s/turn/advance' % GAME, {'accessToken': ACCESS, 'turns': 1})
    colonies = sql(COLONIES.format(GAME, HERO))
    built = sql(BUILT.format(GAME, HERO))
    fleets = sql(FLEETS.format(GAME, HERO))
    state = sql(STATE.format(GAME, HERO))
    wars = int(sql(WARS.format(GAME, HERO))[0][0])
    acts = {code: int(amount) for code, amount in sql(ACTS.format(GAME, HERO))}

    now = {(name, code) for name, code in built}
    fresh = sorted('%s: %s' % (name, code) for name, code in now - seen_buildings)
    seen_buildings = now
    events = []
    for code, amount in sorted(acts.items()):
        was = seen_acts.get(code, 0)
        if amount > was:
            events.append('%s +%d' % (code, amount - was))
    seen_acts = acts

    frames.append({
        'turn': turn,
        'credits': int(state[0][0]) if state else 0,
        'techs': int(state[0][1]) if state else 0,
        'wars': wars,
        'population': sum(int(row[1]) for row in colonies),
        'colonies': [{'name': row[0], 'pop': int(row[1]), 'project': row[2],
                      'points': int(row[3])} for row in colonies],
        'built': fresh,
        'fleets': [{'at': row[0], 'to': row[1], 'ships': int(row[2])} for row in fleets],
        'events': events,
    })
    if turn % 25 == 0:
        print('  ход %d: колоний %d, флотов %d, войн %d'
              % (turn, len(colonies), len(fleets), wars), flush=True)

# Летопись пишется каждый ход сама (EmpireHistoryPhase), и берём мы её ОДНИМ запросом
# в конце, а не по ходу: пересчитывать нечего, а пятьсот лишних запросов стоят минут.
history = sql("select turn::text, colonies::text, production::text, research::text, "
              "fleet_power::text, buildings::text from empire_history "
              "where player_id='%s' order by turn" % HERO)
call('DELETE', '/api/games/%s/admin' % GAME, None, token)


def esc(text):
    return (str(text).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;'))


def spark(values, width=760, height=90, colour='#7aa7ff'):
    if not values:
        return ''
    top = max(values) or 1
    step = width / max(1, len(values) - 1)
    points = ' '.join('%.1f,%.1f' % (i * step, height - value * (height - 6) / top)
                      for i, value in enumerate(values))
    return ('<svg viewBox="0 0 %d %d" preserveAspectRatio="none" class="spark">'
            '<polyline points="%s" fill="none" stroke="%s" stroke-width="2"/></svg>'
            % (width, height, points, colour))


rows = []
for frame in frames:
    projects = ', '.join('%s <span class="dim">%s</span>' % (esc(one['name']), esc(one['project']))
                         for one in frame['colonies']) or '<span class="dim">нет колоний</span>'
    moves = ', '.join(('%s &rarr; %s (%d)' % (esc(one['at']), esc(one['to']), one['ships']))
                      if one['to'] else ('%s (%d)' % (esc(one['at']), one['ships']))
                      for one in frame['fleets']) or '<span class="dim">нет флотов</span>'
    built = '<br>'.join(esc(one) for one in frame['built'])
    events = '<br>'.join(esc(one) for one in frame['events'])
    rows.append(
        '<tr%s><td class="num">%d</td><td class="num">%d</td><td class="num">%d</td>'
        '<td class="num">%d</td><td class="num">%d</td><td>%s</td><td class="ok">%s</td>'
        '<td>%s</td><td class="ev">%s</td></tr>'
        % (' class="war"' if frame['wars'] else '', frame['turn'], len(frame['colonies']),
           frame['population'], frame['credits'], frame['techs'], projects, built, moves, events))

totals = {}
for frame in frames:
    for one in frame['built']:
        code = one.split(': ', 1)[1]
        totals[code] = totals.get(code, 0) + 1
summary = ''.join('<tr><td>%s</td><td class="num">%d</td></tr>' % (esc(code), count)
                  for code, count in sorted(totals.items(), key=lambda kv: -kv[1]))

colonies_line = [int(row[1]) for row in history]
production_line = [int(row[2]) for row in history]
research_line = [int(row[3]) for row in history]

html = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Реплей партии ИИ</title>
<style>
 :root { --ink:#e7ecf3; --dim:#94a3b8; --line:#2a3556; --panel:#131a2e; --gold:#e0b252; }
 * { box-sizing:border-box; }
 body { margin:0; background:#0b1020; color:var(--ink);
        font:13px/1.45 "Segoe UI",system-ui,sans-serif; padding-block:24px;
        padding-left:16px; padding-right:16px; }
 .wrap { max-width:1500px; margin:0 auto; }
 h1 { font-size:24px; margin:0 0 4px; }
 h2 { font-size:17px; color:var(--gold); margin:28px 0 10px;
      border-bottom:1px solid var(--line); padding-bottom:6px; }
 .lead { color:var(--dim); max-width:80ch; }
 .panel { background:var(--panel); border:1px solid var(--line); border-radius:8px;
          padding:12px 14px; margin:12px 0; }
 table { border-collapse:collapse; width:100%%; font-size:12px; }
 th,td { border:1px solid var(--line); padding:4px 7px; vertical-align:top; text-align:left; }
 th { background:#1a2340; position:sticky; top:0; color:#c3cde2; }
 td.num { text-align:right; font-family:Consolas,monospace; white-space:nowrap; }
 tr.war td.num:first-child { color:#f0a0a0; font-weight:600; }
 .dim { color:var(--dim); }
 .ok { color:#9fdcc2; }
 .ev { color:#edc188; }
 .scroll { overflow-x:auto; max-height:70vh; overflow-y:auto; border:1px solid var(--line);
           border-radius:8px; }
 .spark { width:100%%; height:90px; display:block; }
 .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:12px; }
 footer { margin-top:32px; border-top:1px solid var(--line); padding-top:12px;
          color:var(--dim); font-size:12px; }
</style></head><body><div class="wrap">
<h1>Реплей партии ИИ: %(name)s</h1>
<p class="lead">Ход за ходом: что стояло на стапеле у каждой колонии, что достроилось, куда
пошли флоты и что засчитали счётчики механик. Галактика HUGE, %(empires)d империй, зерно
%(seed)d, %(turns)d ходов. Строки войны помечены красным номером хода.</p>
<div class="panel"><b>Сборка наблюдаемой империи</b> (%(cost)d очков): %(traits)s</div>
<h2>Как росла империя</h2>
<div class="grid">
 <div class="panel"><b>Колонии</b>%(colonies_spark)s</div>
 <div class="panel"><b>Выработка</b>%(production_spark)s</div>
 <div class="panel"><b>Наука</b>%(research_spark)s</div>
</div>
<h2>Что построено за партию</h2>
<div class="panel"><table><tr><th>постройка</th><th>раз</th></tr>%(summary)s</table></div>
<h2>Летопись ходов</h2>
<div class="scroll"><table>
<tr><th>ход</th><th>кол.</th><th>жит., тыс.</th><th>казна</th><th>тех.</th>
<th>что строит каждая колония</th><th>достроено</th><th>флоты</th><th>счётчики</th></tr>
%(rows)s
</table></div>
<footer>Собрано <code>tools/ai_replay.py</code>. Партия удалена после записи — реплей
самодостаточен.</footer>
</div></body></html>
""" % {
    'name': esc(HERO_NAME),
    'empires': EMPIRES,
    'seed': SEED,
    'turns': TURNS,
    'cost': cost,
    'traits': esc(', '.join(names.get(code, code) for code in TRAITS)),
    'colonies_spark': spark(colonies_line, colour='#6fc3a0'),
    'production_spark': spark(production_line, colour='#e0b252'),
    'research_spark': spark(research_line, colour='#7aa7ff'),
    'summary': summary,
    'rows': '\n'.join(rows),
}

io.open(OUT + '.tmp', 'w', encoding='utf-8', newline='\n').write(html)
os.replace(OUT + '.tmp', OUT)
print('реплей: %s (%d ходов, %d записей о стройке)' % (OUT, len(frames), sum(totals.values())))
