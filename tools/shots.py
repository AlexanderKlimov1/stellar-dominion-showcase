# -*- coding: utf-8 -*-
"""
Снимки экранов игры для презентации: заводит партию, готовит состояние браузера и
снимает четыре сцены безголовым Chrome.

ЗАЧЕМ ОТДЕЛЬНЫМ СКРИПТОМ. Экраны игры живут в браузере, а нужны файлами — на страницу
презентации. Снимать их рукой значит каждый раз проходить вход, создание партии и
переходы; здесь это делается один раз и повторяемо.

КАК УСТРОЕНО. Состояние клиента лежит в `localStorage` (учётная запись, текущая партия,
открытый экран), а безголовый Chrome со снимком своего кода не выполняет. Поэтому Chrome
запускается ДВАЖДЫ с одной и той же папкой профиля: первый заход открывает страницу-затравку
`shot.html`, которая кладёт нужное в `localStorage`, второй — саму игру, где это уже лежит.

Запуск (при поднятом сервере на 8080 и клиенте на 5173):
    python tools/shots.py
"""

import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import shot_cdp

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUBLIC = os.path.join(ROOT, 'moo3-client', 'public')
OUT = os.path.join(ROOT, 'docs', 'shots')
PROFILE = os.path.join(ROOT, '.tmp-shot-profile')
CHROME = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
SERVER = 'http://localhost:8080'
CLIENT = 'http://localhost:5173'
SIZE = '1600,1000'


def call(path, body=None, token=None, method=None):
    req = urllib.request.Request(
        SERVER + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method or ('POST' if body is not None else 'GET'))
    req.add_header('Content-Type', 'application/json')
    req.add_header('Accept-Language', 'ru')
    if token:
        req.add_header('X-Account-Token', token)
    with urllib.request.urlopen(req) as answer:
        raw = answer.read().decode()
    return json.loads(raw) if raw else None


def admin():
    text = io.open(os.path.join(ROOT, 'admin.txt'), encoding='utf-8', errors='replace').read()
    login = re.search(r'(?im)^\s*(?:login|логин|user\w*)\s*[:=]\s*(\S+)', text).group(1)
    password = re.search(r'(?im)^\s*(?:password|пароль)\s*[:=]\s*(\S+)', text).group(1)
    return call('/api/auth/login', {'login': login, 'password': password})


#: Страница-затравка. Живёт здесь, а не в `moo3-client/public`, потому что оттуда она
#: попала бы в сборку клиента и уехала бы наружу вместе с игрой: пишется на время съёмки
#: и стирается после.
SEED_PAGE = r"""<!doctype html>
<meta charset="utf-8">
<title>Затравка для снимков</title>
<body style="background:#05070f;color:#9fb3c8;font:14px system-ui;padding:24px">
<p id="say">Кладу состояние в хранилище…</p>
<script>
(async () => {
  const what = new URLSearchParams(location.search).get('what') || 'clean';
  const say = document.getElementById('say');
  try {
    for (const key of ['moo3.account', 'moo3.session.active', 'moo3.session.view',
                       'moo3.session.menu']) {
      localStorage.removeItem(key);
    }
    localStorage.setItem('moo3.locale', 'ru');
    if (what !== 'clean') {
      const seed = await (await fetch('/shot-seed.json', { cache: 'no-store' })).json();
      localStorage.setItem('moo3.account', JSON.stringify(seed.account));
      if (what === 'milkyway') {
        localStorage.setItem('moo3.session.menu', 'milkyway');
      } else {
        localStorage.setItem('moo3.session.active', JSON.stringify(seed.active));
        const view = { ...seed.view, overlay: what === 'research' ? 'research' : 'none' };
        localStorage.setItem('moo3.session.view', JSON.stringify(view));
      }
    }
    say.textContent = 'готово: ' + what;
  } catch (error) {
    say.textContent = 'не вышло: ' + error;
  }
})();
</script>
</body>
"""


def seed(what, profile):
    """Кладёт нужное состояние в localStorage профиля: отдельным заходом браузера."""
    shot_cdp.shoot([(os.path.join(OUT, '.seed.png'), '%s/shot.html?what=%s' % (CLIENT, what))],
                   profile, settle=3.0)


def main():
    os.makedirs(OUT, exist_ok=True)
    if os.path.exists(PROFILE):
        shutil.rmtree(PROFILE, ignore_errors=True)

    io.open(os.path.join(PUBLIC, 'shot.html'), 'w', encoding='utf-8',
            newline='\n').write(SEED_PAGE)

    session = admin()
    print('вход выполнен, роль %s' % session['account']['role'])

    # Партия для снимков карты и науки: средняя галактика, четыре империи.
    game = call('/api/games', {'name': 'Показ', 'playerName': 'Земляне',
                               'galaxySize': 'MEDIUM', 'totalPlayers': 4,
                               'raceTraits': [], 'galacticEvents': True},
                token=session['token'])
    credentials = game['credentials']
    call('/api/games/%s/start' % game['game']['id'], {'accessToken': credentials['accessToken']},
         token=session['token'])
    print('партия заведена: %s' % game['game']['id'])

    stored = {
        'account': session,
        'active': {'gameId': game['game']['id'], 'gameName': game['game']['name'],
                   'credentials': credentials, 'savedAt': time.strftime('%Y-%m-%dT%H:%M:%S')},
        'view': {'gameId': game['game']['id'], 'selectedSystemId': None,
                 'openedSystemId': None, 'openedColonyId': None,
                 'overlay': 'none', 'showLabels': True},
    }
    io.open(os.path.join(PUBLIC, 'shot-seed.json'), 'w', encoding='utf-8').write(
        json.dumps(stored, ensure_ascii=False))

    scenes = (
        ('first-screen', 'clean'),       # первая сцена: вход в игру
        ('milky-way', 'milkyway'),       # схема Млечного Пути из меню
        ('galaxy-map', 'game'),          # карта галактики
        ('research', 'research'),        # выбор исследования
    )
    for name, what in scenes:
        # Затравка и снимок — РАЗНЫЕ заходы браузера: своего кода безголовый Chrome на
        # снимаемой странице не выполняет, а localStorage переживает запуск в профиле.
        seed(what, PROFILE)
        shot_cdp.shoot([(os.path.join(OUT, name + '.png'), CLIENT + '/')], PROFILE)
    trash = os.path.join(OUT, '.seed.png')
    if os.path.exists(trash):
        os.remove(trash)

    call('/api/games/%s?accessToken=%s' % (game['game']['id'], credentials['accessToken']),
         method='DELETE')
    for trash in ('shot-seed.json', 'shot.html'):
        where = os.path.join(PUBLIC, trash)
        if os.path.exists(where):
            os.remove(where)
    print('партия убрана, затравка удалена; снимки в %s' % OUT)


if __name__ == '__main__':
    main()
