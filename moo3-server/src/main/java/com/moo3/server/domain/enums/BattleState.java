package com.moo3.server.domain.enums;

/**
 * Состояние тактического боя — п. 8.
 * <p>
 * Бой идёт, пока на поле остаются корабли обеих сторон и никто не ушёл. Кончился — исход
 * записан, потери списаны с флотов, и открывать бой заново уже нельзя.
 */
public enum BattleState {

    /** Идёт: ходит корабль за кораблём по инициативе. */
    IN_PROGRESS("Идёт бой"),

    /** Кончился: сторона разбита или ушла с поля. */
    FINISHED("Бой окончен");

    private final String label;

    BattleState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
