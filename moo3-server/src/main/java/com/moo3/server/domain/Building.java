package com.moo3.server.domain;

import com.moo3.server.domain.enums.BuildingEffectType;

import java.util.Map;

/**
 * Здание из описания зданий — п. 10.
 * <p>
 * Стоимость в единицах производства, содержание в кредитах за ход. Здание доступно
 * колонии после того, как изучена технология {@code requiredTechCode}; без неё —
 * с самого начала партии.
 *
 * @param effects действие здания: сколько чего оно даёт колонии
 */
public record Building(
        String code,
        LocalizedText names,
        LocalizedText descriptions,
        Integer cost,
        Integer upkeep,
        String requiredTechCode,
        Map<BuildingEffectType, Integer> effects
) {

    /**
     * Название на языке читателя — п. 3.5.
     * <p>
     * Отдельным методом, а не полем записи: так остались нетронутыми семь десятков мест,
     * которые спрашивают у справочника название, и каждое получает его на своём языке.
     */
    public String name() {
        return names.text();
    }

    /** Описание на языке читателя — п. 3.5. */
    public String description() {
        return descriptions == null ? null : descriptions.text();
    }

    /** Сколько здание даёт этого эффекта; ноль — не даёт вовсе. */
    public Integer amount(BuildingEffectType type) {
        return effects.getOrDefault(type, 0);
    }
}
