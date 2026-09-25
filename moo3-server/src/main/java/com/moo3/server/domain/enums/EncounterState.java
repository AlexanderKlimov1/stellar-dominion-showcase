package com.moo3.server.domain.enums;

/**
 * Состояние встречи флотов в системе — п. 8.
 * <p>
 * Порядок решений как в MOO II: первым выбирает тот, чей флот быстрее — атаковать или
 * разойтись. Отказался от боя — выбор переходит второму: он может напасть сам. Отказались
 * оба — флоты расходятся, и до следующего хода их никто не тревожит.
 */
public enum EncounterState {

    /** Решает игрок с большей инициативой. */
    WAITING_FIRST("Ждём решения первого"),

    /** Первый разошёлся миром, решает второй. */
    WAITING_SECOND("Ждём решения второго"),

    /** Идёт тактический бой: сцена открыта, корабли ходят по инициативе — п. 8. */
    IN_BATTLE("Идёт бой"),

    /** Бой состоялся. */
    BATTLE("Бой"),

    /** Оба разошлись миром. */
    IGNORED("Разошлись");

    private final String label;

    EncounterState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Встреча ещё ждёт решения: такие показываются игроку в начале хода. */
    public Boolean isPending() {
        return this == WAITING_FIRST || this == WAITING_SECOND;
    }
}
