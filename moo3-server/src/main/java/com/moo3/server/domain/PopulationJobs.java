package com.moo3.server.domain;

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
}
