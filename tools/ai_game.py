"""
Тестовая партия без игрока-человека: восемь империй ИИ играют до победы одной из них.

Запуск при поднятом сервере:  python tools\ai_game.py [ходов] [размер галактики]

Партия заводится наблюдателем (`observer: true` при создании — п. 3.2): создатель держит
пропуск и объявляет конец хода, а его империю ведёт ИИ наравне с остальными. Ходы идут
подряд, пока партия не кончится победой или пока не выйдет отпущенное число ходов.

Каждый ход проверяются правила, которые в исправной игре нарушаться не могут: население
не больше вместимости планеты, колония не бывает без хозяина, ход растёт на единицу,
партия с победителем закрыта. Нарушение останавливает прогон — дальше смотреть незачем,
надо чинить механику.

Отчёт печатается в консоль и кладётся рядом со скриптом в ai-game-report.txt: консоль
Windows коверкает кириллицу, файл — нет.
"""
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

# Вывод по-русски: консоль Windows отдаёт скрипту кодовую страницу, в которую кириллица
# не влезает, и печать сообщения роняла прогон UnicodeEncodeError вместо того, чтобы это
# сообщение показать. Непереводимое заменяем, а не падаем.
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BASE = os.environ.get('MOO3_BASE', 'http://localhost:8080')
REPORT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'ai-game-report.txt')

report = []
problems = []
account_token = None


def say(line=''):
    report.append(line)
    try:
        print(line)
    except UnicodeEncodeError:
        print(line.encode('ascii', 'replace').decode())


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['X-Access-Token'] = token
    if account_token:
        headers['X-Account-Token'] = account_token
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            text = response.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode('utf-8')
        try:
            body = json.loads(raw) if raw else None
        except ValueError:
            body = raw
        return {'ERROR': error.code, 'body': body}


def failed(answer):
    return isinstance(answer, dict) and 'ERROR' in answer


def problem(turn, what):
    """Нарушенное правило: прогон дальше не идёт — чинить надо механику, а не прогон."""
    problems.append((turn, what))
    say('  !! ход %s: %s' % (turn, what))


def admin_login():
    """Пропуск учётной записи — п. 3.1: без него сервер не пускает в новую партию."""
    global account_token
    path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'admin.txt')
    login, password = 'admin', None
    for line in io.open(path, encoding='utf-8'):
        if line.startswith('логин:'):
            login = line.split(':', 1)[1].strip()
        if line.startswith('пароль:'):
            password = line.split(':', 1)[1].strip()
    answer = call('POST', '/api/auth/login', {'login': login, 'password': password})
    if failed(answer):
        # Самый частый случай — счётчики защиты входа (п. 3.1): их тратит сквозной прогон
        # своими проверками подбора, а живут они в памяти сервера. Пишем это прямо: без
        # подсказки отказ читается как поломка входа.
        hint = ('\nЭто счётчики защиты входа: они в памяти сервера. Перезапустите '
                'moo3-server\\run.cmd и повторите.' if answer.get('ERROR') == 429 else '')
        raise SystemExit('вход администратора не удался: %s%s'
                         % (answer.get('body', {}).get('message', answer), hint))
    account_token = answer['token']


def empires(details):
    return {player['id']: player['name'] for player in details['players']}


def census(systems):
    """Кто чем владеет: колонии, жители и выработка по империям — по одной карте."""
    colonies, population, production, research = {}, {}, {}, {}
    for system in systems:
        for planet in system.get('planets', []):
            owner = planet.get('ownerPlayerId')
            if owner is None or (planet.get('population') or 0) <= 0:
                continue
            colony = planet.get('colony') or {}
            colonies[owner] = colonies.get(owner, 0) + 1
            population[owner] = population.get(owner, 0) + planet['population']
            production[owner] = production.get(owner, 0) + (colony.get('production') or 0)
            research[owner] = research.get(owner, 0) + (colony.get('research') or 0)
    return colonies, population, production, research


def check_planets(turn, systems):
    """
    Правила планет, которые не могут нарушаться в исправной игре.

    Вместимость берётся у колонии, а не у планеты: у планеты она базовая, а биосферы и
    особенности расы её поднимают — п. 4.1. На базовой проверка ловила бы исправную
    колонию с биосферами.
    """
    for system in systems:
        for planet in system.get('planets', []):
            population = planet.get('population') or 0
            if population < 0:
                problem(turn, 'на планете %s население %s' % (planet['name'], population))
            if population > 0 and planet.get('ownerPlayerId') is None:
                problem(turn, 'колония %s без хозяина' % planet['name'])
            colony = planet.get('colony') or {}
            limit = colony.get('maxPopulation')
            if limit is not None and population > limit:
                problem(turn, 'на планете %s жителей %s при вместимости %s'
                        % (planet['name'], population, limit))
            if population > 0 and not planet.get('colonizable'):
                problem(turn, 'колония на непригодной планете %s (%s)'
                        % (planet['name'], planet.get('climateLabel')))


def run(max_turns, galaxy_size):
    admin_login()

    created = call('POST', '/api/games', {
        'name': 'Прогон ИИ',
        'galaxySize': galaxy_size,
        'playerName': 'Наблюдатель',
        'observer': True,
        'galacticEvents': True,
    })
    if failed(created):
        raise SystemExit('партия не создалась: %s' % created)
    game_id = created['game']['id']
    token = created['credentials']['accessToken']

    started = call('POST', '/api/games/%s/start' % game_id, {'accessToken': token})
    if failed(started):
        raise SystemExit('партия не стартовала: %s' % started)

    names = empires(started)
    say('Партия %s: галактика %s, империй %s'
        % (game_id, galaxy_size, len(names)))
    say('Империи: %s' % ', '.join(sorted(names.values())))
    say()

    previous_turn = 0
    winner = None
    began = time.time()

    for _ in range(max_turns):
        answer = call('POST', '/api/games/%s/turn/end' % game_id, {'accessToken': token}, token)
        if failed(answer):
            problem(previous_turn, 'конец хода отказал: %s' % json.dumps(answer, ensure_ascii=False))
            break

        summary = answer['state']['game']
        turn = summary['turn']
        if previous_turn and turn != previous_turn + 1 and summary['status'] == 'IN_PROGRESS':
            problem(turn, 'ход прыгнул с %s на %s' % (previous_turn, turn))
        previous_turn = turn

        galaxy = call('GET', '/api/games/%s/map?accessToken=%s&revealAll=true' % (game_id, token),
                      token=token)
        if failed(galaxy):
            problem(turn, 'карта не пришла: %s' % json.dumps(galaxy, ensure_ascii=False))
            break

        systems = galaxy['systems']
        check_planets(turn, systems)
        colonies, population, production, research = census(systems)

        if not population:
            problem(turn, 'в галактике не осталось ни одной колонии')
            break

        if turn % 10 == 0 or summary['status'] != 'IN_PROGRESS':
            alive = sorted(population.items(), key=lambda pair: -pair[1])
            say('ход %-4s колоний %-3s жителей %-4s выработка %-4s наука %-4s | %s'
                % (turn,
                   sum(colonies.values()),
                   sum(population.values()),
                   sum(production.values()),
                   sum(research.values()),
                   ', '.join('%s %s/%s' % (names.get(pid, '?')[:12], colonies.get(pid, 0), pop)
                             for pid, pop in alive[:8])))

        if summary['status'] != 'IN_PROGRESS' and not summary.get('winnerPlayerId'):
            problem(turn, 'партия закрыта без победителя')

        if problems:
            break

        if summary['status'] != 'IN_PROGRESS':
            winner = summary
            break

    say()
    spent = time.time() - began
    if winner:
        say('ПОБЕДА на ходу %s: %s (%s), партия шла %.0f с'
            % (winner['turn'], names.get(winner.get('winnerPlayerId'), '?'),
               winner.get('victoryKind'), spent))
    elif problems:
        say('ПРОГОН ОСТАНОВЛЕН: найдено нарушений %s' % len(problems))
        for turn, what in problems:
            say('  ход %s: %s' % (turn, what))
    else:
        say('за %s ходов победителя нет (%.0f с)' % (previous_turn, spent))
        say('партия %s осталась в базе — её можно досчитать' % game_id)

    return winner is not None and not problems


def main():
    max_turns = int(sys.argv[1]) if len(sys.argv) > 1 else 400
    galaxy_size = sys.argv[2] if len(sys.argv) > 2 else 'HUGE'
    ok = run(max_turns, galaxy_size)
    text = '\n'.join(report) + '\n'
    tmp = REPORT + '.tmp'
    io.open(tmp, 'w', encoding='utf-8', newline='\n').write(text)
    os.replace(tmp, REPORT)
    print('отчёт: %s' % REPORT)
    raise SystemExit(0 if ok else 1)


if __name__ == '__main__':
    main()
