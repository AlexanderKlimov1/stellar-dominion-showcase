# -*- coding: utf-8 -*-
"""
Собирает витрину проекта для публичного репозитория.

ЗАЧЕМ. Исходники показывают работодателю, а не раздают в сборку. Поэтому витрина —
это полная копия кода МИНУС несколько файлов, без которых ни сервер, ни клиент не
собираются: они кладутся рядом зашифрованными, а ключ остаётся у хозяина.

ЧЕСТНАЯ ОГОВОРКА, она же написана и в README витрины: это ЗАМОК, А НЕ СЕЙФ. Описание
сборки восстанавливается по самим исходникам — зависимости видны в импортах. Шифрование
поднимает цену запуска с «скачал и собрал» до «разобрался и написал заново», и на этом
его сила кончается.

ЧЕГО В ВИТРИНЕ НЕТ НАМЕРЕННО:
  * снимков настоящей MOO II (`tools/moo2-scenes`, `docs/moo2`) — это чужая игра, и её
    кадрам не место в публичном репозитории под своим именем;
  * телеметрии прогонов (`tools/balance`) — четверть гигабайта замеров, которые никому,
    кроме прибора, не нужны;
  * учётной записи администратора, писем, журналов, сборок и временных файлов.

Запуск:
    python tools/make_showcase.py
"""

import io
import os
import secrets
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOWCASE = os.path.join(ROOT, 'showcase')
KEY_FILE = os.path.join(ROOT, 'showcase-key.txt')

#: Что копируется целиком (каталоги) и по одному (файлы).
TREES = (
    'moo3-server/src',
    'moo3-client/src',
    'moo3-client/public',
    'resources',
    'docs/architecture',
    'docs/shots',
)
FILES = (
    'moo3-server/pom.xml',
    'moo3-server/build.cmd',
    'moo3-server/run.cmd',
    'moo3-server/run-dev.cmd',
    'moo3-server/run-balance.cmd',
    'moo3-client/index.html',
    'moo3-client/package.json',
    'moo3-client/postcss.config.js',
    'moo3-client/tailwind.config.js',
    'moo3-client/tsconfig.json',
    'moo3-client/vite.config.ts',
    'moo3-client/setup.cmd',
    'README.md',
    'CLAUDE.md',
    'balance-metrics-works.txt',
    'demo.html',
    'docs/renaming.md',
    'run-all.bat',
)

#: Инструменты: только код, без замеров и снимков.
#:
#: `download_moo2.ps1` не идёт в витрину намеренно: это черновик, тянущий кадры ЧУЖОЙ игры
#: по ссылкам-заглушкам, и в публичном репозитории при резюме ему делать нечего.
#: `_ui_session.json` — тем более: в нём лежит пропуск игрока от живой партии, а пропускам
#: не место в публичном репозитории ни при каких обстоятельствах.
TOOLS_SKIP = ('balance', 'moo2-scenes', '__pycache__', 'learn',
              'download_moo2.ps1', '_ui_session.json')

#: Что запирается. Выбор не случаен: это ОПИСАНИЯ СБОРКИ И ЗАПУСКА, а не игровая логика.
#: Витрина должна показывать ремесло целиком — поэтому заперт скелет, а не мясо.
LOCKED = (
    ('moo3-server/pom.xml', 'без него не собрать сервер: Maven не знает ни зависимостей, ни сборки jar'),
    ('moo3-server/src/main/resources/application.yml', 'без него сервер не поднимется: нет ни базы, ни путей к справочникам'),
    ('moo3-server/src/main/resources/application-balance.yml', 'то же для режима балансовых прогонов'),
    ('moo3-server/src/main/resources/db/changelog/db.changelog-master.xml', 'без него Liquibase не построит схему базы'),
    ('moo3-client/package.json', 'без него клиент не собрать: нет ни зависимостей, ни команд'),
    ('moo3-client/vite.config.ts', 'без него нет сборки и dev-сервера'),
    ('moo3-client/tsconfig.json', 'без него не проходит проверка типов'),
    ('moo3-client/tailwind.config.js', 'без него нет ролей цвета и шкалы размеров'),
    ('moo3-client/postcss.config.js', 'без него не собирается стиль'),
)

IGNORE = shutil.ignore_patterns(
    'node_modules', 'target', '__pycache__', '*.pyc', '*.log', '.tmp*', 'dist')


def copy_tree(rel):
    src = os.path.join(ROOT, rel.replace('/', os.sep))
    dst = os.path.join(SHOWCASE, rel.replace('/', os.sep))
    if not os.path.exists(src):
        print('   пропущено (нет): %s' % rel)
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copytree(src, dst, ignore=IGNORE, dirs_exist_ok=True)


def copy_file(rel):
    src = os.path.join(ROOT, rel.replace('/', os.sep))
    dst = os.path.join(SHOWCASE, rel.replace('/', os.sep))
    if not os.path.exists(src):
        print('   пропущено (нет): %s' % rel)
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)


def copy_tools():
    src = os.path.join(ROOT, 'tools')
    dst = os.path.join(SHOWCASE, 'tools')
    os.makedirs(dst, exist_ok=True)
    for name in sorted(os.listdir(src)):
        if name in TOOLS_SKIP or name.startswith('.'):
            continue
        where = os.path.join(src, name)
        if os.path.isdir(where):
            shutil.copytree(where, os.path.join(dst, name), ignore=IGNORE, dirs_exist_ok=True)
        elif name.endswith(('.py', '.ps1', '.md', '.json')) and not name.endswith('-report.txt'):
            shutil.copyfile(where, os.path.join(dst, name))


def make_key():
    """
    Ключ рождается ЗДЕСЬ и никуда не показывается: ни в вывод, ни в репозиторий.

    Своё случайное значение надёжнее придуманного пароля и не заставляет хозяина
    диктовать секрет вслух.
    """
    if os.path.exists(KEY_FILE):
        print('ключ уже есть: %s (беру его)' % KEY_FILE)
        return
    io.open(KEY_FILE, 'w', encoding='utf-8', newline='\n').write(secrets.token_hex(48) + '\n')
    print('ключ создан: %s — СОХРАНИТЕ ЕГО, второго такого нет' % KEY_FILE)


def lock():
    """Запирает описания сборки: рядом ложится .enc, а открытый файл из витрины уходит."""
    locked = []
    for rel, why in LOCKED:
        plain = os.path.join(SHOWCASE, rel.replace('/', os.sep))
        if not os.path.exists(plain):
            print('   нечего запирать: %s' % rel)
            continue
        sealed = plain + '.enc'
        done = subprocess.run(
            ['openssl', 'enc', '-aes-256-cbc', '-pbkdf2', '-iter', '300000', '-salt',
             '-pass', 'file:' + KEY_FILE, '-in', plain, '-out', sealed],
            capture_output=True)
        if done.returncode != 0:
            raise SystemExit('openssl не справился с %s: %s' % (rel, done.stderr.decode('utf-8', 'replace')))
        os.remove(plain)
        locked.append((rel, why))
        print('   заперт %s' % rel)
    return locked


README = '''# Stellar Dominion

Пошаговая стратегия в духе Master of Orion II: сервер на Spring Boot, клиент на
React + Phaser, а рядом — мастерская приборов, которыми игра меряется и настраивается.

Наглядный разбор проекта: откройте **`demo.html`** (файл самодостаточный, сервер не нужен).

## Что здесь

| Что | Где |
|---|---|
| Сервер: 369 классов, 15 фаз конца хода | `moo3-server/src` |
| Клиент: 116 модулей, экраны и карта галактики | `moo3-client/src` |
| Правила игры данными, а не кодом | `resources/` |
| Инструменты: балансировка, игра в оригинал, обучение сети | `tools/` |
| Как всё устроено и почему именно так | `README.md`, `CLAUDE.md` |
| Журнал балансировки: каждая правка цены и на чём она измерена | `balance-metrics-works.txt` |

## Собрать нельзя — и это нарочно

Витрина показывает код, но не раздаёт готовую сборку. Несколько файлов — описания
сборки и запуска — лежат здесь **зашифрованными** (`*.enc`), и ключ есть только у автора.
Их список и назначение: `LOCKED.md`.

Это **замок, а не сейф**: описание сборки восстанавливается по самим исходникам —
зависимости видны в импортах. Шифрование поднимает цену запуска с «скачал и собрал» до
«разобрался и написал заново», и на этом его сила кончается. Игровая логика при этом
открыта целиком: витрина должна показывать ремесло, а не прятать его.

## Чего здесь нет

* снимков настоящей MOO II — это чужая игра, и её кадрам не место в публичном
  репозитории;
* телеметрии прогонов балансировки — четверть гигабайта замеров;
* учётных записей, писем, журналов и сборок.

## Права

Код открыт для чтения, но не для использования: смотреть можно всем, брать нельзя
никому — см. `LICENSE`. Игра — самостоятельная реализация, вдохновлённая Master of
Orion II; материалов оригинала здесь нет, имена рас и технологий свои
(`docs/renaming.md`).
'''

#: Лицензия выбрана под замысел витрины, а не «как принято».
#:
#: MIT и Apache разрешают брать код и делать с ним что угодно — это прямо противоречит
#: тому, ради чего описания сборки заперты. Поэтому здесь source-available: смотреть можно
#: всем, брать нельзя никому. Русский текст первый — он и есть исходный.
LICENSE = '''Stellar Dominion
Copyright (c) 2026 Александр Климов. Все права защищены.

ИСХОДНЫЙ КОД ОТКРЫТ ДЛЯ ЧТЕНИЯ, НО НЕ ДЛЯ ИСПОЛЬЗОВАНИЯ

Настоящим разрешается: просматривать, читать и изучать этот исходный код —
в том числе для оценки квалификации автора, — а также цитировать из него
фрагменты с указанием авторства.

Настоящим НЕ разрешается без письменного согласия автора: использовать этот
код или любую его часть в своих работах, копировать, изменять, объединять,
публиковать, распространять, передавать по сублицензии и продавать, а также
создавать производные произведения.

Код предоставляется «как есть», без каких-либо гарантий, явных или
подразумеваемых. Автор не несёт ответственности за любые последствия его
использования.

О ЧУЖИХ ПРАВАХ

Это самостоятельная реализация пошаговой стратегии, вдохновлённая игрой
Master of Orion II: Battle at Antares. Названия Master of Orion и Orion, а
также иные упомянутые товарные знаки принадлежат их правообладателям; автор
с ними не связан и ими не поддерживается. Кода, изображений, звуков и иных
материалов оригинальной игры этот репозиторий не содержит: имена рас,
технологий и компонентов заменены своими (см. docs/renaming.md).


--- English translation; the Russian text above is the original ---

Stellar Dominion
Copyright (c) 2026 Alexander Klimov. All rights reserved.

SOURCE AVAILABLE FOR READING, NOT FOR USE

Permission is hereby granted to view, read and study this source code —
including for the purpose of evaluating the author's skills — and to quote
excerpts from it with attribution.

Permission is NOT granted, without the author's written consent, to use this
code or any part of it in your own work, nor to copy, modify, merge, publish,
distribute, sublicense or sell it, nor to create derivative works.

The code is provided "as is", without warranty of any kind, express or
implied. The author is not liable for any consequences of its use.

THIRD-PARTY RIGHTS

This is an independent implementation of a turn-based strategy game inspired
by Master of Orion II: Battle at Antares. Master of Orion, Orion and other
trademarks mentioned belong to their respective owners; the author is not
affiliated with or endorsed by them. This repository contains no code, images,
sounds or other assets from the original game: names of races, technologies
and components have been replaced with original ones (see docs/renaming.md).
'''

GITIGNORE = '''# сборки
target/
dist/
node_modules/
*.class
__pycache__/
*.pyc

# то, что не публикуется никогда
showcase-key.txt
admin.txt
admin-dev.txt
mail-outbox/
mail-outbox-dev/
*.log
.tmp*
.env
'''

UNLOCK_SH = '''#!/bin/sh
# Открывает запертые описания сборки. Ключ — файл, который есть только у автора.
#     ./unlock.sh /path/to/showcase-key.txt
set -e
KEY="$1"
if [ -z "$KEY" ] || [ ! -f "$KEY" ]; then
  echo "укажите файл ключа: ./unlock.sh /path/to/showcase-key.txt" >&2
  exit 1
fi
find . -name "*.enc" -print | while read -r sealed; do
  plain="${sealed%.enc}"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 300000 -pass "file:$KEY" -in "$sealed" -out "$plain"
  echo "открыт $plain"
done
'''

UNLOCK_CMD = r'''@echo off
rem Открывает запертые описания сборки. Ключ - файл, который есть только у автора.
rem     unlock.cmd C:\path\to\showcase-key.txt
if "%~1"=="" (
  echo укажите файл ключа: unlock.cmd C:\path\to\showcase-key.txt
  exit /b 1
)
for /r %%f in (*.enc) do (
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 300000 -pass "file:%~1" -in "%%f" -out "%%~dpnf"
  echo открыт %%~dpnf
)
'''


def write(name, text):
    io.open(os.path.join(SHOWCASE, name), 'w', encoding='utf-8', newline='\n').write(text)


def main():
    if os.path.exists(SHOWCASE):
        shutil.rmtree(SHOWCASE)
    os.makedirs(SHOWCASE)

    print('копирую исходники…')
    for tree in TREES:
        copy_tree(tree)
    for one in FILES:
        copy_file(one)
    copy_tools()

    print('готовлю ключ…')
    make_key()

    print('запираю описания сборки…')
    locked = lock()

    write('README.md', README)
    write('LICENSE', LICENSE)
    write('.gitignore', GITIGNORE)
    write('unlock.sh', UNLOCK_SH)
    write('unlock.cmd', UNLOCK_CMD)
    lines = ['# Что заперто и почему', '',
             'Эти файлы лежат здесь зашифрованными (`*.enc`). Без них ни сервер, ни клиент',
             'не собираются. Ключ — у автора; открыть их можно `unlock.sh` или `unlock.cmd`.',
             '', '| Файл | Без него нельзя |', '|---|---|']
    lines += ['| `%s` | %s |' % (rel, why) for rel, why in locked]
    lines += ['', 'Шифр: AES-256-CBC, ключ выведен PBKDF2 (300 000 итераций), соль своя у каждого файла.',
              '', 'Это замок, а не сейф: описание сборки восстанавливается по самим исходникам.',
              'Он поднимает цену запуска, но не делает его невозможным — и это сказано прямо.', '']
    write('LOCKED.md', '\n'.join(lines))

    total = sum(len(files) for _, _, files in os.walk(SHOWCASE))
    size = sum(os.path.getsize(os.path.join(where, f))
               for where, _, files in os.walk(SHOWCASE) for f in files)
    print('готово: %d файлов, %.1f МБ в %s' % (total, size / 1048576.0, SHOWCASE))
    print('запертых файлов: %d' % len(locked))


if __name__ == '__main__':
    main()
