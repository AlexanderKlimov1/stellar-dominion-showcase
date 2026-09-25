package com.moo3.server.dto;

import java.util.List;

/**
 * Что империя может поставить в проект корабля — п. 8.
 *
 * @param maxDesigns сколько ячеек проектов у империи: шесть, как в MOO II
 * @param available  умеет ли империя строить корабли: нужны базовые уровни Power и
 *                   Chemistry — двигатель и топливо
 * @param requirement чего для этого не хватает; пусто, когда хватает всего
 * @param hullSizeWithoutStarBase корпуса какого размера поднимает колония без звёздной
 *                   базы: она и есть верфь — п. 8
 */
public record ShipCatalogDto(
        List<ShipHullDto> hulls,
        List<ShipComponentDto> components,
        /** Модификации ствола — п. 8: их ставят на оружие в окне дизайна. */
        List<WeaponModificationDto> modifications,
        Integer maxDesigns,
        Boolean available,
        String requirement,
        Integer hullSizeWithoutStarBase
) {
}
