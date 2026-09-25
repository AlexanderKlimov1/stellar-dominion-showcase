package com.moo3.server.domain.enums;

/**
 * Сторона тактического боя — п. 8.
 * <p>
 * Нападающий входит в бой слева, обороняющийся справа: как в MOO II, где флот атакующего
 * стоит у левого края поля. Кто есть кто, решает встреча флотов — напал тот, кто выбрал
 * «атаковать».
 */
public enum BattleSide {

    /** Тот, кто напал: его флот стоит у левого края поля. */
    ATTACKER("Нападающий"),

    /** Тот, на кого напали: его флот стоит у правого края. */
    DEFENDER("Обороняющийся");

    private final String label;

    BattleSide(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public BattleSide other() {
        return this == ATTACKER ? DEFENDER : ATTACKER;
    }
}
