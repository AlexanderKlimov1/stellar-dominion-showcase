package com.moo3.server.domain.enums;

/**
 * Что сейчас с лидером у этого игрока — п. 6.
 * <p>
 * Предложение и служба — разные состояния одной строки: лидер сперва предлагает себя
 * ({@code OFFERED}), и предложение живёт тридцать ходов, потом либо нанят
 * ({@code HIRED}), либо отказан ({@code DISMISSED}). Отказанный не пропадает навсегда:
 * в MOO II он может прийти снова, уже уровнем выше.
 */
public enum LeaderState {

    /** Предлагает службу: ждёт решения игрока. */
    OFFERED("Предлагает службу"),

    /** Нанят: получает жалованье и служит. */
    HIRED("На службе"),

    /** Отказано или уволен. */
    DISMISSED("Отказано");

    private final String label;

    LeaderState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
