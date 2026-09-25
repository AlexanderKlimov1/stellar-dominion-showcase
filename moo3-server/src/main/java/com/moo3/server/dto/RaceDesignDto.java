package com.moo3.server.dto;

import java.util.List;

/**
 * Конструктор расы — п. 7: сколько очков у игрока и что на них можно взять.
 *
 * @param picks     бюджет очков выбора
 * @param antiPicks потолок анти-выбора: сколько очков можно вернуть слабыми сторонами.
 *                  Числа два, а не одно, потому что они разошлись: бюджет вырос до
 *                  пятнадцати, потолок остался десятью, как у Силикоидов MOO II
 * @param groups    стороны расы; из каждой берут не больше одной особенности
 */
public record RaceDesignDto(
        Integer picks,
        Integer antiPicks,
        /**
         * Связки с надбавкой: пара, которая вместе даёт больше суммы своих половин, берёт
         * с игрока сверх. Уходит на клиент, потому что очки он считает на лету, и надбавка,
         * всплывшая только в отказе сервера, читалась бы как поломка (журнал, п. 3.71).
         */
        List<CombinationDto> combinations,
        List<GroupDto> groups
) {

    /**
     * Связка с надбавкой — п. 7.
     *
     * @param traits коды сторон: надбавка берётся, когда взяты все
     * @param picks  сколько очков связка стоит СВЕРХ своих частей
     * @param note   на чём измерена
     */
    public record CombinationDto(List<String> traits, List<String> names, Integer picks,
                                 String note) {
    }

    /**
     * Группа особенностей со своими вариантами.
     *
     * @param multiple можно ли взять несколько вариантов сразу — так устроены особые
     *                 способности MOO II; иначе группа переключатель
     */
    public record GroupDto(
            String code,
            String name,
            String description,
            Boolean multiple,
            List<RaceTraitDto> options
    ) {
    }
}
