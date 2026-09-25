package com.moo3.server.domain.enums;

/**
 * Раздел списка изученного в окне Info — п. 11.1.
 * <p>
 * Ровно четыре, как в оригинале: руководство MOO II про экран Tech Review говорит, что
 * «список технологий разделён на четыре части, доступные кнопками внизу: General
 * Achievements, Colony improvements, Weapons и Ship Equipment». Дерево технологий делится
 * не так — у него восемь разделов науки, — поэтому окно «Инфо» раскладывает изученное
 * по-своему, как и оригинал.
 */
public enum TechnologySection {

    /** Общие достижения: всё, что не встало ни зданием, ни частью корабля. */
    GENERAL("Достижения"),

    /** Улучшения колоний: технология открыла здание. */
    COLONY("Колонии"),

    /** Оружие: технология открыла пушку, ракету или торпеду. */
    WEAPON("Оружие"),

    /** Оснащение кораблей: корпуса, двигатели, броня, щиты и особые модули. */
    SHIP("Корабли");

    private final String label;

    TechnologySection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
