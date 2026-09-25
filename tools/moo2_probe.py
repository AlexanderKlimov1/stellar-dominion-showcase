# -*- coding: utf-8 -*-
"""
Наблюдатель за ОРИГИНАЛОМ: запускает MOO2 в DOSBox, узнаёт сцены в лицо и ходит по ним
вместо человека.

ЗАЧЕМ ЭТО ЕСТЬ. Разметка наших сцен сверяется со снимками настоящей игры
(`docs/moo2/`), а половина окон MOO II нигде не выложена — их приходится снимать самим.
Руками это значит: запусти DOSBox, пройди меню, нажми PrintScreen, обрежь, назови файл.
Скрипт делает то же самое сам: ведёт игру по таблице правил, а КАЖДУЮ невиданную сцену
кладёт снимком в `tools/moo2-scenes/` и записывает её примету. Отсюда и два применения:
пополнять `docs/moo2/`, не размечая ничего руками, и проверять, что наша сцена похожа на
свой источник.

КАК ОН УЗНАЁТ СЦЕНУ. Не по тексту (OCR тут не нужен и не годится: шрифт игры растровый и
мелкий), а по ПРИМЕТЕ кадра — серой сетке 32x24 средних яркостей. Две картинки одной
сцены отличаются мелочами (мигающий курсор, бегущая звезда), и средние по клеткам их не
замечают; разные сцены расходятся на десятки единиц. Примета лежит рядом со снимком в
`scenes.json`, и файл этот — ДАННЫЕ: новая сцена добавляется записью, а не правкой кода.

КАК ОН ИГРАЕТ. В том же файле у сцены лежит `actions` — что на ней нажать, чтобы двинуться
дальше. Это и есть «ИИ вместо человека»: скрипт смотрит на экран, называет сцену, делает
её действия, ждёт смены картинки и смотрит снова. Незнакомая сцена — не поломка, а находка:
она сохраняется, и обход останавливается, чтобы человек (или следующий запуск) назвал её и
сказал, что нажимать.

ЧЕГО ЗДЕСЬ НЕТ. Игры «по-настоящему»: скрипт не понимает, что происходит в партии, и не
принимает решений по правилам MOO II. Он ходит по сценам, а не играет в стратегию, — этого
и достаточно, чтобы собрать снимки и убедиться, что путь по меню цел.

Запуск (из корня проекта):
    python tools\\moo2_probe.py watch            — только смотреть: снимки сцен, без нажатий
    python tools\\moo2_probe.py play [--steps N] — идти по сценам за человека
    python tools\\moo2_probe.py shot [--name X]  — один снимок текущего экрана
    python tools\\moo2_probe.py stop             — закрыть DOSBox

Сам не запустит игру дважды: если окно DOSBox уже открыто, работает с ним.
"""

import argparse
import ctypes
import ctypes.wintypes
import io
import json
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

from PIL import Image
import win32con
import win32gui
import win32ui

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCENES_DIR = os.path.join(ROOT, 'tools', 'moo2-scenes')
SCENES_FILE = os.path.join(SCENES_DIR, 'scenes.json')
REPORT_FILE = os.path.join(SCENES_DIR, 'report.txt')

# Ярлык на рабочем столе ведёт к запускателю на Tcl, а тот всего лишь собирает конфиг и
# зовёт DOSBox. Зовём DOSBox напрямую теми же конфигами: запускатель показал бы своё окно
# и ждал нажатия, а нам нужно, чтобы игра поднялась сама.
DOSBOX = r'C:\MOO2\DOSBox\DOSBox.exe'
GAME_DIR = r'C:\MOO2\v1_50_26'
CONFS = [os.path.join(GAME_DIR, '150', 'dosbox-150.conf'),
         os.path.join(GAME_DIR, '150', 'dosbox.conf')]

# Игра внутри рисует 640x480, а окно вдвое крупнее (scaler 2x). Все правила пишутся в
# ИГРОВЫХ координатах: поменяется масштаб окна — таблица правил останется верной.
GAME_WIDTH, GAME_HEIGHT = 640, 480

# Примета кадра: сетка средних яркостей. 32x24 — та же пропорция 4:3, и 768 чисел хватает,
# чтобы различить окна, но мало, чтобы заметить мигание курсора.
SIGN_COLS, SIGN_ROWS = 32, 24

# Насколько приметы считаются одной сценой. Число измерено: у двух кадров одного экрана
# расхождение 0–4, у разных экранов — от 15 и выше.
SAME_SCENE = 8.0


# --- окно DOSBox ---------------------------------------------------------------------

def find_window():
    """Окно игры; пусто — игра не запущена."""
    found = []

    def visit(hwnd, _):
        if not win32gui.IsWindowVisible(hwnd):
            return
        # Окон у DOSBox ДВА: поле игры (класс SDL_app) и «DOSBox Status Window» — консоль
        # с журналом. Снимать надо первое, и опознаётся оно классом, а не заголовком:
        # заголовок у поля меняется на ходу (в нём идёт счётчик тактов).
        if win32gui.GetClassName(hwnd) == 'SDL_app':
            found.append((hwnd, win32gui.GetWindowText(hwnd)))

    win32gui.EnumWindows(visit, None)
    return found[0] if found else (None, None)


def launch(wait_seconds=25):
    """Поднимает игру, если её нет, ждёт окна и ставит его целиком на экран."""
    hwnd, title = find_window()
    if hwnd:
        place_window(hwnd)
        return hwnd, title
    command = [DOSBOX]
    for conf in CONFS:
        if os.path.exists(conf):
            command += ['-conf', conf]
    subprocess.Popen(command, cwd=GAME_DIR, close_fds=True)
    until = time.time() + wait_seconds
    while time.time() < until:
        hwnd, title = find_window()
        if hwnd:
            time.sleep(3)   # окну нужно время, чтобы нарисовать первый кадр
            place_window(hwnd)
            return hwnd, title
        time.sleep(0.5)
    raise SystemExit('DOSBox не поднялся за %d секунд' % wait_seconds)


# --- уступка управления человеку ------------------------------------------------------
#
# Инструмент водит НАСТОЯЩУЮ мышь, и это значит, что он и человек тянут один и тот же
# рычаг. Пока правил не было, всякое движение руки посреди партии ломало ход: человек
# открывал окно, инструмент тем же мгновением жал «конец хода», и оба мешали друг другу.
#
# Правило простое и проверяемое: инструмент ПОМНИТ, куда сам поставил указатель. Указатель
# оказался не там — значит, его двигали не мы, и управление наше кончилось. Дальше
# инструмент ничего не делает и ждёт тишины: тридцать секунд без движения указателя — и
# он продолжает партию с того места, где остановился.

#: Куда указатель поставил сам инструмент; пусто — он его ещё не ставил.
_expected_cursor = None

#: Сколько секунд тишины считать концом вмешательства.
HUMAN_QUIET_SECONDS = 30.0

#: Насколько указатель может «дрожать», оставаясь на месте (точек экрана).
HUMAN_JITTER = 3


def cursor_now():
    point = ctypes.wintypes.POINT()
    ctypes.windll.user32.GetCursorPos(ctypes.byref(point))
    return point.x, point.y


def _moved_by_hand():
    """Двигали ли указатель не мы: он не там, куда его поставил инструмент."""
    if _expected_cursor is None:
        return False
    here = cursor_now()
    return (abs(here[0] - _expected_cursor[0]) > HUMAN_JITTER
            or abs(here[1] - _expected_cursor[1]) > HUMAN_JITTER)


def yield_to_human(quiet=HUMAN_QUIET_SECONDS):
    """
    Если управление перехватили, ждёт тишины и только потом отдаёт ход инструменту.

    Отдаёт `True`, если пауза была, — вызывающему это нужно, чтобы посмотреть на экран
    заново: человек мог открыть другое окно, и сцена уже не та, что была до перехвата.
    """
    if not _moved_by_hand():
        return False
    print('    управление перехвачено — жду %d секунд тишины' % quiet, flush=True)
    still = 0.0
    last = cursor_now()
    while still < quiet:
        time.sleep(0.5)
        here = cursor_now()
        if (abs(here[0] - last[0]) > HUMAN_JITTER
                or abs(here[1] - last[1]) > HUMAN_JITTER):
            still = 0.0
            last = here
        else:
            still += 0.5
    print('    тишина %d секунд — продолжаю' % quiet, flush=True)
    return True


def focus(hwnd):
    """
    Поднимает окно игры наверх: нажатия идут тому, кто в фокусе.

    Простого `SetForegroundWindow` мало, и это стоило партии. Windows не отдаёт фокус
    процессу, который сам не на переднем плане («foreground lock»): вызов молча ничего
    не делает, мышь исправно едет в нужную точку, нажатие уходит в чужое окно — а со
    стороны это выглядит как неверные координаты. Так обход четыре шага подряд «жал»
    ACCEPT в окне новой игры и оставался на нём же; то же нажатие, сделанное отдельной
    командой, срабатывало с первого раза.

    Замок снимается известным приёмом: касание ALT считается вводом пользователя, и
    следующий за ним перенос фокуса система пропускает. Поэтому здесь не одна попытка,
    а несколько, и каждая ПРОВЕРЯЕТСЯ — у кого фокус на самом деле.
    """
    yield_to_human()
    for attempt in range(3):
        if ctypes.windll.user32.GetForegroundWindow() == hwnd:
            time.sleep(0.2)
            return True
        try:
            win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
            if attempt:
                press('MENU')       # то самое касание ALT
            win32gui.SetForegroundWindow(hwnd)
        except Exception:
            # Отказ — это не поломка: смотреть на окно можно и без фокуса, а нажатия
            # проверит следующий круг.
            pass
        time.sleep(0.3)
    return ctypes.windll.user32.GetForegroundWindow() == hwnd


def work_area():
    """Рабочая область экрана — без панели задач (`SPI_GETWORKAREA`)."""
    rect = ctypes.wintypes.RECT()
    ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0)
    return rect.left, rect.top, rect.right, rect.bottom


#: Куда и каким размером окно поставил сам инструмент; пусто — ещё не ставил.
_placed = None


def keep_placed(hwnd):
    """
    Возвращает окно на место, если оно СМЕНИЛО РАЗМЕР или вылезло за рабочую область.

    Зачем это отдельно от `place_window`. Окно ставилось РОВНО ОДИН РАЗ — при запуске, —
    а DOSBox в этот миг ещё показывает DOS в текстовом режиме, и окно маленькое. Стоит
    игре начаться, как видеорежим переключается на 640x480 со скалером 2x: окно вырастает
    до 1280x960 и уезжает нижним краем за экран. Второй раз выравнивание звалось только у
    СВЁРНУТОГО окна, то есть в этом случае не звалось никогда.

    Заметить это трудно нарочно: кадр снимается `PrintWindow`, то есть самим окном, и на
    снимке всё цело, — а мышь ведётся АБСОЛЮТНЫМИ координатами экрана, и точка ниже края
    не уходит за край, а прижимается к нему. Промахи поэтому начинаются ровно по нижней
    полосе MOO II, где стоят COLONIES, PLANETS, FLEETS и «конец хода», и выглядят как
    «инструмент жмёт не туда», а не как «окно не на месте».

    Двигаем по двум приметам, и обе нарочно узкие: сменился размер (тот самый
    видеорежим) или окно не помещается целиком. Просто сдвинутое человеком окно, которое
    при этом видно целиком, не трогаем — инструмент и так отнимает мышь, отнимать ещё и
    право подвинуть окно незачем.
    """
    try:
        left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    except Exception:
        return False
    width, height = right - left, bottom - top
    area_left, area_top, area_right, area_bottom = work_area()
    outside = (left < area_left or top < area_top
               or right > area_right or bottom > area_bottom)
    resized = _placed is not None and (width, height) != (_placed[2], _placed[3])
    if not outside and not resized:
        return False
    return place_window(hwnd)


def place_window(hwnd):
    """
    Двигает окно игры так, чтобы ОНО ЦЕЛИКОМ помещалось в рабочую область.

    Зачем. Мышь ведётся абсолютными координатами экрана (`SendInput`), и точка за краем
    экрана не «уходит за край», а ПРИЖИМАЕТСЯ к нему: нажатие приходит не туда, куда
    целились. Окно игры высотой под тысячу точек легко выезжает под панель задач нижним
    краем — и промахи начинаются как раз по нижней полосе, где у MOO II стоят кнопки
    разделов и «конец хода».

    Отдаёт `True`, если окно пришлось двигать.
    """
    # СВЁРНУТОЕ окно сперва разворачиваем. Свёрнутым оно лежит в координатах −32000, и
    # `MoveWindow` по нему не работает вовсе — а заодно оно не отдаёт кадра (`grab` шесть
    # раз подряд получает пустоту) и ловит нажатия мимо. Три беды из одной причины.
    if win32gui.IsIconic(hwnd):
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        time.sleep(0.6)
    left, top, right, bottom = win32gui.GetWindowRect(hwnd)
    width, height = right - left, bottom - top
    area_left, area_top, area_right, area_bottom = work_area()
    # СТАВИМ ПО ЦЕНТРУ, а не подвигаем на минимум. Прежде окно лишь вдвигалось в рабочую
    # область ближайшим движением, и у окна ровно по высоте экрана «ближайшее» значило
    # «впритык к нижнему краю»: хватало панели задач, чтобы нижняя полоса игры снова
    # оказалась под ней. Середина запас делит пополам и сверху, и снизу.
    place_x = max(area_left, area_left + (area_right - area_left - width) // 2)
    place_y = max(area_top, area_top + (area_bottom - area_top - height) // 2)
    global _placed
    _placed = (place_x, place_y, width, height)
    if (place_x, place_y) == (left, top):
        return False
    win32gui.MoveWindow(hwnd, place_x, place_y, width, height, True)
    time.sleep(0.4)
    print('окно игры сдвинуто в рабочую область: (%d,%d) -> (%d,%d)'
          % (left, top, place_x, place_y), flush=True)
    return True


def client_box(hwnd):
    """Прямоугольник ПОЛЯ игры на экране, без рамки окна."""
    left, top, right, bottom = win32gui.GetClientRect(hwnd)
    x, y = win32gui.ClientToScreen(hwnd, (left, top))
    return x, y, right - left, bottom - top


# --- снимок --------------------------------------------------------------------------

def _bitmap(hwnd, width, height, draw):
    """Общая обвязка: завести холст размером width x height и отдать его картинкой."""
    window_dc = win32gui.GetWindowDC(hwnd)
    source = win32ui.CreateDCFromHandle(window_dc)
    memory = source.CreateCompatibleDC()
    bitmap = win32ui.CreateBitmap()
    bitmap.CreateCompatibleBitmap(source, width, height)
    memory.SelectObject(bitmap)
    try:
        draw(source, memory)
        info = bitmap.GetInfo()
        bits = bitmap.GetBitmapBits(True)
        # Пустой или недописанный холст — обычное дело, пока окно перерисовывается:
        # `PrintWindow` возвращает то ничего, то половину. Раньше это долетало до PIL и
        # роняло весь прогон посреди партии («not enough image data»), причём каждый раз
        # на разном ходу. Теперь «не получилось» — это пусто, а решает повтор выше.
        needed = info['bmWidth'] * info['bmHeight'] * 4
        if not bits or len(bits) < needed:
            return None
        return Image.frombuffer('RGB', (info['bmWidth'], info['bmHeight']), bits,
                                'raw', 'BGRX', 0, 1)
    finally:
        win32gui.DeleteObject(bitmap.GetHandle())
        memory.DeleteDC()
        source.DeleteDC()
        win32gui.ReleaseDC(hwnd, window_dc)


def grab(hwnd, tries=6, pause=0.4):
    """
    Снимок ПОЛЯ игры, без рамки окна; пустой кадр — повод повторить.

    Повторы тут не перестраховка, а необходимость: за одну партию в полсотни ходов
    инструмент снимает окно тысячи раз, и одного пустого кадра хватало, чтобы уронить
    весь прогон. Шесть попыток по четыре десятых секунды покрывают перерисовку окна с
    запасом; если и после них кадра нет, это уже не рябь, а закрытое окно, и молчать
    об этом нельзя.
    """
    for attempt in range(tries):
        # Свёрнутое окно кадра не отдаёт: разворачиваем, а не ждём у моря погоды.
        if win32gui.IsIconic(hwnd):
            place_window(hwnd)
        # А заодно сверяем, стоит ли окно там, где мы его оставили: игра переключает
        # видеорежим посреди работы, и окно вырастает вдвое. Проверка идёт при КАЖДОМ
        # взгляде на экран, потому что дороже она не стоит ничего (один GetWindowRect),
        # а пропущенный рост окна портит все нажатия до конца партии.
        keep_placed(hwnd)
        field = _grab_once(hwnd)
        if field is not None:
            return field
        time.sleep(pause)
    raise SystemExit('окно игры не отдаёт кадр: %d попыток подряд пусто' % tries)


def _grab_once(hwnd):
    """
    Одна попытка снять поле игры; пусто — кадр не готов.

    Снимается самим окном (`PrintWindow` с флагом 2, «нарисуй всё содержимое»), а не
    куском рабочего стола, и это важнее, чем кажется: окно игры высотой 960 точек не
    помещается над панелью задач, и снимок с экрана обрезал бы нижнюю полосу игры —
    ту самую, где у MOO II стоят кнопки. Заодно это развязывает руки: окно может быть
    чем-то прикрыто, а кадр всё равно снимется целым.

    Если OpenGL всё же отдаст чёрный кадр (бывает у старых драйверов), берём запасной
    путь — копию рабочего стола: лучше обрезанный снимок, чем никакого.
    """
    window_left, window_top, window_right, window_bottom = win32gui.GetWindowRect(hwnd)
    window_width = window_right - window_left
    window_height = window_bottom - window_top
    client_x, client_y, client_width, client_height = client_box(hwnd)
    inside_x = client_x - window_left
    inside_y = client_y - window_top

    def print_window(_source, memory):
        ctypes.windll.user32.PrintWindow(hwnd, memory.GetSafeHdc(), 2)

    image = _bitmap(hwnd, window_width, window_height, print_window)
    if image is not None:
        field = image.crop((inside_x, inside_y,
                            inside_x + client_width, inside_y + client_height))
        # `getextrema` у пустой картинки отдаёт не пару чисел, а `None`.
        extremes = field.convert('L').getextrema()
        if extremes and extremes[1] > 0:
            return field

    def from_desktop(source, memory):
        memory.BitBlt((0, 0), (client_width, client_height), source,
                      (client_x, client_y), win32con.SRCCOPY)

    return _bitmap(win32gui.GetDesktopWindow(), client_width, client_height, from_desktop)


def signature(image, region=None):
    """
    Примета кадра: средние яркости по сетке, числами 0–255.

    `region` — часть кадра в ИГРОВЫХ координатах (x, y, ширина, высота), по которой сцену
    и узнают. Нужна она не для красоты: в главном меню оригинала ползут титры, и примета
    всего кадра у одного и того же меню выходит каждый раз новой. Область берут по тому
    куску, который стоит на месте, — у меню это его панель с кнопками.
    """
    field = image
    if region is not None:
        x, y, width, height = region
        scale_x = image.width / GAME_WIDTH
        scale_y = image.height / GAME_HEIGHT
        field = image.crop((int(x * scale_x), int(y * scale_y),
                            int((x + width) * scale_x), int((y + height) * scale_y)))
    small = field.convert('L').resize((SIGN_COLS, SIGN_ROWS), Image.BOX)
    return list(small.tobytes())


def distance(one, other):
    """Среднее расхождение двух примет; 0 — кадры неотличимы."""
    if len(one) != len(other):
        return 255.0
    return sum(abs(a - b) for a, b in zip(one, other)) / len(one)


# --- справочник сцен -----------------------------------------------------------------

def read_scenes():
    if not os.path.exists(SCENES_FILE):
        return {'version': 1, 'scenes': []}
    return json.load(io.open(SCENES_FILE, encoding='utf-8'))


def write_scenes(book):
    os.makedirs(SCENES_DIR, exist_ok=True)
    tmp = SCENES_FILE + '.tmp'
    with io.open(tmp, 'w', encoding='utf-8', newline='\n') as file:
        json.dump(book, file, ensure_ascii=False, indent=2)
    os.replace(tmp, SCENES_FILE)


def recognise(book, image):
    """
    Какая это сцена и насколько уверенно; пусто — такой мы ещё не видели.

    Примета считается ПО ОБЛАСТИ КАЖДОЙ сцены: у одной она про всё окно, у другой — про
    кусок, который не шевелится. Поэтому сравнение идёт не «примета с приметой», а «кадр
    с каждой записью справочника».
    """
    def closest(scenes):
        found, found_gap = None, None
        for scene in scenes:
            gap = distance(signature(image, scene.get('region')), scene['signature'])
            if found_gap is None or gap < found_gap:
                found, found_gap = scene, gap
        return found, found_gap

    # Сперва окна ПОВЕРХ (признак `over`), потом всё остальное. Иначе карта галактики
    # узнаётся сквозь модальное окно: её примета снята с нижней полосы, а полоса из-под
    # окна видна — обход жал «конец хода», пока игра ждала ответа про лидера.
    over, over_gap = closest([one for one in book['scenes'] if one.get('over')])
    if over is not None and over_gap <= SAME_SCENE:
        return over, over_gap

    best, best_gap = closest([one for one in book['scenes'] if not one.get('over')])
    if best is not None and best_gap <= SAME_SCENE:
        return best, best_gap
    return None, min(gap for gap in (over_gap, best_gap) if gap is not None) if (
        over_gap is not None or best_gap is not None) else None


def remember(book, image, prefix='unknown'):
    """Кладёт невиданную сцену снимком и приметой: её потом назовут и опишут."""
    number = 1 + sum(1 for scene in book['scenes'] if scene['name'].startswith(prefix))
    name = '%s-%02d' % (prefix, number)
    os.makedirs(SCENES_DIR, exist_ok=True)
    path = os.path.join(SCENES_DIR, name + '.png')
    image.save(path)
    scene = {
        'name': name,
        'note': 'снята сама, ещё не названа',
        'signature': signature(image),
        'actions': [],
    }
    book['scenes'].append(scene)
    write_scenes(book)
    return scene, path


# --- нажатия ---------------------------------------------------------------------------

SendInput = ctypes.windll.user32.SendInput
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_ABSOLUTE = 0x8000
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_SCANCODE = 0x0008

# Коды клавиш пишутся ИМЕНАМИ в таблице правил: «RETURN» читается, 0x0D — нет.
KEYS = {
    'RETURN': 0x0D, 'ESCAPE': 0x1B, 'SPACE': 0x20, 'TAB': 0x09,
    'UP': 0x26, 'DOWN': 0x28, 'LEFT': 0x25, 'RIGHT': 0x27,
    'F1': 0x70, 'F2': 0x71, 'F3': 0x72, 'F4': 0x73, 'F5': 0x74, 'F6': 0x75,
    'F10': 0x79, 'F11': 0x7A, 'F12': 0x7B,
    # ALT сама по себе игре не нужна — ею снимается запрет Windows на перенос фокуса
    # (см. `focus`).
    'MENU': 0x12,
}


class _MouseInput(ctypes.Structure):
    _fields_ = [('dx', ctypes.c_long), ('dy', ctypes.c_long),
                ('mouseData', ctypes.c_ulong), ('dwFlags', ctypes.c_ulong),
                ('time', ctypes.c_ulong), ('dwExtraInfo', ctypes.POINTER(ctypes.c_ulong))]


class _KeyInput(ctypes.Structure):
    _fields_ = [('wVk', ctypes.c_ushort), ('wScan', ctypes.c_ushort),
                ('dwFlags', ctypes.c_ulong), ('time', ctypes.c_ulong),
                ('dwExtraInfo', ctypes.POINTER(ctypes.c_ulong))]


class _InputUnion(ctypes.Union):
    _fields_ = [('mi', _MouseInput), ('ki', _KeyInput)]


class _Input(ctypes.Structure):
    _fields_ = [('type', ctypes.c_ulong), ('union', _InputUnion)]


def _send(kind, payload):
    event = _Input(kind, payload)
    SendInput(1, ctypes.byref(event), ctypes.sizeof(event))


def _screen_size():
    user32 = ctypes.windll.user32
    return user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)


def click(hwnd, game_x, game_y, button='left'):
    """
    Нажатие в ИГРОВЫХ координатах (640x480): пересчитывается в точку экрана.

    Мышь ведётся настоящим вводом (`SendInput`), а не сообщением окну: DOSBox читает
    ввод через SDL, и подложенное сообщение он просто не увидит.
    """
    x, y, width, height = client_box(hwnd)
    screen_x = x + int(game_x * width / GAME_WIDTH)
    screen_y = y + int(game_y * height / GAME_HEIGHT)
    full_width, full_height = _screen_size()
    absolute_x = int(screen_x * 65535 / max(1, full_width - 1))
    absolute_y = int(screen_y * 65535 / max(1, full_height - 1))
    flags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE
    _send(0, _InputUnion(mi=_MouseInput(absolute_x, absolute_y, 0, flags, 0, None)))
    # Запоминаем, куда поставили указатель: по расхождению с этим местом и узнаётся, что
    # за мышь взялся человек (см. `yield_to_human`).
    global _expected_cursor
    _expected_cursor = cursor_now()
    time.sleep(0.05)
    down = MOUSEEVENTF_RIGHTDOWN if button == 'right' else MOUSEEVENTF_LEFTDOWN
    up = MOUSEEVENTF_RIGHTUP if button == 'right' else MOUSEEVENTF_LEFTUP
    _send(0, _InputUnion(mi=_MouseInput(0, 0, 0, down, 0, None)))
    time.sleep(0.05)
    _send(0, _InputUnion(mi=_MouseInput(0, 0, 0, up, 0, None)))


def drag(hwnd, start, end, hold=0.25):
    """
    Перетаскивание из одной игровой точки в другую: нажать, провести, отпустить.

    Нужно ровно для одного, зато важного: в MOO II жителя переводят с работы на работу,
    перетаскивая его фигурку из ряда в ряд. Нажатием этого не сделать, а без этого
    голодающую колонию нечем спасти — постройка фермы придёт через десяток ходов, а люди
    умирают каждый ход.

    Мышь ведётся настоящим вводом, поэтому путь проходится НЕСКОЛЬКИМИ шагами: рывок из
    точки в точку игра понимает как «мышь дёрнулась», и перетаскивание не засчитывается.
    """
    x, y, width, height = client_box(hwnd)
    def to_screen(spot):
        return (x + int(spot[0] * width / GAME_WIDTH),
                y + int(spot[1] * height / GAME_HEIGHT))

    def move(point):
        full_width, full_height = _screen_size()
        _send(0, _InputUnion(mi=_MouseInput(
            int(point[0] * 65535 / max(1, full_width - 1)),
            int(point[1] * 65535 / max(1, full_height - 1)),
            0, MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE, 0, None)))

    from_point, to_point = to_screen(start), to_screen(end)
    move(from_point)
    time.sleep(0.15)
    _send(0, _InputUnion(mi=_MouseInput(0, 0, 0, MOUSEEVENTF_LEFTDOWN, 0, None)))
    time.sleep(hold)
    steps = 8
    for step in range(1, steps + 1):
        move((from_point[0] + (to_point[0] - from_point[0]) * step // steps,
              from_point[1] + (to_point[1] - from_point[1]) * step // steps))
        time.sleep(0.05)
    time.sleep(hold)
    _send(0, _InputUnion(mi=_MouseInput(0, 0, 0, MOUSEEVENTF_LEFTUP, 0, None)))
    time.sleep(0.2)
    global _expected_cursor
    _expected_cursor = cursor_now()


def press(name):
    """Нажатие клавиши по имени из таблицы KEYS."""
    code = KEYS.get(name.upper())
    if code is None and len(name) == 1:
        code = ord(name.upper())
    if code is None:
        raise SystemExit('неизвестная клавиша: %s' % name)
    _send(1, _InputUnion(ki=_KeyInput(code, 0, 0, 0, None)))
    time.sleep(0.05)
    _send(1, _InputUnion(ki=_KeyInput(code, 0, KEYEVENTF_KEYUP, 0, None)))


def do_action(hwnd, action):
    """Одно действие правила: нажать мышью, нажать клавишу или подождать."""
    if 'click' in action:
        click(hwnd, action['click'][0], action['click'][1], action.get('button', 'left'))
    elif 'key' in action:
        press(action['key'])
    time.sleep(action.get('wait', 0.8))


# --- обход ----------------------------------------------------------------------------

def settle(hwnd, tries=12, pause=0.5):
    """
    Ждёт, пока картинка перестанет меняться, и отдаёт установившийся кадр.

    Иначе сцену узнают посреди её же появления: у MOO II половина окон выезжает, а
    заставка и вовсе живёт своей жизнью.
    """
    previous = None
    image = grab(hwnd)
    for _ in range(tries):
        sign = signature(image)
        if previous is not None and distance(sign, previous) < 2.0:
            return image, sign
        previous = sign
        time.sleep(pause)
        image = grab(hwnd)
    return image, signature(image)


def walk(hwnd, book, steps, playing, log):
    """Ходит по сценам: узнал — сделал её действия — посмотрел снова."""
    seen = []
    for step in range(1, steps + 1):
        focus(hwnd)
        image, _ = settle(hwnd)
        scene, gap = recognise(book, image)
        if scene is None:
            scene, path = remember(book, image)
            line = ('%02d. НОВАЯ СЦЕНА: %s (ближайшая знакомая — на %.1f), снимок %s'
                    % (step, scene['name'], gap if gap is not None else 255, path))
            print(line, flush=True)
            log.append(line)
            if playing:
                log.append('    обход остановлен: назовите сцену в scenes.json и '
                           'скажите, что на ней нажимать')
                print(log[-1], flush=True)
                break
            time.sleep(1.5)
            continue

        line = '%02d. %s (расхождение %.1f) — %s' % (step, scene['name'], gap,
                                                     scene.get('note', ''))
        print(line, flush=True)
        log.append(line)
        seen.append(scene['name'])

        if not playing:
            time.sleep(1.5)
            continue

        actions = scene.get('actions') or []
        if not actions:
            log.append('    у сцены нет действий: дальше идти нечем')
            print(log[-1], flush=True)
            break
        for action in actions:
            do_action(hwnd, action)
    return seen


def write_report(mode, title, log, seen):
    os.makedirs(SCENES_DIR, exist_ok=True)
    with io.open(REPORT_FILE, 'w', encoding='utf-8', newline='\n') as file:
        file.write('ОБХОД СЦЕН ОРИГИНАЛА — %s\n\n' % time.strftime('%Y-%m-%d %H:%M:%S'))
        file.write('  режим       %s\n' % mode)
        file.write('  окно        %s\n' % title)
        file.write('  снимки      %s\n\n' % SCENES_DIR)
        file.write('ХОД ОБХОДА\n')
        for line in log:
            file.write('  %s\n' % line)
        if seen:
            file.write('\nПРОЙДЕНО СЦЕН: %d (%s)\n' % (len(seen), ', '.join(seen)))
    print('отчёт: %s' % REPORT_FILE)




# Разделы нижней полосы карты — в игровых координатах. Обход открывает каждый, снимает и
# возвращается: это и есть сбор снимков «на сравнение с нашими экранами».
TOUR_STOPS = [
    ('colonies', [16, 447]),
    ('planets', [61, 447]),
    ('fleets', [110, 447]),
    ('leaders', [172, 447]),
    ('races', [211, 447]),
    ('info', [248, 447]),
]


def tour(hwnd, book):
    """
    Обход разделов: открыть — снять — вернуться.

    Возврат делается Esc, а если окно его не понимает — нажатием по кнопке возврата в
    правом нижнем углу: у MOO II она там у всех полноэкранных консолей.
    """
    taken = []
    for name, spot in TOUR_STOPS:
        focus(hwnd)
        do_action(hwnd, {'click': spot, 'wait': 2.5})
        image, _ = settle(hwnd)
        path = os.path.join(SCENES_DIR, 'tour-%s.png' % name)
        os.makedirs(SCENES_DIR, exist_ok=True)
        image.save(path)
        known, gap = recognise(book, image)
        taken.append('%-9s -> %s%s' % (name, path,
                                       '' if known is None else ' (узнана: %s)' % known['name']))
        print(taken[-1], flush=True)
        press('ESCAPE')
        time.sleep(1.5)
        image, _ = settle(hwnd)
        known, _ = recognise(book, image)
        if known is None or known['name'] != 'galaxy-map':
            do_action(hwnd, {'click': [593, 469], 'wait': 1.5})   # RETURN в углу
            image, _ = settle(hwnd)
            known, _ = recognise(book, image)
        if known is None or known['name'] != 'galaxy-map':
            taken.append('    с раздела %s не вернулись на карту — обход прерван' % name)
            print(taken[-1], flush=True)
            break
    return taken


def name_scene(args):
    """
    Даёт снятой сцене имя, область приметы и действия — ту самую строку, по которой
    обход пойдёт дальше.

    Зачем отдельным режимом, а не правкой файла руками: примета — семьсот чисел, и
    пересчитывать её под новую область в уме нельзя. Здесь она пересчитывается по
    сохранённому снимку, а снимок заодно переименовывается, чтобы имя сцены и имя файла
    не разъезжались.
    """
    if not args.scene:
        raise SystemExit('какую сцену называем? --scene unknown-01')
    book = read_scenes()
    scene = next((one for one in book['scenes'] if one['name'] == args.scene), None)
    if scene is None:
        raise SystemExit('сцены %s в справочнике нет' % args.scene)

    old_path = os.path.join(SCENES_DIR, scene['name'] + '.png')
    if args.as_name:
        new_path = os.path.join(SCENES_DIR, args.as_name + '.png')
        if os.path.exists(old_path):
            os.replace(old_path, new_path)
        scene['name'] = args.as_name
        old_path = new_path
    if args.note:
        scene['note'] = args.note
    if args.region:
        scene['region'] = [int(part) for part in args.region.split(',')]
    if os.path.exists(old_path):
        scene['signature'] = signature(Image.open(old_path), scene.get('region'))

    actions = []
    for spot in args.click:
        parts = spot.split(',')
        action = {'click': [int(parts[0]), int(parts[1])], 'wait': args.wait}
        if len(parts) > 2:
            action['button'] = 'right'
        actions.append(action)
    for key in args.key:
        actions.append({'key': key, 'wait': args.wait})
    if actions:
        scene['actions'] = actions

    write_scenes(book)
    print('сцена %s: область %s, действий %d'
          % (scene['name'], scene.get('region', 'весь кадр'), len(scene.get('actions', []))))


def main():
    parser = argparse.ArgumentParser(description='Обход сцен оригинала MOO2')
    parser.add_argument('mode', choices=['watch', 'play', 'shot', 'stop', 'name', 'tour'])
    parser.add_argument('--steps', type=int, default=20, help='сколько сцен пройти')
    parser.add_argument('--name', default=None, help='имя снимка для режима shot')
    parser.add_argument('--no-launch', action='store_true',
                        help='не поднимать игру, работать только с уже открытой')
    parser.add_argument('--scene', default=None, help='какую сцену назвать (режим name)')
    parser.add_argument('--as', dest='as_name', default=None, help='как её назвать')
    parser.add_argument('--note', default=None, help='что на сцене видно, словами')
    parser.add_argument('--region', default=None,
                        help='область приметы в игровых координатах: x,y,ширина,высота')
    parser.add_argument('--click', action='append', default=[],
                        help='нажатие мышью: x,y[,право] — можно несколько раз подряд')
    parser.add_argument('--key', action='append', default=[],
                        help='нажатие клавиши: имя из таблицы KEYS')
    parser.add_argument('--wait', type=float, default=1.2,
                        help='сколько ждать после каждого действия, секунд')
    args = parser.parse_args()

    if args.mode == 'name':
        name_scene(args)
        return

    if args.mode == 'stop':
        hwnd, title = find_window()
        if not hwnd:
            print('игра не запущена')
            return
        win32gui.PostMessage(hwnd, win32con.WM_CLOSE, 0, 0)
        print('окно игры закрыто: %s' % title)
        return

    if args.no_launch:
        hwnd, title = find_window()
        if not hwnd:
            raise SystemExit('игра не запущена, а поднимать её запретили')
    else:
        hwnd, title = launch()
    print('окно игры: %s' % title)
    focus(hwnd)

    if args.mode == 'shot':
        image, _ = settle(hwnd)
        os.makedirs(SCENES_DIR, exist_ok=True)
        name = args.name or time.strftime('shot-%H%M%S')
        path = os.path.join(SCENES_DIR, name + '.png')
        image.save(path)
        print('снимок: %s (%dx%d)' % (path, image.width, image.height))
        return

    book = read_scenes()
    if args.mode == 'tour':
        log = tour(hwnd, book)
        write_report('tour', title, log, [])
        return
    log = []
    seen = walk(hwnd, book, args.steps, args.mode == 'play', log)
    write_report(args.mode, title, log, seen)


if __name__ == '__main__':
    main()
