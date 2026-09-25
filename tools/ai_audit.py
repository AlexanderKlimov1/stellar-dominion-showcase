# -*- coding: utf-8 -*-
"""Чем империя ИИ занята на самом деле: стройка, оборона, наука против расселения.

Вопросы хозяина проекта перед кругом 5:
  1. Реагирует ли ИИ на угрозу нападения — ставит ли оборону, когда рядом чужой флот;
  2. Берётся ли он за суперкомпьютер и робошахты следом за заводом и лабораторией;
  3. Сравнивает ли он вообще пользу стройки и науки с простым расселением.

Меряется живой партией на обычном сервере (партии в Postgres): на памятном их не видно
вовсе — там игра живёт в H2, и та же ошибка уже сделала проверку холостого хода слепой.
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
TURNS = int(sys.argv[1]) if len(sys.argv) > 1 else 200
STEP = 25
SEED = 900001
PSQL = r'C:\PostgreSQL15\bin\psql.exe'
REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ai-audit.txt')
out = []


def say(line=''):
    print(line, flush=True)
    out.append(line)


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
    # Пароль один на оба режима сервера: учётные записи живут в постоянном источнике данных.
    for path in (r'C:\Works\moo3\admin.txt',):
        try:
            found = re.search(r'пароль:\s*(\S+)', io.open(path, encoding='utf-8').read())
        except OSError:
            continue
        if not found:
            continue
        try:
            token = call('POST', '/api/auth/login',
                         {'login': 'admin', 'password': found.group(1)})['token']
            say('вход по %s' % path)
            return token
        except Exception:
            continue
    raise SystemExit('не подошёл ни один пароль администратора')


token = admin_token()
created = call('POST', '/api/games',
               {'name': 'Проверка ИИ', 'playerName': 'Наблюдатель', 'galaxySize': 'HUGE',
                'seed': SEED, 'aiEmpires': 8, 'galacticEvents': False}, token)
GAME = created['game']['id']
ACCESS = created['credentials']['accessToken']
call('POST', '/api/games/%s/start' % GAME, {'accessToken': ACCESS})
say('партия %s: HUGE, 8 империй, зерно %d, %d ходов' % (GAME[:8], SEED, TURNS))

DEFENCE = ('star-base', 'battle-station', 'star-fortress', 'missile-base',
           'ground-batteries', 'fighter-garrison', 'planetary-flux-shield',
           'artemis-system-net', 'planetary-barrier-shield')
ECONOMY = ('automated-factory', 'research-laboratory', 'supercomputer', 'robo-miners',
           'autolab', 'astro-university', 'deep-core-mine', 'galactic-cybernet')

BUILDINGS = ("select b.building_code, count(*)::text from planet_building b "
             "join planet p on p.id=b.planet_id join star_system s on s.id=p.star_system_id "
             "where s.game_id='{0}' group by 1 order by 2 desc")
COLONIES = ("select count(*)::text from planet p join star_system s on s.id=p.star_system_id "
            "where s.game_id='{0}' and p.owner_player_id is not null")
BARE = ("select count(*)::text from planet p join star_system s on s.id=p.star_system_id "
        "where s.game_id='{0}' and p.owner_player_id is not null "
        "and not exists (select 1 from planet_building b where b.planet_id=p.id)")
WARS = ("select count(*)::text from diplomacy_relation r join player pl on pl.id=r.player_id "
        "where pl.game_id='{0}' and r.stance='WAR'")
TECHS = ("select coalesce(round(avg(c)::numeric,1)::text,'0') from (select count(*) c "
         "from player_technology t join player pl on pl.id=t.player_id "
         "where pl.game_id='{0}' group by pl.id) x")
FIRST_WAR = None

say('')
say('%5s %8s %7s %6s %8s %9s %9s' % ('ход', 'колоний', 'голых', 'войн', 'технол.', 'зданий', 'обороны'))
timeline = []
for turn in range(STEP, TURNS + 1, STEP):
    call('POST', '/api/games/%s/turn/advance' % GAME, {'accessToken': ACCESS, 'turns': STEP})
    built = {code: int(n) for code, n in sql(BUILDINGS.format(GAME))}
    colonies = int(sql(COLONIES.format(GAME))[0][0])
    bare = int(sql(BARE.format(GAME))[0][0])
    wars = int(sql(WARS.format(GAME))[0][0])
    techs = float(sql(TECHS.format(GAME))[0][0])
    defence = sum(n for code, n in built.items() if code in DEFENCE)
    if wars > 0 and FIRST_WAR is None:
        FIRST_WAR = turn
    timeline.append((turn, colonies, bare, wars, techs, sum(built.values()), defence, dict(built)))
    say('%5d %8d %7d %6d %8.1f %9d %9d'
        % (turn, colonies, bare, wars, techs, sum(built.values()), defence))

last = timeline[-1][-1]
say('')
say('ЧТО ПОСТРОЕНО ЗА %d ХОДОВ (восемь империй вместе)' % TURNS)
for code, n in sorted(last.items(), key=lambda kv: -kv[1]):
    mark = ' <- хозяйство' if code in ECONOMY else (' <- оборона' if code in DEFENCE else '')
    say('   %-26s %3d%s' % (code, n, mark))
say('')
say('ХОЗЯЙСТВО ПО ПОРЯДКУ СПИСКА РАЗВИТИЯ:')
for code in ECONOMY:
    say('   %-26s %3d' % (code, last.get(code, 0)))
say('')
say('ОБОРОНА: всего %d, первая война на ходу %s'
    % (sum(last.get(code, 0) for code in DEFENCE), FIRST_WAR))
for turn, colonies, bare, wars, techs, total, defence, built in timeline:
    if defence:
        say('   первая оборона замечена на ходу %d (войн к этому ходу: %d)' % (turn, wars))
        break
else:
    say('   обороны не построено НИ ОДНОЙ')

colonies = timeline[-1][1]
bare = timeline[-1][2]
say('')
say('РАССЕЛЕНИЕ ПРОТИВ СТРОЙКИ: колоний %d, из них БЕЗ ЕДИНОЙ ПОСТРОЙКИ %d (%.0f %%)'
    % (colonies, bare, 100.0 * bare / colonies if colonies else 0))
say('зданий на колонию: %.2f' % (timeline[-1][5] / colonies if colonies else 0))

io.open(REPORT, 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
print('отчёт: %s' % REPORT)
call('DELETE', '/api/games/%s/admin' % GAME, None, token)
