"""
Записывает демонстрационный бой в GIF — п. 8.

Запуск при поднятом сервере:  python tools\\demo_battle_gif.py [файл.gif]
По умолчанию кладёт `demo-battle.gif` в корень проекта — и копию в
`sddnw-client/public/`, откуда её берёт обложка экрана входа. В git не идёт ни одна
(`.gitignore`): анимации игре нужны, но двоичным файлам в истории пока не место.
Копия нужна потому, что сборка клиента видит только свой каталог: запись, оставшаяся
в корне, до игры не доехала бы.

**Кадры снимаются с НАСТОЯЩЕЙ сцены боя, а не рисуются заново** (трек техдолга, пункт 19).
Прежде инструмент рисовал поле сам, средствами Pillow, и рисовал корабли прямоугольниками;
когда у кораблей появились рисунки (`ui/ship/shipArt.tsx`), обложка стала показывать игру
такой, какой она уже не выглядит. Второй способ рисовать корабль расходится с первым при
первой же правке, поэтому способ теперь один: инструмент поднимает Chrome без окна
(свой временный профиль, ничего не скачивается), входит в игру администратором, открывает
демонстрационный бой из главного меню и снимает поле кадр за кадром, пока бой не кончится.
В записи ровно то, что увидит игрок: рисунки, выстрелы, вспышки, взрывы.

Управляется Chrome по протоколу DevTools через WebSocket; клиент WebSocket здесь свой, на
стандартной библиотеке: ставить `playwright` или `selenium` ради одного инструмента незачем
(зависимости этого проекта так не ставят).

Настройки:
* `SDDNW_BASE` — сервер (API и собранный клиент; по умолчанию 8080). Клиент он раздаёт из
  `sddnw-client/dist`, поэтому запись показывает ПОСЛЕДНЮЮ СБОРКУ — перед записью
  соберите клиент (`npm run build`);
* `SDDNW_CLIENT` — откуда брать клиент, если не с сервера (например, dev-сервер Vite);
* `SDDNW_ADMIN_FILE` — файл с паролем администратора (по умолчанию `admin.txt` в корне);
* `SDDNW_CHROME` — путь к Chrome или Edge, если они стоят не на обычном месте.

Осторожно: сервер держит одну демонстрацию за раз, и новая удаляет прошлую. Если в это
время кто-то смотрит демонстрацию в браузере, его бой закончится.
"""
import base64
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

from PIL import Image

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# Адрес сервера переопределяется переменной окружения, как в regress.py и
# balance_run.py: рядом с прогонным сервером на 8080 часто поднят второй, и
# инструмент, не умеющий смотреть на него, молча записывал бы ЧУЖОЙ сервер.
BASE = os.environ.get('SDDNW_BASE', 'http://localhost:8080').rstrip('/')
CLIENT = os.environ.get('SDDNW_CLIENT', BASE).rstrip('/')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# --- кадр ---------------------------------------------------------------------------

# Окно браузера: поле боя займёт его над нижней полосой. Пропорция 16:10 — как у поля.
WINDOW = (1280, 800)
# Ширина кадра в записи: на обложке запись ужимают по столбцу (около 450 точек), и
# крупнее незачем — вес GIF растёт с площадью.
FRAME_WIDTH = 640
# Снимок не чаще, чем раз в столько миллисекунд живого времени.
CAPTURE_MS = 100
# Кадр GIF не короче этого: браузеры кадры короче 20 мс растягивают до 100, а глазу
# пятнадцати кадров в секунду хватает.
MIN_FRAME_MS = 66
# Сколько длится запись при показе. В браузере бой идёт живым темпом, минуты полторы-две, —
# столько обложка не держит внимания, и время при сборке сжимается до этого срока.
PLAYBACK_SECONDS = 30
# Потолок записи живого времени: бой без конца — это поломка, а не длинный бой.
MAX_SECONDS = 240
# Сколько снимать после конца боя: последний взрыв должен догореть в кадре.
TAIL_SECONDS = 1.5

CHROMES = (
    os.environ.get('SDDNW_CHROME', ''),
    os.path.join(os.environ.get('PROGRAMFILES', r'C:\Program Files'), 'Google', 'Chrome', 'Application', 'chrome.exe'),
    os.path.join(os.environ.get('PROGRAMFILES(X86)', r'C:\Program Files (x86)'), 'Google', 'Chrome', 'Application', 'chrome.exe'),
    os.path.join(os.environ.get('PROGRAMFILES(X86)', r'C:\Program Files (x86)'), 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
)


# --- вход ---------------------------------------------------------------------------

def login():
    """Пропуск администратора: без входа главное меню не откроется, а демонстрация — в нём."""
    admin_file = os.environ.get('SDDNW_ADMIN_FILE', os.path.join(ROOT, 'admin.txt'))
    password = None
    with io.open(admin_file, encoding='utf-8') as source:
        for line in source:
            if line.startswith('пароль:'):
                password = line.split(':', 1)[1].strip()
    if password is None:
        raise SystemExit(f'В {admin_file} нет строки «пароль:» — войти нечем.')
    request = urllib.request.Request(
        BASE + '/api/auth/login',
        data=json.dumps({'login': 'admin', 'password': password}).encode('utf-8'),
        headers={'Content-Type': 'application/json'}, method='POST')
    answer = json.loads(urllib.request.urlopen(request, timeout=30).read().decode('utf-8'))
    return {'token': answer['token'], 'account': answer['account']}


# --- WebSocket (RFC 6455) на стандартной библиотеке ---------------------------------

class Socket:
    """Ровно столько WebSocket, сколько нужно протоколу DevTools: текстовые кадры туда и обратно."""

    def __init__(self, url):
        parts = urllib.parse.urlparse(url)
        self.sock = socket.create_connection((parts.hostname, parts.port), timeout=60)
        key = base64.b64encode(os.urandom(16)).decode('ascii')
        self.sock.sendall((
            f'GET {parts.path} HTTP/1.1\r\nHost: {parts.hostname}:{parts.port}\r\n'
            f'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n'
            'Sec-WebSocket-Version: 13\r\n\r\n').encode('ascii'))
        head = b''
        while b'\r\n\r\n' not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise SystemExit('Chrome закрыл соединение DevTools, не ответив.')
            head += chunk
        if b' 101 ' not in head.split(b'\r\n', 1)[0]:
            raise SystemExit('Chrome не принял соединение DevTools: ' + head.split(b'\r\n', 1)[0].decode())
        self.buffer = head.split(b'\r\n\r\n', 1)[1]

    def send(self, text):
        payload = text.encode('utf-8')
        mask = os.urandom(4)
        head = bytes([0x81])
        if len(payload) < 126:
            head += bytes([0x80 | len(payload)])
        elif len(payload) < 65536:
            head += bytes([0x80 | 126]) + len(payload).to_bytes(2, 'big')
        else:
            head += bytes([0x80 | 127]) + len(payload).to_bytes(8, 'big')
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(head + mask + masked)

    def _read(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(1 << 20)
            if not chunk:
                raise SystemExit('Chrome оборвал соединение DevTools.')
            self.buffer += chunk
        data, self.buffer = self.buffer[:n], self.buffer[n:]
        return data

    def receive(self):
        message = b''
        while True:
            b0, b1 = self._read(2)
            length = b1 & 0x7F
            if length == 126:
                length = int.from_bytes(self._read(2), 'big')
            elif length == 127:
                length = int.from_bytes(self._read(8), 'big')
            payload = self._read(length)
            opcode = b0 & 0x0F
            if opcode == 0x8:
                raise SystemExit('Chrome закрыл соединение DevTools.')
            if opcode in (0x1, 0x0):
                message += payload
                if b0 & 0x80:
                    return message.decode('utf-8')


class DevTools:
    """Команды DevTools по одной: ответ узнаётся по номеру, события по пути пропускаются."""

    def __init__(self, url):
        self.socket = Socket(url)
        self.next_id = 0

    def call(self, method, **params):
        self.next_id += 1
        self.socket.send(json.dumps({'id': self.next_id, 'method': method, 'params': params}))
        while True:
            answer = json.loads(self.socket.receive())
            if answer.get('id') == self.next_id:
                if 'error' in answer:
                    raise SystemExit(f'{method}: {answer["error"]}')
                return answer.get('result', {})

    def js(self, expression):
        result = self.call('Runtime.evaluate', expression=expression, returnByValue=True, awaitPromise=True)
        if 'exceptionDetails' in result:
            raise SystemExit('Ошибка в странице: ' + json.dumps(result['exceptionDetails'], ensure_ascii=False)[:400])
        return result.get('result', {}).get('value')

    def wait(self, expression, what, seconds=30):
        deadline = time.time() + seconds
        while time.time() < deadline:
            if self.js(expression):
                return
            time.sleep(0.2)
        raise SystemExit(f'Не дождался: {what}.')


def chrome_path():
    for path in CHROMES:
        if path and os.path.exists(path):
            return path
    raise SystemExit('Не нашёл ни Chrome, ни Edge: задайте путь переменной SDDNW_CHROME.')


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def page_socket(port):
    """Адрес DevTools открытой вкладки: Chrome поднимается не мгновенно, поэтому ждём."""
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            targets = json.loads(urllib.request.urlopen(f'http://127.0.0.1:{port}/json/list', timeout=2).read())
            pages = [t for t in targets if t.get('type') == 'page' and t.get('webSocketDebuggerUrl')]
            if pages:
                return pages[0]['webSocketDebuggerUrl']
        except OSError:
            pass
        time.sleep(0.3)
    raise SystemExit('Chrome не открыл вкладку для DevTools.')


# --- запись ---------------------------------------------------------------------------

def record(tools, account):
    """Открывает демонстрацию и снимает поле, пока бой не кончится; кадры и их длительности."""
    tools.call('Page.enable')
    tools.call('Emulation.setDeviceMetricsOverride', width=WINDOW[0], height=WINDOW[1],
               deviceScaleFactor=1, mobile=False)
    tools.call('Page.navigate', url=CLIENT + '/')
    tools.wait("document.readyState === 'complete'", 'загрузки страницы')
    # Язык — английский, как у обложки по умолчанию; журнал и подсветка ходов выключены:
    # в записи должно быть поле, а не служебные надписи поверх него. Залпы быстрые — бой
    # доигрывается вдвое скорее, и записывать его меньше.
    tools.js(
        f"localStorage.setItem('sddnw.account', {json.dumps(json.dumps(account))});"
        "localStorage.setItem('sddnw.locale', 'en');"
        "localStorage.setItem('sddnw.battle.options',"
        " JSON.stringify({grid: false, moves: false, fast: true, log: false})); true")
    tools.call('Page.reload')
    demo_button = "[...document.querySelectorAll('button')].find(b => /Demo battle/.test(b.textContent))"
    tools.wait(f'!!({demo_button})', 'главного меню с кнопкой демонстрации')
    tools.js(f'{demo_button}.click(); true')
    tools.wait("!!document.querySelector('[data-battle-field] svg')", 'сцены боя')
    time.sleep(0.8)
    rect = tools.js(
        "(() => { const r = document.querySelector('[data-battle-field] svg').getBoundingClientRect();"
        " return {x: r.x, y: r.y, width: r.width, height: r.height}; })()")
    clip = {'x': rect['x'], 'y': rect['y'], 'width': rect['width'], 'height': rect['height'], 'scale': 1}

    frames, durations = [], []
    started = time.time()
    last = None
    finished_at = None
    while time.time() - started < MAX_SECONDS:
        now = time.time()
        if last is not None and (now - last) * 1000 < CAPTURE_MS:
            time.sleep(CAPTURE_MS / 1000 - (now - last))
            now = time.time()
        # PNG, а не JPEG: шум сжатия GIF потом не пережимает, и файл выходил бы вдвое тяжелее.
        shot = tools.call('Page.captureScreenshot', format='png', clip=clip)
        if last is not None:
            durations.append(int((now - last) * 1000))
        # Кадр хранится сжатым, как пришёл: разжатые снимки боя в две-три минуты — это
        # гигабайты, а разжимаются они при сборке по одному.
        frames.append(base64.b64decode(shot['data']))
        last = now
        state = tools.js("document.querySelector('[data-battle-state]')?.dataset.battleState ?? ''")
        if state and state != 'IN_PROGRESS' and finished_at is None:
            finished_at = now
        if finished_at is not None and now - finished_at > TAIL_SECONDS:
            break
    durations.append(durations[-1] if durations else CAPTURE_MS)
    return frames, durations, finished_at is not None


def decode(frame):
    return Image.open(io.BytesIO(frame)).convert('RGB')


# Какую долю кадров с самыми далёкими краями рамка не учитывает — см. action_box.
CROP_SHARE = 0.15


def action_box(frames):
    """
    Прямоугольник, где за весь бой что-то светилось: корабли, выстрелы, взрывы.

    Поле 32×20 клеток почти пустое — эскадры стоят у двух краёв, — и снятое целиком оно
    ужималось на обложке так, что корабли становились точками. Поэтому кадр режется по
    действию, с запасом по краям, а пропорция держится не шире 2,4 : 1 — иначе полоса
    выйдет лентой.
    """
    width, height = decode(frames[0]).size
    found = [box for box in (decode(frame).convert('L').point(lambda v: 255 if v > 48 else 0).getbbox()
                             for frame in frames[::4]) if box]
    if not found:
        return 0, 0, width, height
    # Края берутся не по самому широкому кадру, а по большинству (backlog-promo, пункт 30):
    # бой теперь сходится к середине, и только первые кадры, пока эскадры ещё у краёв поля,
    # растягивали рамку на всю ширину — корабли снова выходили точками. Отрезается
    # CROP_SHARE кадров с самыми далёкими краями с каждой стороны.
    def edge(values, low):
        ordered = sorted(values)
        cut = int(len(ordered) * CROP_SHARE)
        return ordered[cut] if low else ordered[len(ordered) - 1 - cut]
    box = (edge([b[0] for b in found], True), edge([b[1] for b in found], True),
           edge([b[2] for b in found], False), edge([b[3] for b in found], False))
    margin = 28
    left, top = max(0, box[0] - margin), max(0, box[1] - margin)
    right, bottom = min(width, box[2] + margin), min(height, box[3] + margin)
    need = (right - left) / 2.4 - (bottom - top)
    if need > 0:
        top, bottom = max(0, top - need / 2), min(height, bottom + need / 2)
    return int(left), int(top), int(right), int(bottom)


def pace(frames, durations):
    """
    Сжатие времени до PLAYBACK_SECONDS: кадры прореживаются так, чтобы каждый длился не меньше
    MIN_FRAME_MS, а длительности пересчитываются — движение остаётся ровным, только быстрее.
    """
    speed = max(1.0, sum(durations) / 1000 / PLAYBACK_SECONDS)
    kept, kept_ms, carry = [], [], 0.0
    for frame, ms in zip(frames, durations):
        carry += ms / speed
        if not kept or carry >= MIN_FRAME_MS:
            kept.append(frame)
            kept_ms.append(max(MIN_FRAME_MS, int(carry)))
            carry = 0.0
    return kept, kept_ms, speed


def save(frames, durations, target):
    box = action_box(frames)
    frames, durations, speed = pace(frames, durations)
    width, height = box[2] - box[0], box[3] - box[1]
    size = (FRAME_WIDTH, round(height * FRAME_WIDTH / width))
    scaled = [decode(frame).crop(box).resize(size, Image.LANCZOS)
              .convert('P', palette=Image.ADAPTIVE, colors=96) for frame in frames]
    scaled[0].save(target, save_all=True, append_images=scaled[1:], duration=durations,
                   loop=0, disposal=1, optimize=False)
    return len(scaled), speed


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'demo-battle.gif')
    account = login()
    port = free_port()
    profile = tempfile.mkdtemp(prefix='sddnw-gif-')
    chrome = subprocess.Popen([
        chrome_path(), '--headless=new', f'--remote-debugging-port={port}',
        f'--user-data-dir={profile}', f'--window-size={WINDOW[0]},{WINDOW[1]}',
        '--hide-scrollbars', '--mute-audio', '--no-first-run', '--no-default-browser-check',
        'about:blank'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        tools = DevTools(page_socket(port))
        frames, durations, finished = record(tools, account)
    finally:
        chrome.terminate()
        try:
            chrome.wait(timeout=10)
        except subprocess.TimeoutExpired:
            chrome.kill()
        shutil.rmtree(profile, ignore_errors=True)

    live = sum(durations) / 1000
    count, speed = save(frames, durations, target)
    print(f'Снято {len(frames)} кадров за {live:.1f} с боя; в записи {count} кадров, '
          f'ускорение {speed:.1f}: {target}')
    if not finished:
        print(f'Бой не кончился за {MAX_SECONDS} с — запись обрезана по потолку.')

    cover = os.path.join(ROOT, 'sddnw-client', 'public', 'demo-battle.gif')
    if os.path.abspath(target) != os.path.abspath(cover):
        os.makedirs(os.path.dirname(cover), exist_ok=True)
        shutil.copyfile(target, cover)
        print(f'Копия для обложки: {cover} — её берёт экран входа')
    return 0


if __name__ == '__main__':
    sys.exit(main())
