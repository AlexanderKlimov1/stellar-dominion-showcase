# -*- coding: utf-8 -*-
"""
Архитектурная схема подсистем «Дизайн кораблей», «Тактический бой», «Демонстрационный
бой» и «Стоимости особенностей рас» в нотации ArchiMate 3.

Зачем генератор, а не нарисованная руками картинка: схема живёт рядом с кодом и стареет
вместе с ним. Модель описана здесь списками элементов и связей, а на выходе получаются
два представления одного и того же:

* ``moo3-architecture.archimate`` — модель для Archi (открывается File → Open), с
  четырьмя видами и документацией на каждом элементе;
* ``moo3-architecture-*.svg`` — те же виды картинками, чтобы посмотреть без Archi.

Раскладка задаётся сеткой (столбец, строка): координаты в файле модели и в SVG берутся
из одного места, поэтому вид в Archi и картинка совпадают.

Запуск: ``python tools\\archimate_model.py`` — кладёт файлы в ``docs\\architecture``.
"""

import io
import os
import xml.etree.ElementTree as ElementTree

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "architecture")

# --- сетка раскладки: одна на файл модели и на картинку ---
BOX_W = 180
BOX_H = 72
STEP_X = 210
STEP_Y = 112
MARGIN = 28

# Вид элемента → тип в ArchiMate, папка модели, цвет слоя.
KINDS = {
    "actor": ("BusinessActor", "business", "#FFFFB5"),
    "process": ("BusinessProcess", "business", "#FFFFB5"),
    "component": ("ApplicationComponent", "application", "#B5FFFF"),
    "appservice": ("ApplicationService", "application", "#B5FFFF"),
    "data": ("DataObject", "application", "#B5FFFF"),
    "node": ("Node", "technology", "#C9E7B7"),
    "software": ("SystemSoftware", "technology", "#C9E7B7"),
    "artifact": ("Artifact", "technology", "#C9E7B7"),
}

RELATIONS = {
    "serving": "ServingRelationship",
    "assignment": "AssignmentRelationship",
    "realization": "RealizationRelationship",
    "access": "AccessRelationship",
    "composition": "CompositionRelationship",
    "association": "AssociationRelationship",
}

# ---------------------------------------------------------------- элементы

ELEMENTS = [
    # ключ, вид, имя, документация
    ("B1", "actor", u"Игрок",
     u"Человек за партией: проектирует корабли, ведёт бой, открывает демонстрацию."),
    ("B2", "actor", u"Разработчик баланса",
     u"Тот, кто правит числа игры: стоимости особенностей рас и запись показательного боя."),
    ("B3", "process", u"Спроектировать корабль",
     u"Выбор корпуса и набивка гнёзд компонентами — окно Ship Design оригинала (п. 8)."),
    ("B4", "process", u"Провести тактический бой",
     u"Ручной бой на поле 20x12: корабли ходят по инициативе, а не игроки по очереди (п. 8)."),
    ("B5", "process", u"Посмотреть демонстрационный бой",
     u"Показательный бой из главного меню: обеими эскадрами ходит сервер, человек только смотрит."),
    ("B6", "process", u"Настроить стоимости особенностей рас",
     u"Правка цен race picks прямо из игры: изменение ложится в JSON-справочник сервера."),
    ("B7", "process", u"Записать бой в GIF",
     u"Съёмка показательного боя в файл: нужна, чтобы показывать игру там, где сервера нет."),

    ("C0", "component", u"moo3-client (React + Phaser)",
     u"Клиент партии. Правила повторяет только для предпросмотра и подсветки — считает всё сервер."),
    ("C1", "component", u"ShipDesignScreen",
     u"Окно проекта: ячейки проектов, состав корпуса, список компонентов гнезда (ui/ship)."),
    ("C2", "component", u"BattleScreen",
     u"Сцена боя: очередь по инициативе, журнал, силы сторон. Она же показывает демонстрацию —\n"
     u"показательный бой идёт по тем же данным, что и настоящий."),
    ("C3", "component", u"BattleField / ShotEffect / ShipDebris",
     u"Поле боя: корабли прямоугольниками по корпусу, выстрелы лучом, очередью и ракетой,\n"
     u"гибель — разлёт обломков."),
    ("C4", "component", u"RaceCostEditor",
     u"Экран правки цен особенностей рас (ui/race)."),
    ("C5", "component", u"MainMenu",
     u"Начальный экран: отсюда открываются редактор стоимостей и демонстрационный бой."),
    ("C6", "component", u"api/client.ts",
     u"Единственное место, где клиент ходит в REST: типы ответов описаны в api/types.ts."),
    ("C7", "component", u"shipDesignRules.ts / battleVisuals.ts",
     u"Повтор правил на клиенте — только чтобы показать проект до сохранения и подсветить\n"
     u"достижимые клетки. Источник правды остаётся на сервере."),

    ("API1", "appservice", u"API проектов кораблей",
     u"/api/games/{id}/ship-designs: каталог, список проектов, сохранение и снятие проекта."),
    ("API2", "appservice", u"API тактического боя",
     u"/api/games/{id}/battles: идущие бои, состояние поля, ход корабля."),
    ("API3", "appservice", u"API справочников и демонстрации",
     u"/api/reference: справочники игры, стоимости особенностей рас, демонстрационный бой."),

    ("S0", "component", u"moo3-server (Spring Boot)",
     u"Сервер партии: все игровые правила здесь, клиент им только пользуется."),
    ("ERR", "component", u"GlobalExceptionHandler",
     u"Ошибки клиента не выглядят поломкой сервера: 400/404/405/415 вместо общего 500."),
    ("CTL1", "component", u"ShipDesignController", u"Веб-слой подсистемы проектов кораблей."),
    ("CTL2", "component", u"BattleController", u"Веб-слой тактического боя."),
    ("CTL3", "component", u"ReferenceController",
     u"Веб-слой справочников: сюда же попали правка стоимостей рас и демонстрационный бой."),

    ("A1", "component", u"ShipDesignService",
     u"Шесть ячеек проектов на игрока, устаревание при перезаписи, состав для стройки."),
    ("A2", "component", u"ShipDesignRules",
     u"Все числа корабля: место, цена, залп, защита, боевая сила. Правятся только здесь."),
    ("A3", "component", u"ShipCatalog",
     u"Корпуса и компоненты из JSON с перечиткой по времени файла: новая пушка — запись\n"
     u"в файле, а не миграция."),
    ("A4", "component", u"TacticalBattleService",
     u"Поле боя: расстановка, очередь по инициативе корабля, ход, залп, потери.\n"
     u"Кораблями ИИ ходит сам сервер."),
    ("A5", "component", u"BattleRules",
     u"Числа боя: размер поля, дальность, инициатива, шанс попадания, пробитие щита."),
    ("A6", "component", u"BattleService",
     u"Бои партии: начало боя по встрече флотов и выдача идущих боёв обеим сторонам."),
    ("A7", "component", u"DemoBattleService",
     u"Показательный бой: две эскадры по шесть кораблей, перевес подбирается числом стволов.\n"
     u"Собран на том же бое, что и настоящий, — демонстрация показывает игру, а не её подобие."),
    ("A8", "component", u"RaceTraitCatalog",
     u"Стоимости особенностей рас: чтение и запись файла деревом, чтобы правка одной цены\n"
     u"не давала диф на весь файл."),
    ("A9", "component", u"FleetService",
     u"Флоты по системам и их состав по проектам — из него берутся корабли для боя."),
    ("A10", "component", u"EncounterService",
     u"Встреча флотов: с неё начинается настоящий бой."),

    ("D1", "data", u"Проекты кораблей\n(ship_design, ship_design_component)",
     u"В базе только коды компонентов — ссылки в JSON-каталог."),
    ("D2", "data", u"Бой\n(space_battle, battle_ship)",
     u"Поле и корабли на нём поимённо: бой переживает перезагрузку страницы обеими сторонами."),
    ("D3", "data", u"Флоты\n(fleet, fleet_ship)",
     u"Состав флота по проектам: сила флота считается по нему."),
    ("D4", "data", u"Каталог корпусов и компонентов",
     u"Справочник кораблей: корпуса и компоненты парами «эффект — количество»."),
    ("D5", "data", u"Стоимости особенностей рас",
     u"Цены race picks и бюджет очков."),

    ("T1", "node", u"Браузер",
     u"Клиент отдаётся Vite в разработке и статикой в сборке."),
    ("T2", "node", u"Spring Boot 3.4 / Java 23",
     u"Сервер на 8080, запускается из moo3-server: пути к справочникам относительные."),
    ("T3", "software", u"PostgreSQL 15 (порт 5433)",
     u"База moo3, схему накатывает Liquibase при старте."),
    ("T4", "artifact", u"resources/Ships/ship-components.json",
     u"Корпуса и компоненты. Читается на лету — правится без миграций."),
    ("T5", "artifact", u"resources/Races/race-traits.json",
     u"Цены особенностей рас. Тот же файл и правит экран стоимостей."),
    ("T6", "component", u"tools/demo_battle_gif.py",
     u"Съёмка: ведёт настоящий показательный бой через API и рисует кадры теми же правилами\n"
     u"размеров, что и сцена боя."),
    ("T7", "artifact", u"demo-battle.gif",
     u"Запись боя в корне проекта."),
]

# ---------------------------------------------------------------- связи

RELS = [
    # ключ связи, источник, цель, вид, тип доступа (для access)
    ("r01", "B1", "B3", "assignment", None),
    ("r02", "B1", "B4", "assignment", None),
    ("r03", "B1", "B5", "assignment", None),
    ("r04", "B1", "B6", "assignment", None),
    ("r05", "B2", "B6", "assignment", None),
    ("r06", "B2", "B7", "assignment", None),

    ("r10", "C0", "B3", "serving", None),
    ("r11", "C0", "B4", "serving", None),
    ("r12", "C0", "B5", "serving", None),
    ("r13", "C0", "B6", "serving", None),

    ("r20", "API1", "C0", "serving", None),
    ("r21", "API2", "C0", "serving", None),
    ("r22", "API3", "C0", "serving", None),
    ("r23", "S0", "API1", "realization", None),
    ("r24", "S0", "API2", "realization", None),
    ("r25", "S0", "API3", "realization", None),
    ("r26", "S0", "ERR", "composition", None),
    ("r27", "S0", "D4", "access", "1"),
    ("r28", "S0", "D5", "access", "3"),

    ("r30", "T1", "C0", "assignment", None),
    ("r31", "T2", "S0", "assignment", None),
    ("r32", "T3", "S0", "serving", None),
    ("r33", "T4", "D4", "realization", None),
    ("r34", "T5", "D5", "realization", None),
    ("r35", "API3", "T6", "serving", None),
    ("r36", "T6", "B7", "serving", None),
    ("r37", "T6", "T7", "access", None),

    # дизайн кораблей
    ("r40", "C1", "B3", "serving", None),
    ("r41", "C7", "C1", "serving", None),
    ("r42", "C6", "C1", "serving", None),
    ("r43", "API1", "C6", "serving", None),
    ("r44", "CTL1", "API1", "realization", None),
    ("r45", "A1", "CTL1", "serving", None),
    ("r46", "A2", "A1", "serving", None),
    ("r47", "A3", "A1", "serving", None),
    ("r48", "A1", "D1", "access", "3"),
    ("r49", "A3", "D4", "access", "1"),
    ("r50", "T3", "A1", "serving", None),

    # бой
    ("r60", "C2", "B4", "serving", None),
    ("r61", "C2", "B5", "serving", None),
    ("r62", "C2", "C3", "composition", None),
    ("r63", "API2", "C2", "serving", None),
    ("r64", "API3", "C2", "serving", None),
    ("r65", "CTL2", "API2", "realization", None),
    ("r66", "CTL3", "API3", "realization", None),
    ("r67", "A6", "CTL2", "serving", None),
    ("r68", "A7", "CTL3", "serving", None),
    ("r69", "A4", "A6", "serving", None),
    ("r70", "A4", "A7", "serving", None),
    ("r71", "A5", "A4", "serving", None),
    ("r72", "A9", "A4", "serving", None),
    ("r73", "A10", "A6", "serving", None),
    ("r74", "A4", "D2", "access", "3"),
    ("r75", "A4", "D1", "access", "1"),
    ("r76", "A9", "D3", "access", "3"),

    # стоимости рас
    ("r80", "C4", "B6", "serving", None),
    ("r81", "C5", "C4", "association", None),
    ("r82", "C6", "C4", "serving", None),
    ("r83", "API3", "C6", "serving", None),
    ("r84", "A8", "CTL3", "serving", None),
    ("r85", "A8", "D5", "access", "3"),
]

# ---------------------------------------------------------------- виды

VIEWS = [
    {
        "id": "v1",
        "name": u"1. Обзор",
        "doc": u"Кто чем пользуется: игрок и разработчик баланса, клиент, три службы REST, "
               u"сервер и то, на чём всё стоит.",
        "nodes": [
            ("B1", 1.5, 0, 1), ("B2", 5, 0, 1),
            ("B3", 0, 1, 1), ("B4", 1, 1, 1), ("B5", 2, 1, 1), ("B6", 3, 1, 1), ("B7", 5, 1, 1),
            ("C0", 0, 2, 4), ("T1", 4, 2, 1),
            ("API1", 0, 3, 1), ("API2", 1, 3, 1), ("API3", 2, 3, 1), ("ERR", 3, 3, 1),
            ("T6", 4, 3, 1),
            ("S0", 0, 4, 3), ("T7", 4, 4, 1),
            ("T2", 0, 5, 1), ("T3", 1, 5, 1),
            ("D4", 2, 6, 1), ("D5", 3, 6, 1),
            ("T4", 2, 7, 1), ("T5", 3, 7, 1),
        ],
    },
    {
        "id": "v2",
        "name": u"2. Дизайн кораблей",
        "doc": u"Проект корабля: окно, служба REST, правила и каталог. В базе остаются только "
               u"коды компонентов, сам справочник лежит файлом.",
        "nodes": [
            ("B3", 0, 0, 1),
            ("C1", 0, 1, 1), ("C7", 1, 1, 1), ("C6", 2, 1, 1),
            ("API1", 0, 2, 1),
            ("CTL1", 0, 3, 1),
            ("A1", 0, 4, 1), ("A2", 1, 4, 1), ("A3", 2, 4, 1),
            ("D1", 0, 5, 1), ("T3", 1, 5, 1), ("D4", 2, 5, 1),
            ("T4", 2, 6, 1),
        ],
    },
    {
        "id": "v3",
        "name": u"3. Тактический и демонстрационный бой",
        "doc": u"Показательный бой собран на том же тактическом бое, что и настоящий: сцена, "
               u"правила и поле общие, различается только то, кто отдаёт приказы.",
        "nodes": [
            ("B4", 0, 0, 1), ("B5", 2, 0, 1), ("B7", 4, 0, 1),
            ("C2", 0, 1, 1), ("C3", 1, 1, 1), ("T6", 4, 1, 1),
            ("API2", 0, 2, 1), ("API3", 2, 2, 1), ("T7", 4, 2, 1),
            ("CTL2", 0, 3, 1), ("CTL3", 2, 3, 1),
            ("A6", 0, 4, 1), ("A7", 2, 4, 1),
            ("A4", 1, 5, 1), ("A5", 3, 5, 1),
            ("A10", 0, 6, 1), ("A9", 1, 6, 1),
            ("D3", 1, 7, 1), ("D2", 2, 7, 1), ("D1", 3, 7, 1),
        ],
    },
    {
        "id": "v4",
        "name": u"4. Стоимости особенностей рас",
        "doc": u"Правка цен race picks идёт в тот же файл, из которого справочник читается: "
               u"экран, служба REST, каталог и сам JSON.",
        "nodes": [
            ("B2", 0, 0, 1), ("B6", 1, 0, 1),
            ("C5", 0, 1, 1), ("C4", 1, 1, 1), ("C6", 2, 1, 1),
            ("API3", 1, 2, 1),
            ("CTL3", 1, 3, 1),
            ("A8", 1, 4, 1),
            ("D5", 1, 5, 1),
            ("T5", 1, 6, 1),
        ],
    },
]

BY_KEY = dict((key, (kind, name, doc)) for key, kind, name, doc in ELEMENTS)


def bounds(col, row, span):
    """Прямоугольник элемента по клетке сетки."""
    x = MARGIN + int(col * STEP_X)
    y = MARGIN + row * STEP_Y
    width = BOX_W + (span - 1) * STEP_X
    return x, y, width, BOX_H


def connections(view):
    """Связи вида: только те, у которых на виде есть оба конца."""
    present = set(node[0] for node in view["nodes"])
    return [rel for rel in RELS if rel[1] in present and rel[2] in present]


# ---------------------------------------------------------------- файл модели Archi

def write_archimate(path):
    lines = [u'<?xml version="1.0" encoding="UTF-8"?>',
             u'<archimate:model xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'
             u' xmlns:archimate="http://www.archimatetool.com/archimate"'
             u' name="moo3 — бой и дизайн кораблей" id="id-moo3" version="5.0.0">',
             u'  <purpose>%s</purpose>' % esc(
                 u"Подсистемы, выросшие вокруг корабля: проект корабля, тактический бой, "
                 u"показательный бой и правка стоимостей особенностей рас. "
                 u"Схема собирается из tools/archimate_model.py — правится вместе с кодом.")]

    for folder in ("business", "application", "technology"):
        lines.append(u'  <folder name="%s" id="id-folder-%s" type="%s">'
                     % (folder.capitalize(), folder, folder))
        for key, kind, name, doc in ELEMENTS:
            archi_type, layer, _ = KINDS[kind]
            if layer != folder:
                continue
            lines.append(u'    <element xsi:type="archimate:%s" name="%s" id="id-%s">'
                         % (archi_type, esc(name.replace(u"\n", u" ")), key))
            lines.append(u'      <documentation>%s</documentation>' % esc(doc))
            lines.append(u'    </element>')
        lines.append(u'  </folder>')

    lines.append(u'  <folder name="Relations" id="id-folder-rel" type="relations">')
    for key, src, dst, kind, access in RELS:
        access_attr = u' accessType="%s"' % access if access else u''
        lines.append(u'    <element xsi:type="archimate:%s" id="id-%s" source="id-%s"'
                     u' target="id-%s"%s/>'
                     % (RELATIONS[kind], key, src, dst, access_attr))
    lines.append(u'  </folder>')

    lines.append(u'  <folder name="Views" id="id-folder-views" type="diagrams">')
    for view in VIEWS:
        lines.append(u'    <element xsi:type="archimate:ArchimateDiagramModel" name="%s"'
                     u' id="id-view-%s">' % (esc(view["name"]), view["id"]))
        lines.append(u'      <documentation>%s</documentation>' % esc(view["doc"]))

        # Связи считаются заранее: у цели нужен список входящих, а он собирается по всем.
        outgoing = {}
        incoming = {}
        for rel in connections(view):
            conn_id = u"id-conn-%s-%s" % (view["id"], rel[0])
            outgoing.setdefault(rel[1], []).append((conn_id, rel))
            incoming.setdefault(rel[2], []).append(conn_id)

        for key, col, row, span in view["nodes"]:
            x, y, width, height = bounds(col, row, span)
            target_attr = u''
            if key in incoming:
                target_attr = u' targetConnections="%s"' % u" ".join(incoming[key])
            lines.append(u'      <children xsi:type="archimate:DiagramObject" id="id-do-%s-%s"%s'
                         u' archimateElement="id-%s" fillColor="%s">'
                         % (view["id"], key, target_attr, key, KINDS[BY_KEY[key][0]][2]))
            lines.append(u'        <bounds x="%d" y="%d" width="%d" height="%d"/>'
                         % (x, y, width, height))
            for conn_id, rel in outgoing.get(key, []):
                lines.append(u'        <sourceConnection xsi:type="archimate:Connection" id="%s"'
                             u' source="id-do-%s-%s" target="id-do-%s-%s"'
                             u' archimateRelationship="id-%s"/>'
                             % (conn_id, view["id"], key, view["id"], rel[2], rel[0]))
            lines.append(u'      </children>')
        lines.append(u'    </element>')
    lines.append(u'  </folder>')
    lines.append(u'</archimate:model>')

    io.open(path, "w", encoding="utf-8", newline="\n").write(u"\n".join(lines) + u"\n")


def esc(text):
    return (text.replace(u"&", u"&amp;").replace(u"<", u"&lt;")
            .replace(u">", u"&gt;").replace(u'"', u"&quot;"))


# ---------------------------------------------------------------- картинка вида

FONT = u"'Segoe UI', 'Trebuchet MS', sans-serif"


def wrap(text, limit):
    """Перенос имени по словам: длинное имя иначе вылезает за рамку."""
    out = []
    for chunk in text.split(u"\n"):
        line = u""
        for word in chunk.split():
            if line and len(line) + 1 + len(word) > limit:
                out.append(line)
                line = word
            else:
                line = (line + u" " + word).strip()
        out.append(line)
    return out


def icon(kind, x, y):
    """Значок вида элемента в правом верхнем углу — по нему тип читается без легенды."""
    stroke = u'fill="none" stroke="#33383d" stroke-width="1.2"'
    if kind == "component":
        return (u'<g %s><rect x="%d" y="%d" width="14" height="11"/>'
                u'<rect x="%d" y="%d" width="6" height="3" fill="#fff"/>'
                u'<rect x="%d" y="%d" width="6" height="3" fill="#fff"/></g>'
                % (stroke, x + 3, y, x - 1, y + 2, x - 1, y + 7))
    if kind == "appservice":
        return u'<rect x="%d" y="%d" width="17" height="10" rx="5" %s/>' % (x, y + 1, stroke)
    if kind == "process":
        return (u'<path d="M%d %d h9 l5 5 l-5 5 h-9 z" %s/>' % (x, y + 1, stroke))
    if kind == "actor":
        return (u'<g %s><circle cx="%d" cy="%d" r="3"/>'
                u'<path d="M%d %d v5 M%d %d h9 M%d %d l-4 4 M%d %d l4 4"/></g>'
                % (stroke, x + 8, y + 3, x + 8, y + 6, x + 4, y + 8, x + 8, y + 11, x + 8, y + 11))
    if kind == "data":
        return (u'<g %s><rect x="%d" y="%d" width="17" height="11"/>'
                u'<path d="M%d %d h17"/></g>' % (stroke, x, y + 1, x, y + 5))
    if kind == "node":
        return (u'<g %s><rect x="%d" y="%d" width="13" height="9"/>'
                u'<path d="M%d %d l4 -4 h13 v9 l-4 4"/></g>'
                % (stroke, x, y + 3, x, y + 3))
    if kind == "software":
        return (u'<g %s><path d="M%d %d h13 a5 5 0 0 0 0 -10 h-13 a5 5 0 0 1 0 10 z"/></g>'
                % (stroke, x + 2, y + 12))
    # artifact
    return (u'<g %s><path d="M%d %d h9 l5 5 v8 h-14 z"/><path d="M%d %d h5 v-5"/></g>'
            % (stroke, x, y + 1, x + 9, y + 6))


def edge_point(rect, other):
    """Точка выхода линии: пересечение отрезка между центрами с рамкой элемента."""
    x, y, w, h = rect
    cx, cy = x + w / 2.0, y + h / 2.0
    ox, oy = other[0] + other[2] / 2.0, other[1] + other[3] / 2.0
    dx, dy = ox - cx, oy - cy
    if dx == 0 and dy == 0:
        return cx, cy
    scale = float("inf")
    if dx:
        scale = min(scale, (w / 2.0 + 2) / abs(dx))
    if dy:
        scale = min(scale, (h / 2.0 + 2) / abs(dy))
    return cx + dx * scale, cy + dy * scale


def write_svg(view, path):
    boxes = {}
    for key, col, row, span in view["nodes"]:
        boxes[key] = bounds(col, row, span)

    width = max(max(box[0] + box[2] for box in boxes.values()) + MARGIN, 640)
    # Подпись вида переносится по ширине картинки: у узкого вида она иначе уезжает за край.
    caption = wrap(view["doc"], int((width - 2 * MARGIN) / 6.4))
    shift = 44 + (len(caption) - 1) * 16
    height = max(box[1] + box[3] for box in boxes.values()) + shift + 48 + MARGIN

    out = [u'<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
           u'viewBox="0 0 %d %d" font-family=%s>' % (width, height, width, height, u'"%s"' % FONT),
           u'<rect width="100%%" height="100%%" fill="#f7f7f4"/>',
           u'<defs>',
           u'<marker id="open" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9"'
           u' markerHeight="9" orient="auto"><path d="M0 0 L10 5 L0 10" fill="none"'
           u' stroke="#33383d" stroke-width="1.4"/></marker>',
           u'<marker id="solid" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8"'
           u' markerHeight="8" orient="auto"><path d="M0 0 L10 5 L0 10 z" fill="#33383d"/></marker>',
           u'<marker id="hollow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="10"'
           u' markerHeight="10" orient="auto"><path d="M0 0 L10 5 L0 10 z" fill="#f7f7f4"'
           u' stroke="#33383d" stroke-width="1.2"/></marker>',
           u'<marker id="dot" viewBox="0 0 8 8" refX="4" refY="4" markerWidth="7"'
           u' markerHeight="7" orient="auto"><circle cx="4" cy="4" r="3" fill="#33383d"/></marker>',
           u'<marker id="diamond" viewBox="0 0 12 8" refX="1" refY="4" markerWidth="11"'
           u' markerHeight="9" orient="auto"><path d="M0 4 L6 1 L12 4 L6 7 z" fill="#33383d"/>'
           u'</marker>',
           u'</defs>',
           u'<text x="%d" y="34" font-size="20" font-weight="600" fill="#20242a">%s</text>'
           % (MARGIN, esc(view["name"]))]
    for index, line in enumerate(caption):
        out.append(u'<text x="%d" y="%d" font-size="12.5" fill="#5a6068">%s</text>'
                   % (MARGIN, 56 + index * 16, esc(line)))

    for key in boxes:
        x, y, w, h = boxes[key]
        boxes[key] = (x, y + shift, w, h)

    for rel in connections(view):
        _, src, dst, kind, access = rel
        x1, y1 = edge_point(boxes[src], boxes[dst])
        x2, y2 = edge_point(boxes[dst], boxes[src])
        dash, start, end = u"", u"", u"open"
        if kind == "realization":
            dash, end = u' stroke-dasharray="7 5"', u"hollow"
        elif kind == "assignment":
            start, end = u"dot", u"solid"
        elif kind == "access":
            dash, end = u' stroke-dasharray="2 4"', u"open"
        elif kind == "composition":
            start, end = u"diamond", u""
        elif kind == "association":
            end = u""
        markers = u''
        if start:
            markers += u' marker-start="url(#%s)"' % start
        if end:
            markers += u' marker-end="url(#%s)"' % end
        out.append(u'<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="#33383d"'
                   u' stroke-width="1.3" opacity="0.75"%s%s/>' % (x1, y1, x2, y2, dash, markers))

    for key, _, _, _ in view["nodes"]:
        kind, name, _ = BY_KEY[key]
        x, y, w, h = boxes[key]
        colour = KINDS[kind][2]
        out.append(u'<rect x="%d" y="%d" width="%d" height="%d" rx="3" fill="%s"'
                   u' stroke="#33383d" stroke-width="1.1"/>' % (x, y, w, h, colour))
        out.append(icon(kind, x + w - 24, y + 6))
        rows = wrap(name, max(20, int(w / 8.2)))
        # Длинное неразрывное имя (путь к файлу, имя класса) переносить некуда — тогда
        # уменьшается кегль, иначе строка вылезает за рамку.
        longest = max(len(row) for row in rows)
        size = min(12.5, (w - 16) / (0.55 * max(1, longest)))
        step = size * 1.15
        top = y + h / 2 - (len(rows) - 1) * step / 2 + size / 3
        for index, row in enumerate(rows):
            out.append(u'<text x="%d" y="%.1f" font-size="%.1f" fill="#20242a"'
                       u' text-anchor="middle">%s</text>'
                       % (x + w / 2, top + index * step, size, esc(row)))

    out.append(legend(MARGIN, height - 40))
    out.append(u'</svg>')
    io.open(path, "w", encoding="utf-8", newline="\n").write(u"\n".join(out) + u"\n")


def legend(x, y):
    """Легенда связей: без неё пунктир и ромб приходится помнить наизусть."""
    parts = [u'<g font-size="11.5" fill="#5a6068">']
    items = [(u"обслуживает", u"", u"open", u""),
             (u"назначен", u"dot", u"solid", u""),
             (u"реализует", u"", u"hollow", u' stroke-dasharray="7 5"'),
             (u"обращается к данным", u"", u"open", u' stroke-dasharray="2 4"'),
             (u"состоит из", u"diamond", u"", u"")]
    offset = x
    for label, start, end, dash in items:
        markers = u''
        if start:
            markers += u' marker-start="url(#%s)"' % start
        if end:
            markers += u' marker-end="url(#%s)"' % end
        parts.append(u'<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#33383d" stroke-width="1.3"'
                     u'%s%s/>' % (offset, y, offset + 34, y, dash, markers))
        parts.append(u'<text x="%d" y="%d">%s</text>' % (offset + 40, y + 4, esc(label)))
        offset += 54 + len(label) * 6.6
        offset = int(offset)
    parts.append(u'</g>')
    return u"".join(parts)


def main():
    out_dir = os.path.normpath(OUT_DIR)
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)

    model = os.path.join(out_dir, "moo3-architecture.archimate")
    write_archimate(model)
    ElementTree.parse(model)  # разбор на месте: битый XML Archi просто не откроет
    print(u"модель: %s" % model)

    for view in VIEWS:
        name = "moo3-architecture-%s.svg" % view["id"]
        path = os.path.join(out_dir, name)
        write_svg(view, path)
        ElementTree.parse(path)
        print(u"вид %s: %s" % (view["id"], path))

    used = set()
    for rel in RELS:
        used.add(rel[1])
        used.add(rel[2])
    shown = set()
    for view in VIEWS:
        for node in view["nodes"]:
            shown.add(node[0])
    missing = sorted(set(BY_KEY) - shown)
    if missing:
        print(u"не попали ни на один вид: %s" % u", ".join(missing))
    orphan = sorted(set(BY_KEY) - used)
    if orphan:
        print(u"без связей: %s" % u", ".join(orphan))


if __name__ == "__main__":
    main()
