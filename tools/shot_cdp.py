# -*- coding: utf-8 -*-
"""
Снимок страницы через протокол отладки Chrome.

ЗАЧЕМ НЕ `--screenshot`. Ключ `--screenshot` снимает кадр, КОГДА СТРАНИЦА УСПОКОИТСЯ, а
наша игра не успокаивается никогда: Phaser рисует карту галактики каждый кадр, и открыт
поток событий партии. Chrome в таком режиме висит до упора (проверено: 180 секунд без
файла). Протокол отладки снимает кадр ПО КОМАНДЕ — ждать нечего.

Своего клиента вебсокета здесь нет по той же причине, что и всегда в этом проекте:
зависимости не заводим ради одного вызова. Нужны только текстовые кадры в одну сторону и
сборка ответа в другую — это полсотни строк.
"""

import base64
import json
import os
import socket
import struct
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'


class Talk:
    """Разговор с вкладкой Chrome по вебсокету: только то, что нужно для снимка."""

    def __init__(self, url):
        host, _, rest = url[len('ws://'):].partition('/')
        name, _, port = host.partition(':')
        self.sock = socket.create_connection((name, int(port)), timeout=60)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            'GET /%s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
            'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n'
            % (rest, host, key)).encode())
        self.rest = b''
        while b'\r\n\r\n' not in self.rest:
            self.rest += self.sock.recv(4096)
        self.rest = self.rest.split(b'\r\n\r\n', 1)[1]
        self.number = 0

    def _read(self, count):
        while len(self.rest) < count:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise IOError('вкладка закрылась')
            self.rest += chunk
        head, self.rest = self.rest[:count], self.rest[count:]
        return head

    def send(self, method, **params):
        self.number += 1
        body = json.dumps({'id': self.number, 'method': method, 'params': params}).encode()
        head = bytearray([0x81])
        size = len(body)
        if size < 126:
            head.append(0x80 | size)
        elif size < 65536:
            head.append(0x80 | 126)
            head += struct.pack('>H', size)
        else:
            head.append(0x80 | 127)
            head += struct.pack('>Q', size)
        mask = os.urandom(4)
        head += mask
        self.sock.sendall(bytes(head) + bytes(b ^ mask[i % 4] for i, b in enumerate(body)))
        return self.number

    def take(self):
        """Один пришедший кадр целиком (продолжения собираются)."""
        parts = b''
        while True:
            first, second = self._read(2)
            size = second & 0x7F
            if size == 126:
                size = struct.unpack('>H', self._read(2))[0]
            elif size == 127:
                size = struct.unpack('>Q', self._read(8))[0]
            parts += self._read(size)
            if first & 0x80:
                break
        return json.loads(parts.decode())

    def ask(self, method, **params):
        """Команда и ответ именно на неё: события протокола пропускаются."""
        number = self.send(method, **params)
        while True:
            said = self.take()
            if said.get('id') == number:
                return said.get('result', {})

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def shoot(pages, profile, size=(1600, 1000), settle=9.0, port=9333):
    """Снимает страницы по очереди одной вкладкой; `pages` — пары «имя файла, адрес»."""
    chrome = subprocess.Popen(
        [CHROME, '--headless=new', '--no-sandbox', '--enable-unsafe-swiftshader',
         '--hide-scrollbars', '--mute-audio', '--user-data-dir=' + profile,
         '--window-size=%d,%d' % size, '--remote-debugging-port=%d' % port,
         'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        where = None
        for _ in range(60):
            try:
                tabs = json.loads(urllib.request.urlopen(
                    'http://127.0.0.1:%d/json/list' % port, timeout=2).read().decode())
                pages_open = [t for t in tabs if t.get('type') == 'page']
                if pages_open:
                    where = pages_open[0]['webSocketDebuggerUrl']
                    break
            except Exception:
                time.sleep(0.5)
        if not where:
            raise SystemExit('Chrome не отозвался на протокол отладки')

        talk = Talk(where)
        talk.ask('Page.enable')
        for out, url in pages:
            talk.ask('Page.navigate', url=url)
            # Ждём ПО ЧАСАМ, а не «пока успокоится»: страница и не должна успокаиваться.
            time.sleep(settle)
            answer = talk.ask('Page.captureScreenshot', format='png', captureBeyondViewport=False)
            data = answer.get('data')
            if not data:
                print('   %s: кадр не пришёл' % os.path.basename(out))
                continue
            with open(out, 'wb') as file:
                file.write(base64.b64decode(data))
            print('   снят %s (%d КБ)' % (os.path.basename(out), os.path.getsize(out) // 1024))
        talk.close()
    finally:
        chrome.terminate()
        try:
            chrome.wait(timeout=15)
        except subprocess.TimeoutExpired:
            chrome.kill()
