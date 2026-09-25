package com.moo3.server.domain.enums;

/**
 * Размер планеты и базовое население для климата Gaya — п. 4.1.1 / 4.1.1.1.
 * <p>
 * Размер решает и то, сколько промышленности планета терпит без грязи (п. 10):
 * маленькому миру некуда девать отходы, большому есть. Числа оригинала — 2, 4, 6, 8, 10
 * от крошечной до огромной (StrategyWiki, Calculations: в примере с планетой среднего
 * размера из грязного производства вычитается именно 6).
 */
public enum PlanetSize {

    TINY("Tiny", 6, 2),
    SMALL("Small", 12, 4),
    MEDIUM("Medium", 18, 6),
    LARGE("Large", 24, 8),
    HUGE("Huge", 30, 10);

    private final String label;
    private final Integer basePopulation;
    private final Integer pollutionTolerance;

    PlanetSize(String label, Integer basePopulation, Integer pollutionTolerance) {
        this.label = label;
        this.basePopulation = basePopulation;
        this.pollutionTolerance = pollutionTolerance;
    }

    /**
     * Сколько единиц производства планета этого размера переносит без загрязнения — п. 10.
     * Всё, что сверх, приходится убирать, и уборка съедает само производство.
     */
    public Integer getPollutionTolerance() {
        return pollutionTolerance;
    }

    public String getLabel() {
        return label;
    }

    /** Максимальное население на планете этого размера при климате Gaya. */
    public Integer getBasePopulation() {
        return basePopulation;
    }
}
