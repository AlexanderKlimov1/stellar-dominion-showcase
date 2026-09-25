# -*- coding: utf-8 -*-
"""
Записыватель: смотрит, как в оригинал играет ЧЕЛОВЕК, и ведёт протокол его нажатий.

ЗАЧЕМ ЭТО ЕСТЬ. Инструмент игры (`moo2_play.py`) водит игру сам, и каждое незнакомое
управление приходится выводить по снимкам: где кнопка, что она делает, каким нажатием
отдаётся приказ. За один вечер так было пять неверных догадок подряд про отправку флота —
не та дорога, не те координаты, не та клавиша, не та кнопка, не та цель, — и каждая стоила
получасовой партии. Посмотреть, как это делает человек, дешевле любого разбора: он проходит
дорогу правильно с первого раза, а нам остаётся её записать.

ЧТО ЗАПИСЫВАЕТСЯ. На каждое нажатие мыши и клавиши: время, вид нажатия, точка в ИГРОВЫХ
координатах (640x480 — тех же, какими пишутся правила инструмента), имя сцены, если она
узнана, и снимок экрана ПЕРЕД нажатием. Снимок и есть главное: по нему видно, на что
человек нажал, а по следующему — что из этого вышло.

ЧЕГО ЗДЕСЬ НЕТ. Он НИЧЕГО НЕ НАЖИМАЕТ сам и мышь не трогает: только смотрит. Поэтому
играть можно как обычно, а записыватель работает рядом.

Запуск (из корня проекта, при запущенной игре):
    python tools/moo2_record.py [--minutes 20] [--shots 300]

Остановить можно и досрочно — закрыть окно игры или прервать запуск: протокол пишется по
строке на нажатие и не теряется.
"""

import argparse
import ctypes
import ctypes.wintypes
import io
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
# И stderr тоже: отказ «игра не запущена» уходит именно туда, а пульт на сервере читает
# этот вывод файлом и ждёт UTF-8. Без строки ниже причина отказа доезжала до него
# нечитаемыми байтами — то есть не доезжала вовсе, и на экране оставался голый код 1.
sys.stderr.reconfigure(encoding='utf-8', errors='replace')

import moo2_probe as probe

RECORD_DIR = os.path.join(probe.SCENES_DIR, 'record')
LOG_FILE = os.path.join(RECORD_DIR, 'record.txt')
DATA_FILE = os.path.join(RECORD_DIR, 'record.jsonl')

#: Состояние записи для пульта: сколько нажатий, сколько снимков, когда начали.
#: Пультом управляет наш сервер, а он на Java и в процесс записи заглянуть не может —
#: поэтому состояние кладётся файлом, а не держится в памяти.
STATE_FILE = os.path.join(RECORD_DIR, 'state.json')

#: Файл-просьба остановиться. Пульт создаёт его, запись видит и заканчивает сама, дописав
#: протокол. Убивать процесс тоже можно, но тогда последняя строка может не долететь до
#: диска, а протокол — это всё, ради чего запись и ведётся.

#: Кнопки и клавиши, за которыми следим. Мышь — обе кнопки; клавиши — те, какими в MOO II
#: и правда что-то делают: Esc, Enter, пробел и функциональные.
BUTTONS = ((0x01, 'левая кнопка'), (0x02, 'правая кнопка'))
KEYS = ((0x1B, 'Esc'), (0x0D, 'Enter'), (0x20, 'пробел'),
        (0x70, 'F1'), (0x71, 'F2'), (0x72, 'F3'), (0x73, 'F4'),
        (0x74, 'F5'), (0x75, 'F6'), (0x76, 'F7'), (0x77, 'F8'))

STOP_FILE = os.path.join(RECORD_DIR, 'stop')

#: Как часто опрашивать состояние кнопок. Двадцать миллисекунд — быстрее человеческого
#: нажатия и дешевле по ядрам, чем настоящий перехватчик ввода.
POLL = 0.02


def cursor():
    """Где сейчас указатель, в точках экрана."""
    point = ctypes.wintypes.POINT()
    ctypes.windll.user32.GetCursorPos(ctypes.byref(point))
    return point.x, point.y


def down(code):
    """Нажата ли кнопка или клавиша прямо сейчас."""
    return bool(ctypes.windll.user32.GetAsyncKeyState(code) & 0x8000)


def in_front(hwnd):
    """
    Игра ли сейчас на переднем плане.

    ЗАЧЕМ. Записыватель следит за мышью ВО ВСЁМ окружении, а не только в игре: он не может
    иначе — нажатия ловятся у системы, а не у окна. Поэтому в протокол попадало всё подряд:
    как человек переключался на браузер, как закрывал сообщение, как возвращался в игру.
    Причём первое нажатие по окну игры — это не ход, а именно возвращение фокуса, и по
    координатам оно неотличимо от настоящего.

    Отсекаем это просто: записываем только то, что сделано, когда игра И ТАК была впереди.
    Нажатие, которое её туда вернуло, пропадает — и правильно, игроком оно не было.
    """
    return ctypes.windll.user32.GetForegroundWindow() == hwnd


def write_state(started, count, shots, scene, alive=True):
    """Кладёт состояние записи файлом: пульт на сервере читает его отсюда."""
    try:
        tmp = STATE_FILE + '.tmp'
        io.open(tmp, 'w', encoding='utf-8').write(json.dumps(
            {'alive': alive, 'startedAt': started, 'seconds': round(time.time() - started, 1),
             'clicks': count, 'shots': shots, 'scene': scene,
             'log': LOG_FILE, 'data': DATA_FILE}, ensure_ascii=False))
        os.replace(tmp, STATE_FILE)
    except OSError:
        # Состояние — удобство пульта, а не условие записи: не записалось, и ладно.
        pass


def to_game(hwnd, spot):
    """
    Точка экрана — в игровые координаты 640x480.

    Правила инструмента писаны именно в них, поэтому записывать надо тоже в них: иначе
    протокол пришлось бы пересчитывать руками, а это лишний повод ошибиться.
    """
    left, top, width, height = probe.client_box(hwnd)
    if width <= 0 or height <= 0:
        return None
    return (int((spot[0] - left) * probe.GAME_WIDTH / width),
            int((spot[1] - top) * probe.GAME_HEIGHT / height))


#: Каждое сколькое нажатие снимать, когда сцена не сменилась и давно известна.
SAME_SCENE_EVERY = 10

#: С какого расхождения узнавание считать НЕуверенным и всё-таки снимать кадр.
#: Порог самого узнавания — `probe.SAME_SCENE` (8), и у верного попадания расхождение
#: обычно меньше единицы; всё, что подобралось к порогу, стоит сохранить глазами.
SURE_ENOUGH = 4.0


def worth_a_shot(scene, before, count, gap=None):
    """
    Стоит ли тратить снимок на это нажатие.

    ЗАЧЕМ ПРАВИЛО, А НЕ «снимать всё подряд». Первая настоящая запись (54 минуты, 1675
    нажатий) израсходовала все снимки к 400-му — то есть к семнадцатой минуте, — и дальше
    протокол шёл вслепую. А неузнанных сцен там было 154, и самые длинные куски пришлись
    как раз на конец, где картинок уже не осталось: разобрать их нечем.

    Снимок нужен там, где по протоколу НЕЛЬЗЯ понять, что было на экране: сцена не узнана
    или только что сменилась. Пятидесятое подряд нажатие на той же известной сцене не
    говорит ничего нового, и тратить на него картинку — значит отнять её у того кадра,
    ради которого запись и велась.
    """
    if scene is None or scene != before:
        return True
    # Узнано, но еле-еле. Так уже случилось: сцена «весть от посла», чья примета бралась с
    # тёмной полосы во всю высоту экрана, собрала 50 нажатий по 32 разным точкам — в окне,
    # где кнопка одна. Разобрать это было нечем: снимков к тому времени не осталось.
    if gap is not None and gap > SURE_ENOUGH:
        return True
    return count % SAME_SCENE_EVERY == 0


def keep_previous():
    """
    Отодвигает протокол прошлой записи, чтобы новая его не затёрла.

    ЗАЧЕМ. Имена протокола постоянны — на них смотрит пульт, — и открытие на запись
    стирает прошлый молча. А протокол и есть всё, ради чего запись велась: часа игры
    человека, которую потом разбирают по строчке. Терять его оттого, что кто-то нажал
    «старт» второй раз, нельзя. Отодвинутый зовётся временем своей последней строки:
    так видно, какая запись когда сделана.
    """
    when = None
    for path in (LOG_FILE, DATA_FILE):
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            continue
        stem, dot, tail = path.rpartition('.')
        when = time.strftime('%Y%m%d-%H%M', time.localtime(os.path.getmtime(path)))
        os.replace(path, '%s-%s%s%s' % (stem, when, dot, tail))
    if when is None:
        return
    # И СНИМКИ ТОЖЕ. Они зовутся по номеру нажатия (`shot-0007.png`), номера у новой записи
    # те же самые, и она затирает их один за другим — а протокол, отодвинутый строкой выше,
    # ссылается именно на них. Первый раз это заметили, когда новая запись уже успела съесть
    # пять кадров: отодвинутый протокол ссылался бы на чужие картинки, и это хуже пропажи —
    # ошибку видно не было бы вовсе.
    shots = sorted(f for f in os.listdir(RECORD_DIR) if f.startswith('shot-'))
    if not shots:
        return
    into = os.path.join(RECORD_DIR, when)
    os.makedirs(into, exist_ok=True)
    for name in shots:
        os.replace(os.path.join(RECORD_DIR, name), os.path.join(into, name))


def main():
    parser = argparse.ArgumentParser(description='Запись игры человека в оригинал MOO2')
    parser.add_argument('--minutes', type=float, default=30.0,
                        help='сколько минут писать')
    parser.add_argument('--shots', type=int, default=1200,
                        help='сколько снимков отложить, не больше: они тратятся на смену '
                             'сцены и на неузнанное, а не на каждое нажатие')
    args = parser.parse_args()

    hwnd, title = probe.find_window()
    if not hwnd:
        raise SystemExit('игра не запущена: подними её и начинай играть')
    os.makedirs(RECORD_DIR, exist_ok=True)
    book = probe.read_scenes()
    keep_previous()
    log = io.open(LOG_FILE, 'w', encoding='utf-8')
    data = io.open(DATA_FILE, 'w', encoding='utf-8')

    def say(line):
        print(line, flush=True)
        log.write(line + '\n')
        log.flush()

    say('ЗАПИСЬ ИГРЫ. Окно: %s' % title)
    say('Пишу %g минут, снимков не больше %d. Играйте как обычно — я только смотрю.'
        % (args.minutes, args.shots))

    started = time.time()
    until = started + args.minutes * 60
    was = {code: False for code, _ in BUTTONS + KEYS}
    shots = 0
    count = 0
    last_scene = None
    last_state = 0.0
    if os.path.exists(STOP_FILE):
        os.remove(STOP_FILE)
    write_state(started, 0, 0, None)

    while time.time() < until:
        # Окно закрыли — записывать больше нечего.
        if not probe.find_window()[0]:
            say('окно игры закрылось — запись окончена')
            break
        if os.path.exists(STOP_FILE):
            say('пульт попросил остановиться — запись окончена')
            break
        ahead = in_front(hwnd)
        for code, name in BUTTONS + KEYS:
            now = down(code)
            if now and not was[code]:
                if not ahead:
                    # Шум: нажатие сделано мимо игры (другое окно) или ею возвращён фокус.
                    was[code] = now
                    continue
                count += 1
                spot = cursor()
                where = to_game(hwnd, spot)
                # Снимок делается ДО того, как игра успеет отозваться: нам важно, на что
                # нажали, а не что получилось, — последнее видно на снимке следующего.
                scene, shot, gap = None, None, None
                try:
                    image = probe.grab(hwnd)
                    found, gap = probe.recognise(book, image)
                    scene = found['name'] if found else None
                    if shots < args.shots and worth_a_shot(scene, last_scene, count, gap):
                        shot = os.path.join(RECORD_DIR, 'shot-%04d.png' % count)
                        image.save(shot)
                        shots += 1
                except Exception as error:
                    scene = 'снимок не вышел: %s' % error
                last_scene = scene
                # Расхождение пишется РЯДОМ С ИМЕНЕМ сцены, а не прячется: имя без него
                # выглядит одинаково уверенно и у точного попадания, и у случайного
                # сходства — а разница между ними и есть вся цена протокола.
                say('%6.1fс  %-14s игровые %s  сцена: %s%s'
                    % (time.time() - started, name, where, scene or '—',
                       '' if gap is None else ' (расхождение %.1f)' % gap))
                data.write(json.dumps({'at': round(time.time() - started, 2),
                                       'what': name, 'spot': where,
                                       'scene': scene, 'gap': None if gap is None else round(gap, 2),
                                       'shot': shot},
                                      ensure_ascii=False) + '\n')
                data.flush()
            was[code] = now
        if time.time() - last_state >= 1.0:
            last_state = time.time()
            write_state(started, count, shots, last_scene)
        time.sleep(POLL)

    write_state(started, count, shots, last_scene, alive=False)
    if os.path.exists(STOP_FILE):
        os.remove(STOP_FILE)
    say('Записано нажатий: %d, снимков: %d' % (count, shots))
    say('Протокол: %s' % LOG_FILE)
    log.close()
    data.close()


if __name__ == '__main__':
    main()
