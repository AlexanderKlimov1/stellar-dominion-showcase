"""
Записывает демонстрационный бой в GIF — п. 8.

Запуск при поднятом сервере:  python tools\\demo_battle_gif.py [файл.gif]
По умолчанию кладёт `demo-battle.gif` в корень проекта.

Кадры рисуются по состоянию поля, которое отдаёт сам сервер: клетки, корабли, их
прочность, выстрелы и гибель — всё из тех же ответов, что видит экран боя. Поэтому запись
показывает настоящий бой, а не его подражание; правки правил видны в ней сразу.

Вид у кадров тот же, что у сцены (`ui/battle/`): тёмное поле в сетку, корабли —
прямоугольники размером по корпусу, свои синие, чужие красные. Выстрелы нарисованы по
видам оружия — луч линией, снаряды очередью точек, ракета по дуге, — а погибший корабль
рассыпается обломками.

Осторожно: сервер держит одну демонстрацию за раз, и новая удаляет прошлую. Если в это
время кто-то смотрит демонстрацию в браузере, его бой закончится.
"""
import io
import json
import math
import os
import random
import sys
import urllib.error
import urllib.request

from PIL import Image, ImageDraw, ImageFont

BASE = 'http://localhost:8080'

# --- как выглядит кадр -------------------------------------------------------

CELL = 30
HEADER = 34
BACKGROUND = (5, 7, 15)
GRID = (22, 32, 58)
HEADER_BACKGROUND = (9, 12, 24)
TEXT = (148, 163, 184)
TEXT_BRIGHT = (226, 232, 240)
BLUE = (125, 211, 252)
RED = (248, 113, 113)
AMBER = (253, 230, 138)

# Доля клетки, которую занимает корабль каждого размера корпуса — те же числа, что в
# `battleVisuals.ts`: иначе запись врала бы о размерах кораблей.
SHIP_SCALE = [0.34, 0.44, 0.56, 0.68, 0.82, 0.96]

# Сколько кадров тратится на один ход корабля: первый — выстрел в полёте, второй —
# попадание и обломки, третий — поле после хода.
FRAMES_PER_STEP = 3

# Сколько миллисекунд держится кадр.
FRAME_MS = 260

# Больше этого числа шагов в запись не идёт: длинный бой раздувает файл, а показать
# нужно, как он устроен.
MAX_STEPS = 120


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read().decode('utf-8')
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        return {'ERROR': error.code, 'body': error.read().decode('utf-8')}


def font(size):
    """Моноширинный шрифт с кириллицей; без него подписи кадра были бы квадратами."""
    for name in ('consola.ttf', 'arial.ttf', 'segoeui.ttf'):
        path = os.path.join(os.environ.get('WINDIR', r'C:\Windows'), 'Fonts', name)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def centre(x, y):
    """Середина клетки в пикселях кадра."""
    return x * CELL + CELL // 2, HEADER + y * CELL + CELL // 2


def ship_box(ship):
    """Прямоугольник корабля: размер от корпуса, как на экране."""
    scale = SHIP_SCALE[max(1, min(6, ship['hullSize'])) - 1]
    size = max(4, int(CELL * scale))
    cx, cy = centre(ship['x'], ship['y'])
    half = size // 2
    return cx - half, cy - half, cx + half, cy + half


def side_colour(ship, battle):
    """Свои синие, чужие красные — сторона нападающего считается своей, как на экране."""
    return BLUE if ship['side'] == 'ATTACKER' else RED


def draw_field(draw, battle, width, height):
    draw.rectangle([0, 0, width, height], fill=BACKGROUND)
    draw.rectangle([0, 0, width, HEADER], fill=HEADER_BACKGROUND)
    for column in range(battle['width'] + 1):
        x = column * CELL
        draw.line([(x, HEADER), (x, height)], fill=GRID)
    for row in range(battle['height'] + 1):
        y = HEADER + row * CELL
        draw.line([(0, y), (width, y)], fill=GRID)


def draw_header(draw, battle, small):
    left = f"{battle['attackerName']}: {battle['attackerPower']}"
    right = f"{battle['defenderName']}: {battle['defenderPower']}"
    draw.text((8, 6), f"Круг {battle['round']}", font=small, fill=TEXT_BRIGHT)
    draw.text((90, 6), left, font=small, fill=BLUE)
    draw.text((90, 19), right, font=small, fill=RED)


def draw_ships(draw, battle):
    for ship in battle['ships']:
        if ship['destroyed']:
            continue
        box = ship_box(ship)
        colour = side_colour(ship, battle)
        outline = AMBER if ship['id'] == battle.get('currentShipId') else (11, 18, 32)
        draw.rectangle(box, fill=colour, outline=outline)

        # Полоска прочности над кораблём — по ней видно, кому досталось.
        share = ship['structure'] / ship['maxStructure'] if ship['maxStructure'] else 1
        bar_top = box[1] - 4
        draw.rectangle([box[0], bar_top, box[2], bar_top + 1], fill=(31, 41, 55))
        if share > 0:
            end = box[0] + int((box[2] - box[0]) * share)
            health = (74, 222, 128) if share > 0.5 else (250, 204, 21) if share > 0.25 else RED
            draw.rectangle([box[0], bar_top, end, bar_top + 1], fill=health)


def draw_shot(draw, shot, progress):
    """
    Выстрел в полёте. Луч бьёт мгновенно и рисуется целиком, снаряды летят очередью точек,
    ракета идёт по дуге — те же три вида, что и на экране боя.
    """
    start = centre(*shot['from'])
    end = centre(*shot['to'])
    colour = shot['colour'] if shot['hit'] else tuple(int(c * 0.45) for c in shot['colour'])

    if shot['kind'] == 'BEAM':
        draw.line([start, end], fill=colour, width=2)
        return

    if shot['kind'] == 'PROJECTILE':
        for index in range(3):
            at = max(0.0, min(1.0, progress - index * 0.12))
            x = start[0] + (end[0] - start[0]) * at
            y = start[1] + (end[1] - start[1]) * at
            draw.ellipse([x - 2, y - 2, x + 2, y + 2], fill=colour)
        return

    # Ракета: дуга строится тем же способом, что и в сцене, — отклонением от середины.
    mid = ((start[0] + end[0]) / 2, (start[1] + end[1]) / 2)
    normal = (-(end[1] - start[1]), end[0] - start[0])
    length = max(1.0, math.hypot(*normal))
    bend = min(CELL * 1.6, length * 0.25)
    control = (mid[0] + normal[0] / length * bend, mid[1] + normal[1] / length * bend)

    def point(at):
        one = 1 - at
        return (one * one * start[0] + 2 * one * at * control[0] + at * at * end[0],
                one * one * start[1] + 2 * one * at * control[1] + at * at * end[1])

    trail = [point(at / 12) for at in range(13)]
    draw.line(trail, fill=tuple(int(c * 0.35) for c in colour), width=1)
    head = point(max(0.0, min(1.0, progress)))
    draw.ellipse([head[0] - 3, head[1] - 3, head[0] + 3, head[1] + 3], fill=colour)


def draw_debris(draw, wrecks, progress, rng):
    """Погибший корабль рассыпается кусками в разные стороны и гаснет."""
    for wreck in wrecks:
        cx, cy = centre(*wreck['at'])
        for piece in wreck['pieces']:
            distance = piece['reach'] * progress
            x = cx + math.cos(piece['angle']) * distance
            y = cy + math.sin(piece['angle']) * distance
            size = piece['size']
            fade = max(0.0, 1 - progress)
            colour = tuple(int(c * fade) for c in wreck['colour'])
            draw.rectangle([x - size, y - size, x + size, y + size], fill=colour)


def wreck(event, battle, rng):
    """Обломки одного погибшего корабля: направления и размеры — случайные."""
    ship = next((s for s in battle['ships'] if s['id'] == event['shipId']), None)
    colour = side_colour(ship, battle) if ship else RED
    size = SHIP_SCALE[max(1, min(6, ship['hullSize'] if ship else 1)) - 1] * CELL
    return {
        'at': (event.get('x', 0), event.get('y', 0)),
        'colour': colour,
        'pieces': [{'angle': rng.uniform(0, math.tau),
                    'reach': size * rng.uniform(0.8, 2.2),
                    'size': max(1, int(size / 6 * rng.uniform(0.5, 1.2)))}
                   for _ in range(9)],
    }


def shots_of(step, battle):
    """Залпы этого шага: откуда, куда, чем и дошло ли."""
    shots = []
    for event in step['events']:
        if event['type'] != 'FIRE':
            continue
        shooter = next((s for s in battle['ships'] if s['id'] == event['shipId']), None)
        target = next((s for s in battle['ships'] if s['id'] == event['targetShipId']), None)
        if shooter is None or target is None:
            continue
        shots.append({
            'from': (shooter['x'], shooter['y']),
            'to': (target['x'], target['y']),
            'kind': event.get('weaponKind') or 'PROJECTILE',
            'hit': (event.get('damage') or 0) > 0,
            'colour': side_colour(shooter, battle),
        })
    return shots


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'demo-battle.gif')

    demo = call('POST', '/api/reference/demo-battle')
    if 'ERROR' in demo:
        print('Демонстрация не завелась:', demo)
        return 1

    battle = demo['battle']
    width = battle['width'] * CELL
    height = HEADER + battle['height'] * CELL
    small = font(11)
    rng = random.Random(20260902)

    frames = []
    # Что попало в запись: по этой сводке видно, все ли виды оружия успели выстрелить и
    # сколько кораблей погибло, — иначе пришлось бы разглядывать кадры глазами.
    drawn = {'BEAM': 0, 'PROJECTILE': 0, 'MISSILE': 0, 'wrecks': 0}

    def frame(state, shots=(), wrecks=(), progress=0.0):
        image = Image.new('RGB', (width, height), BACKGROUND)
        draw = ImageDraw.Draw(image)
        draw_field(draw, state, width, height)
        draw_header(draw, state, small)
        draw_ships(draw, state)
        for shot in shots:
            draw_shot(draw, shot, progress)
        draw_debris(draw, wrecks, progress, rng)
        frames.append(image.convert('P', palette=Image.ADAPTIVE, colors=64))

    # Кадр строя перед боем: с него запись и начинается.
    frame(battle)
    frame(battle)

    state = battle
    for _ in range(MAX_STEPS):
        step = call('POST', f"/api/reference/demo-battle/{battle['id']}/step")
        if 'ERROR' in step:
            print('Шаг не удался:', step)
            break

        # Выстрелы считаются по состоянию ДО шага: стрелявший ещё стоит там, откуда бил.
        shots = shots_of(step, state)
        wrecks = [wreck(event, state, rng)
                  for event in step['events'] if event['type'] == 'DESTROYED']

        for shot in shots:
            drawn[shot['kind']] = drawn.get(shot['kind'], 0) + 1
        drawn['wrecks'] += len(wrecks)

        if shots:
            frame(state, shots, progress=0.45)
            frame(step, shots, wrecks, progress=0.95)
        if wrecks:
            frame(step, (), wrecks, progress=0.5)
        frame(step)

        state = step
        if state['state'] != 'IN_PROGRESS':
            break

    # Последний кадр держится дольше: на нём читается исход.
    for _ in range(6):
        frames.append(frames[-1])

    frames[0].save(target, save_all=True, append_images=frames[1:], optimize=True,
                   duration=FRAME_MS, loop=0)
    size_kb = os.path.getsize(target) // 1024
    print(f'кадров {len(frames)}, {size_kb} КБ: {target}')
    print(f"в записи: лучей {drawn['BEAM']}, снарядов {drawn['PROJECTILE']},"
          f" ракет {drawn['MISSILE']}, погибших кораблей {drawn['wrecks']}")
    print('исход:', state.get('outcome'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
