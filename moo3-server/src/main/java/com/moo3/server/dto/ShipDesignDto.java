package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Проект корабля — п. 8: корпус с компонентами и всё, что из них следует.
 * <p>
 * Числа уже с расовыми поправками: атака и защита у одного и того же проекта у разных
 * рас разные — п. 7.
 *
 * @param slot      ячейка проекта, 1..6
 * @param space     полезное место корпуса с учётом боевых отсеков
 * @param spaceUsed сколько места занято
 * @param cost      во что обходится корабль колонии, единиц производства
 * @param power     боевая сила корабля: по ней сходятся флоты
 * @param obsolete  проект вытеснен из ячейки новым; строить по нему нельзя, а корабли летают
 */
public record ShipDesignDto(
        UUID id,
        Integer slot,
        String name,
        String hullCode,
        String hullName,
        List<ShipDesignComponentDto> components,
        Integer space,
        Integer spaceUsed,
        Integer cost,
        Integer structure,
        Integer armour,
        Integer shield,
        Integer speed,
        /** Боевая скорость двигателя — п. 8, число оригинала. */
        Integer combatSpeed,
        Integer attack,
        Integer defense,
        /** Уклонение от ракет сверх обычной защиты — п. 8: постановщик помех. */
        Integer missileEvasion,
        Integer troops,
        Integer command,
        Integer power,
        Boolean obsolete
) {
}
