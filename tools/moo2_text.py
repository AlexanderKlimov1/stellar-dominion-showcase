# -*- coding: utf-8 -*-
"""
Чтение текста ОРИГИНАЛА с экрана: растровый шрифт MOO2 разбирается по буквам.

ЗАЧЕМ. `moo2_probe.py` узнаёт сцену в лицо и нажимает по постоянным точкам — этого
хватает, чтобы пройти меню, и НЕ хватает, чтобы играть. Партия, проведённая 23.09.2026,
показала это дословно: империя не построила ничего и не разведала ни одной звезды.
Причина не в мелочах — «нажать в такую-то точку» не работает там, где точка зависит от
состояния партии: список стройки у каждой колонии свой, звёзды на карте у каждой партии
свои. Чтобы решать, надо ЧИТАТЬ.

ПОЧЕМУ САМОДЕЛЬНОЕ, А НЕ ГОТОВОЕ. Tesseract и прочие обучены на сглаженном шрифте
печатной страницы, а здесь растровый шрифт 1990-х: буква высотой восемь точек, без
сглаживания, каждая нарисована раз и навсегда одинаково. Для такого шрифта распознавание
— не догадка, а поиск в словаре: вырезал букву, свернул в ключ, нашёл. Ошибок при этом не
бывает вовсе: либо буква в словаре есть, либо она незнакомая и честно отдаётся знаком «?».

КАК УСТРОЕНО.

* Кадр приводится к ИГРОВЫМ 640x480. Окно вдвое крупнее (scaler 2x), и в нём каждая точка
  шрифта размазана на четыре; в игровом размере буква — та самая картинка, какой её
  нарисовали, и ключ у неё устойчивый.
* Текст отделяется от фона порогом яркости: надписи MOO2 светлые, панели тёмные.
* Строки — по рядам, где есть светлое; буквы внутри строки — по столбцам. Широкий провал
  между буквами значит пробел; насколько широкий, считается от высоты строки, чтобы
  правило годилось и мелкому шрифту списков, и крупному шрифту заголовков.
* Ключ буквы — её силуэт: высота, ширина и строки из «#» и «.». Словарь (`glyphs.json`)
  лежит рядом со сценами и пополняется УЧЕНИЕМ: показали область и сказали, что там
  написано, — буквы разложились по ключам сами.

ЧЕГО ЗДЕСЬ НЕТ. Догадок. Незнакомая буква не подбирается «на кого похожа» — она
возвращается знаком «?», а её ключ можно тут же выучить. Так чтение остаётся либо верным,
либо честно неполным, и ошибка не уходит в решение молча.
"""

import io
import json
import os

from PIL import Image

SCENES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'moo2-scenes')
GLYPHS_FILE = os.path.join(SCENES_DIR, 'glyphs.json')

GAME_WIDTH, GAME_HEIGHT = 640, 480

# Порог «это текст, а не фон», и меряется он САМЫМ ЯРКИМ КАНАЛОМ, а не серой яркостью.
# Причина измерена на экране стройки: оранжевая надпись (231,138,32) в серой яркости даёт
# 154, а её же тень (130,40,16) — 64, и порог между ними ещё нашёлся бы; но зелёное имя
# звезды (0,255,0) даёт 150, а синяя подпись — и вовсе 29, хотя обе на экране яркие.
# Самый яркий канал одинаково честен ко всем цветам игры: у надписей он от 170, у теней и
# панелей — ниже 130.
BRIGHT = 150


def green(colour):
    """
    Зелёная надпись — та, у которой зелёный канал заметно выше красного и синего.

    Понадобилось это на экране выбора исследования: цена «50 RP» написана зелёным
    (4,121,0), а панель под ней — серым (146,142,142), то есть ЯРЧЕ текста. Порогом
    яркости их не разделить ни при каком числе, а цветом — в одну строку. Те же зелёные
    буквы стоят у имён своих систем на карте.
    """
    return colour[1] >= 60 and colour[1] >= colour[0] + 40 and colour[1] >= colour[2] + 40


def game_view(image):
    """Кадр в игровых 640x480: буква должна быть такой, какой её нарисовали."""
    if image.width == GAME_WIDTH and image.height == GAME_HEIGHT:
        return image
    return image.resize((GAME_WIDTH, GAME_HEIGHT), Image.NEAREST)


def read_glyphs():
    if not os.path.exists(GLYPHS_FILE):
        return {}
    with io.open(GLYPHS_FILE, encoding='utf-8') as file:
        return json.load(file)


def write_glyphs(glyphs):
    os.makedirs(SCENES_DIR, exist_ok=True)
    tmp = GLYPHS_FILE + '.tmp'
    with io.open(tmp, 'w', encoding='utf-8', newline='\n') as file:
        json.dump(glyphs, file, ensure_ascii=False, indent=1, sort_keys=True)
        file.write('\n')
    os.replace(tmp, GLYPHS_FILE)


def _mask(image, box, bright, fits=None):
    """
    Двоичная карта «здесь текст» в границах области.

    По умолчанию текстом считается всё достаточно светлое, но вызывающий вправе дать своё
    правило цвета (`fits`) — например `green`, когда надпись темнее панели под ней.
    """
    rule = fits or (lambda colour: max(colour) >= bright)
    view = game_view(image).convert('RGB')
    x, y, width, height = box if box else (0, 0, GAME_WIDTH, GAME_HEIGHT)
    crop = view.crop((x, y, x + width, y + height))
    pixels = crop.load()
    return [[rule(pixels[col, row]) for col in range(crop.width)]
            for row in range(crop.height)], x, y, crop.width, crop.height


def _runs(flags, gap=0):
    """Отрезки подряд идущих «да», склеенные через провалы не длиннее gap."""
    runs = []
    start = None
    empty = 0
    for index, flag in enumerate(flags):
        if flag:
            if start is None:
                start = index - empty if empty and runs else index
            empty = 0
        elif start is not None:
            empty += 1
            if empty > gap:
                runs.append((start, index - empty + 1))
                start = None
                empty = 0
    if start is not None:
        runs.append((start, len(flags) - empty))
    return runs


def glyph_key(mask, top, bottom, left, right):
    """
    Ключ буквы — её силуэт строками из «#» и «.».

    Обрезается он по СВОИМ чернилам, а не по границам строки, и это не мелочь: высота
    строки зависит от того, попалась ли в ней буква с хвостом («g») или заглавная, — и
    один и тот же «о» в строке «Housing» и в строке «Spy» получал бы разные ключи, то
    есть словарь пришлось бы учить заново на каждой строке.

    Высота и ширина стоят в начале ключа нарочно: по ним видно глазами, что за буква
    выучена, и одинаковые силуэты разных кеглей не сливаются.
    """
    rows = [row for row in range(top, bottom)
            if any(mask[row][col] for col in range(left, right))]
    if not rows:
        return '0x0:'
    drawn = [''.join('#' if mask[row][col] else '.' for col in range(left, right))
             for row in range(rows[0], rows[-1] + 1)]
    return '%dx%d:%s' % (right - left, len(drawn), '/'.join(drawn))


def lines(image, box=None, bright=BRIGHT, min_height=4, fits=None):
    """
    Строки текста области: каждая — свои буквы силуэтами и своё место на экране.

    Отдаёт список словарей: `box` строки в игровых координатах, `glyphs` — список
    (ключ, левый край, правый край), `spaces` — перед какой буквой стоит пробел.
    """
    mask, offset_x, offset_y, width, height = _mask(image, box, bright, fits)
    row_has = [any(row) for row in mask]
    found = []
    for top, bottom in _runs(row_has):
        if bottom - top < min_height:
            continue
        col_has = [any(mask[row][col] for row in range(top, bottom)) for col in range(width)]
        # Столбцы НЕ склеиваются через провал: в шрифте описаний буквы стоят через одну
        # пустую колонку, и склейка «через один» слепляла строку в слова целиком —
        # тридцать четыре буквы превращались в восемь. Пустые колонки ВНУТРИ буквы у
        # растрового шрифта не встречаются вовсе.
        marks = _runs(col_has, gap=0)
        if not marks:
            continue
        glyphs = []
        for left, right in marks:
            # Верх и низ у каждой буквы свои: у «g» хвост ниже строки, у «o» нет верха.
            rows_here = [row for row in range(top, bottom)
                         if any(mask[row][col] for col in range(left, right))]
            glyphs.append((glyph_key(mask, top, bottom, left, right),
                           left, right, rows_here[0] - top, rows_here[-1] - top))
        found.append({
            'box': (offset_x + marks[0][0], offset_y + top,
                    marks[-1][1] - marks[0][0], bottom - top),
            'height': bottom - top,
            'glyphs': glyphs,
        })
    return found


def _with_spaces(line):
    """
    Текст строки по словарю: широкий провал считается пробелом.

    Треть высоты измерена по трём шрифтам игры: внутри слова провал 1–3 точки, между
    словами 4–16 (описание выключено по ширине, и там пробелы растягиваются).
    """
    glyphs = line['glyphs']
    space = max(3, line['height'] // 3)
    parts = []
    previous_right = None
    for key, left, right, _top, _bottom in glyphs:
        if previous_right is not None and left - previous_right >= space:
            parts.append(' ')
        parts.append(key)
        previous_right = right
    return parts


def read(image, box=None, bright=BRIGHT, glyphs=None, min_height=4, fits=None):
    """
    Текст области строками; незнакомая буква — «?».

    `min_height` — самая низкая строка, которую считаем текстом. По умолчанию четыре
    точки (мелкий шрифт списков), но на карте галактики его поднимают до семи: лучи
    значка звезды попадают в полоску под ней широкой кляксой 12x5, и без этого условия
    каждая вторая звезда «имела имя», то есть считалась разведанной.
    """
    book = read_glyphs() if glyphs is None else glyphs
    out = []
    for line in lines(image, box, bright, min_height, fits):
        text = ''.join(' ' if part == ' ' else book.get(part, '?')
                       for part in _with_spaces(line))
        out.append({'text': text.strip(), 'box': line['box']})
    return out


def learn(image, box, expected, bright=BRIGHT, fits=None):
    """
    Учение: показали область и сказали, что в ней написано, — буквы легли в словарь.

    `expected` — список строк, по строке на строку текста. Пробелы в ожидаемой строке
    пропускаются: где стоит пробел, картинка решает сама (по ширине провала), а словарю
    пробел не нужен.

    Отдаёт, сколько букв выучено и сколько строк не совпало по длине: несовпадение значит,
    что буквы склеились или строка прочиталась не та, — такую строку учить нельзя, иначе
    словарь наполнится враньём.
    """
    book = read_glyphs()
    learned, refused = 0, []
    found = lines(image, box, bright, fits=fits)
    for index, line in enumerate(found):
        if index >= len(expected):
            break
        want = expected[index].replace(' ', '')
        keys = [part for part in _with_spaces(line) if part != ' ']
        if len(keys) != len(want):
            refused.append('%s: букв на экране %d, в ожидаемом %d'
                           % (expected[index], len(keys), len(want)))
            continue
        for key, letter in zip(keys, want):
            if book.get(key) not in (None, letter):
                refused.append('%s: силуэт уже выучен как «%s», а теперь «%s»'
                               % (expected[index], book[key], letter))
                continue
            if key not in book:
                learned += 1
            book[key] = letter
    write_glyphs(book)
    return learned, refused, len(found)
