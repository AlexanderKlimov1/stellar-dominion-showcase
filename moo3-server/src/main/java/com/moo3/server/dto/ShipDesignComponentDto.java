package com.moo3.server.dto;

import com.moo3.server.domain.enums.ShipComponentSlot;

/**
 * Компонент в готовом проекте — п. 8: что стоит, сколько штук и во что обошлось
 * на этом корпусе.
 *
 * @param modifications коды модификаций ствола — п. 8; у прочих гнёзд пусто
 * @param damage        урон одного выстрела с поправкой модификаций; у не-оружия пусто
 */
public record ShipDesignComponentDto(
        String code,
        String name,
        ShipComponentSlot slot,
        Integer count,
        Integer space,
        Integer cost,
        java.util.List<String> modifications,
        Integer damage
) {
}
