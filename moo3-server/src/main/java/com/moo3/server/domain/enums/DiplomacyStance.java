package com.moo3.server.domain.enums;

/**
 * Состояние отношений двух империй — п. 15.
 * <p>
 * Империи, которые ещё не встретились, отношений не имеют вовсе: строки в базе нет.
 * Знакомство начинается с нейтралитета, дальше это дело дипломатии.
 */
public enum DiplomacyStance {

    /** Познакомились, договоров нет. */
    NEUTRAL("Нейтралитет"),

    /** Мирный договор. */
    PEACE("Мир"),

    /** Война. */
    WAR("Война");

    private final String label;

    DiplomacyStance(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
