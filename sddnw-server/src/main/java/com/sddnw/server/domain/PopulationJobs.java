package com.sddnw.server.domain;

import com.sddnw.server.domain.enums.ColonistJob;

/**
 * Распределение населения колонии по занятиям — фермеры, рабочие, учёные.
 * <p>
 * Как в MOO II, каждый житель занят чем-то одним, поэтому сумма трёх чисел равна
 * населению планеты. Незанятых нет: колонист, снятый с одной работы, тут же встаёт
 * на другую.
 */
public record PopulationJobs(Integer farmers, Integer workers, Integer scientists) {

    public static final PopulationJobs NONE = new PopulationJobs(0, 0, 0);

    public Integer total() {
        return farmers + workers + scientists;
    }

    /** Сколько жителей занято этим делом. */
    public Integer count(ColonistJob job) {
        return switch (job) {
            case FARMERS -> farmers;
            case WORKERS -> workers;
            case SCIENTISTS -> scientists;
        };
    }

    /** То же распределение, где у занятия {@code job} на {@code delta} жителей больше (или меньше). */
    public PopulationJobs plus(ColonistJob job, Integer delta) {
        return switch (job) {
            case FARMERS -> new PopulationJobs(farmers + delta, workers, scientists);
            case WORKERS -> new PopulationJobs(farmers, workers + delta, scientists);
            case SCIENTISTS -> new PopulationJobs(farmers, workers, scientists + delta);
        };
    }
}
