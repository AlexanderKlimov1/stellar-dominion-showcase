# -*- coding: utf-8 -*-
"""
Чтение КАРТЫ ГАЛАКТИКИ оригинала: где звёзды, какие из них разведаны и куда слать флот.

ЗАЧЕМ. Разведка — единственное, чего нельзя сделать постоянной точкой на экране: звёзды у
каждой партии свои. Обход сцен, у которого была только таблица «нажать сюда», за всю
партию не разведал ни одной звезды — не потому, что правило неверное, а потому, что
правила такого рода тут не бывает.

КАК УЗНАЮТСЯ ЗВЁЗДЫ. Не по имени и не по цвету, а по РАЗМЕРУ светлого пятна. Фон карты —
мелкая звёздная пыль в одну-две точки; звезда системы нарисована лучистым значком в
десяток точек поперёк. Разница на порядок, и поэтому правило простое: связное пятно ярких
точек от `MIN_STAR` штук — это система, меньше — пыль. Туманности мимо этого правила
проходят тоже (они широкие, но тусклые), а вот их яркая сердцевина могла бы сойти за
звезду — от этого спасает потолок `MAX_STAR`.

КАКАЯ ЗВЕЗДА РАЗВЕДАНА. Та, у которой под значком написано ИМЯ. Это правило самой игры:
неразведанная система имени не показывает. Имя ищется чтением (`moo2_text`) в полоске под
звездой; словарь шрифта тут не важен вовсе — важно, есть ли там хоть что-нибудь.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import moo2_text as text

# Поле карты в игровых координатах: от рамки окна до правой панели сводок и до нижней
# полосы кнопок. Снято со снимка `tools/moo2-scenes/map0.png`.
FIELD = (20, 18, 505, 405)

# Точка считается светлой по самому яркому каналу — тем же мерилом, что и текст, но
# порог здесь ВЫШЕ. Двести измерены на снимке карты и разделяют три вещи разом: значки
# звёзд (ядро белое, 230–255) проходят, сердцевины туманностей (красное 150–190) не
# проходят, и имена систем (зелёное около 145) тоже — иначе буквы имени шли бы в счёт
# звёздами, а «Sol» превращался в три звезды рядом.
BRIGHT = 200

# Имя под звездой читается СВОИМ порогом: зелёное имя тусклее значка.
NAME_BRIGHT = 130

# Сколько точек в пятне: значок системы — от дюжины (мелкая звезда 4x4) до полусотни
# (крупная с лучами 12x10); пыль фона — одна-две.
MIN_STAR = 12
MAX_STAR = 200

# Полоска под звездой, где игра пишет имя системы: своя ширина и высота, снятые со
# снимка карты (имя «Sol» стоит под значком, по центру).
NAME_BELOW = (-26, 6, 52, 14)

# Строка ниже семи точек — это не имя, а нижний луч значка звезды.
NAME_MIN_HEIGHT = 7


def _blobs(image, field, bright):
    """Связные пятна светлых точек области: центр и размер каждого."""
    view = text.game_view(image).convert('RGB')
    x0, y0, width, height = field
    crop = view.crop((x0, y0, x0 + width, y0 + height))
    pixels = crop.load()
    lit = [[max(pixels[col, row]) >= bright for col in range(width)]
           for row in range(height)]
    found = []
    for row in range(height):
        for col in range(width):
            if not lit[row][col]:
                continue
            # Обход в ширину списком: рекурсия на пятне в сотни точек упёрлась бы в предел
            # вложенности, а глубина тут ни к чему — нужен только состав пятна.
            stack = [(row, col)]
            lit[row][col] = False
            cells = []
            while stack:
                here_row, here_col = stack.pop()
                cells.append((here_row, here_col))
                for step_row in (-1, 0, 1):
                    for step_col in (-1, 0, 1):
                        next_row, next_col = here_row + step_row, here_col + step_col
                        if 0 <= next_row < height and 0 <= next_col < width \
                                and lit[next_row][next_col]:
                            lit[next_row][next_col] = False
                            stack.append((next_row, next_col))
            found.append(cells)
    return [(x0 + sum(cell[1] for cell in cells) // len(cells),
             y0 + sum(cell[0] for cell in cells) // len(cells),
             len(cells)) for cells in found]


def stars(image, field=FIELD, bright=BRIGHT):
    """
    Звёзды карты: точка каждой и её имя (пусто — система не разведана).

    Имя читается словарём шрифта, и неизвестная буква приходит знаком «?». Для решения
    «разведана ли» этого достаточно: важно не что написано, а что написано хоть что-то.
    """
    found = []
    for x, y, size in _blobs(image, field, bright):
        if not MIN_STAR <= size <= MAX_STAR:
            continue
        box = (x + NAME_BELOW[0], y + NAME_BELOW[1], NAME_BELOW[2], NAME_BELOW[3])
        lines = text.read(image, box, bright=NAME_BRIGHT, min_height=NAME_MIN_HEIGHT)
        name = ' '.join(line['text'] for line in lines if line['text']).strip()
        # Имя из одного знака — не имя: у систем MOO II названия от трёх букв, а один
        # знак приходит от случайной светлой точки рядом.
        found.append({'spot': (x, y), 'size': size,
                      'name': name if len(name.replace(' ', '')) >= 2 else ''})
    return found


def unexplored(image, **rest):
    """Звёзды без имени — те, куда есть смысл слать разведчика."""
    return [star for star in stars(image, **rest) if not star['name']]


def nearest(spot, targets):
    """Ближайшая к точке цель: квадрат расстояния, без корня — сравнению он не нужен."""
    if not targets:
        return None
    return min(targets, key=lambda star: (star['spot'][0] - spot[0]) ** 2
               + (star['spot'][1] - spot[1]) ** 2)

def _shape_blobs(image, field, fits):
    """Пятна точек, подходящих под правило `fits(цвет)`: размер, ширина, высота, центр."""
    view = text.game_view(image).convert('RGB')
    x0, y0, width, height = field
    crop = view.crop((x0, y0, x0 + width, y0 + height))
    pixels = crop.load()
    lit = [[fits(pixels[col, row]) for col in range(width)] for row in range(height)]
    found = []
    for row in range(height):
        for col in range(width):
            if not lit[row][col]:
                continue
            stack = [(row, col)]
            lit[row][col] = False
            cells = []
            while stack:
                here_row, here_col = stack.pop()
                cells.append((here_row, here_col))
                for step_row in (-1, 0, 1):
                    for step_col in (-1, 0, 1):
                        next_row, next_col = here_row + step_row, here_col + step_col
                        if 0 <= next_row < height and 0 <= next_col < width                                 and lit[next_row][next_col]:
                            lit[next_row][next_col] = False
                            stack.append((next_row, next_col))
            rows = [cell[0] for cell in cells]
            cols = [cell[1] for cell in cells]
            found.append({
                'size': len(cells),
                'width': max(cols) - min(cols) + 1,
                'height': max(rows) - min(rows) + 1,
                'spot': (x0 + sum(cols) // len(cols), y0 + sum(rows) // len(rows)),
            })
    return found


# Окно «Select planet for Colony Base»: схема системы орбитами. Область снята со снимка
# `tools/moo2-scenes/colonybase-target.png`.
ORBIT_FIELD = (157, 147, 330, 185)


def planets(image, field=ORBIT_FIELD):
    """
    Планеты на схеме системы, от крупной к мелкой; звезда в середине отброшена.

    Отбираются ТЁПЛЫЕ точки (красный канал выше синего): орбиты нарисованы сине-фиолетовой
    чертой через всё окно, и обычным порогом яркости они слипаются в одно пятно шириной в
    триста точек — планеты в нём тонут. Тёплый отбор режет орбиты начисто, а заодно и
    холодные планеты (серые, голубые); это потеря, и она названа: у такого окна выбор
    делается перебором, и промах стоит одного лишнего нажатия, а не партии.

    Звезда — самое крупное тёплое пятно и всегда в середине; она отбрасывается первой.
    """
    warm = lambda colour: max(colour) >= 80 and colour[0] >= colour[2] + 10
    found = [blob for blob in _shape_blobs(image, field, warm)
             if blob['size'] >= 20 and 5 <= blob['width'] <= 30 and 5 <= blob['height'] <= 30]
    found.sort(key=lambda blob: -blob['size'])
    return found[1:] if found else []
