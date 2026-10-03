# -*- coding: utf-8 -*-
r"""
Полосы поверхности колонии из снимков в общественном достоянии — п. 10.

Запуск:  python tools\surface_strips.py [куда]
По умолчанию кладёт в `sddnw-client/src/assets/surfaces`, откуда их берёт `planetVisuals`.

ЗАЧЕМ ОН НУЖЕН, если файлы уже лежат в проекте: правило «лицензия проверяется ДО
скачивания» невозможно соблюсти на глаз через год. Скрипт спрашивает Commons о лицензии,
и снимок не в общественном достоянии он не скачивает вовсе — при первом же прогоне так
отсеялись четыре кандидата с CC BY-SA и FAL. Он же хранит ОБРЕЗКУ каждого снимка: без неё
повторить полосу нельзя, а подобрать её заново — полчаса возни с линейкой.

Что он делает с каждым снимком, подробно сказано в `assets/surfaces/CREDITS.md`, а здесь —
в комментариях у самих чисел. Берётся не оригинал, а эскиз нужной ширины: у панорамы
Марса исходник в тринадцать тысяч точек.
"""
import hashlib
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

from PIL import Image, ImageEnhance

API = 'https://commons.wikimedia.org/w/api.php'
UA = 'stellar-dominion-asset-check/1.0 (+https://commons.wikimedia.org/)'
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'surface-cache')
DEFAULT_OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           'sddnw-client', 'src', 'assets', 'surfaces')


def call(params):
    params = dict(params, format='json')
    req = urllib.request.Request(API + '?' + urllib.parse.urlencode(params),
                                 headers={'User-Agent': UA})
    return json.load(urllib.request.urlopen(req, timeout=120))


def info_of(title, width):
    """Лицензия, автор и эскиз нужной ширины — ОДНИМ запросом, до всякого скачивания."""
    data = call({'action': 'query', 'prop': 'imageinfo', 'titles': title,
                 'iiprop': 'url|extmetadata|size', 'iiurlwidth': width})
    page = list(data['query']['pages'].values())[0]
    shot = (page.get('imageinfo') or [{}])[0]
    meta = shot.get('extmetadata', {})
    plain = lambda value: html.unescape(re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', value or ''))).strip()
    return {'title': page.get('title'), 'licence': meta.get('LicenseShortName', {}).get('value'),
            'author': plain(meta.get('Artist', {}).get('value')), 'thumb': shot.get('thumburl')}


def fetch(url, name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={'User-Agent': UA})
        with urllib.request.urlopen(req, timeout=300) as src:
            open(path, 'wb').write(src.read())
    return path


#: климат, файл, заголовок Commons, обрезка (x0, y0, x1, y1) в долях снимка, зеркалить ли.
#: Зеркало нужно там, где солнце на снимке справа: свет в сцене один, и у зданий он слева.
PLAN = [
    ('TOXIC', 'toxic-travertine-terraces',
     'File:Travertine terraces of Canary Spring (47953929088).jpg',
     (0.00, 0.35, 1.00, 0.72), False),
    ('RADIATED', 'radiated-lava-plain',
     'File:Craters of the Moon Monument and Preserve, Idaho (c62f799f-c398-4a38-ab79-4277e771b7ea).jpg',
     (0.065, 0.25, 0.94, 0.585), False),
    ('BARREN', 'barren-moon-hadley',
     'File:Apollo 15 - EVA 3 - AS15-82-11055 to 11057.jpg',
     (0.05, 0.50, 0.90, 0.88), True),
    ('DESERT', 'desert-mars-rocknest',
     'File:PIA16453-MarsCuriosityRover-RocknestPanorama-20121126.jpg',
     (0.00, 0.30, 0.62, 1.00), True),
    ('TUNDRA', 'tundra-soda-butte-valley',
     'File:Soda Butte Creek and Abiathar Mountain (49705457733).jpg',
     (0.00, 0.62, 0.88, 0.95), False),
    ('ARID', 'arid-great-sand-dunes',
     'File:Views at Great Sand Dunes National Monument and Preserve, Colorado (0225cfa4-0f71-444d-8d50-bb79fc4257cb).jpg',
     (0.00, 0.33, 1.00, 0.71), False),
    ('SWAMP', 'swamp-bald-cypress',
     'File:Bald Cypress swamp.jpg',
     (0.00, 0.68, 0.82, 0.99), False),
    ('OCEAN', 'ocean-cape-hatteras',
     'File:Beach and ocean view at Cape Hatteras National Seashore. (5cfede95-1dd8-b71c-0721-a5797fe4bc2e).jpg',
     (0.00, 0.33, 1.00, 0.78), False),
    ('TERRAN', 'terran-comanche-prairie',
     'File:A prairie horizon at Comanche National Grassland (a559f15eee7648ff813fa174c8e8c751).JPG',
     (0.00, 0.44, 1.00, 0.87), False),
    ('GAIA', 'gaia-alpine-meadow',
     'File:A meadow full of wildflowers, with snowy mountain ridges beyond. (f24ce01e-155d-4519-3e32-326e70f1c078).jpg',
     (0.00, 0.44, 1.00, 0.77), False),
]



def strip(title, box, mirror):
    """Полоса земли из снимка. Не общественное достояние — файл не скачивается вовсе."""
    info = info_of(title, 2600)
    if 'ublic domain' not in (info['licence'] or ''):
        raise SystemExit('НЕ общественное достояние (%s): %s' % (info['licence'], title))
    path = fetch(info['thumb'], hashlib.md5(title.encode('utf-8')).hexdigest()[:12] + '.jpg')
    img = Image.open(path).convert('RGB')
    x0, y0, x1, y1 = box
    crop = img.crop((int(x0 * img.width), int(y0 * img.height),
                     int(x1 * img.width), int(y1 * img.height)))
    return (crop.transpose(Image.FLIP_LEFT_RIGHT) if mirror else crop), info


OUT_W, OUT_H = 1100, 275

#: Цвет климата — из `planetVisuals.tsx`; второй правды о нём быть не должно, поэтому
#: значения переписаны оттуда слово в слово и сверяются глазами при правке.
CLIMATE_COLOURS = {
    'TOXIC': '#7fae4e', 'RADIATED': '#b6a02f', 'BARREN': '#8b8378', 'DESERT': '#d9a066',
    'TUNDRA': '#9fb8c8', 'ARID': '#c2854f', 'SWAMP': '#6f8f5a', 'OCEAN': '#3f7fbf',
    'TERRAN': '#46a05a', 'GAIA': '#5fd47e',
}

TINT = 0.30      #: доля цвета климата в цвете земли
DARKEN = 0.52    #: во сколько раз приглушается снимок: сцена идёт под звёздами
SATURATION = 0.8
HAZE = 0.32      #: сколько дымки у горизонта (верхний край полосы)
HAZE_BAND = 0.38 #: на какой доле высоты дымка сходит на нет
TARGET_LUMA = 63 #: к какой средней яркости приводится полоса
EXPOSURE_LIMITS = (0.3, 1.5)


def rgb(code):
    return tuple(int(code[i:i + 2], 16) for i in (1, 3, 5))


def grade(img, climate):
    tint = rgb(CLIMATE_COLOURS[climate])
    img = ImageEnhance.Color(img).enhance(SATURATION)
    # Приглушение считается по САМОМУ снимку, а не назначается числом: лунный реголит
    # снят ярче марсианского грунта вдвое, и общий множитель развёл бы полосы по яркости
    # сильнее, чем это оправдано климатом.
    grey = img.convert('L')
    mean = sum(value * count for value, count in enumerate(grey.histogram())) / (img.width * img.height)
    darken = min(EXPOSURE_LIMITS[1], max(EXPOSURE_LIMITS[0], TARGET_LUMA / max(mean, 1)))
    pixels = img.load()
    width, height = img.size
    # Дымка у горизонта: цвет неба над горами — климат, уведённый в тёмно-синее.
    haze = tuple(int(part * 0.45 + blue * 0.55) for part, blue in zip(tint, (26, 34, 56)))
    for y in range(height):
        veil = HAZE * max(0.0, 1.0 - y / (height * HAZE_BAND))
        for x in range(width):
            r, g, b = pixels[x, y]
            luma = 0.299 * r + 0.587 * g + 0.114 * b
            # цвет климата подмешивается по яркости точки: тёмные места не выцветают
            r = r * (1 - TINT) + tint[0] * luma / 255 * TINT
            g = g * (1 - TINT) + tint[1] * luma / 255 * TINT
            b = b * (1 - TINT) + tint[2] * luma / 255 * TINT
            r = r * darken
            g = g * darken
            b = b * darken
            r = r * (1 - veil) + haze[0] * veil
            g = g * (1 - veil) + haze[1] * veil
            b = b * (1 - veil) + haze[2] * veil
            pixels[x, y] = (int(max(0, min(255, r))), int(max(0, min(255, g))), int(max(0, min(255, b))))
    return ImageEnhance.Contrast(img).enhance(1.2)


def build(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for climate, name, title, box, mirror in PLAN:
        crop, info = strip(title, box, mirror)
        polosa = grade(crop.resize((OUT_W, OUT_H), Image.LANCZOS), climate)
        path = os.path.join(out_dir, name + '.webp')
        polosa.save(path, 'WEBP', quality=74, method=6)
        print('%-9s %-26s %6d байт  %s  %s'
              % (climate, name, os.path.getsize(path), info['licence'], info['author'][:40]))


if __name__ == '__main__':
    build(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT)
