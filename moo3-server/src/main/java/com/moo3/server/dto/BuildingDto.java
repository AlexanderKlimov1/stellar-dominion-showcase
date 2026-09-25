package com.moo3.server.dto;

/**
 * Здание в справочнике — п. 10.
 * <p>
 * Действие здания сюда не входит: справочник отдаёт то, что нужно для выбора стройки,
 * а полное состояние колонии со зданиями приходит вместе с планетой.
 */
public record BuildingDto(
        String code,
        String name,
        String description,
        Integer cost,
        Integer upkeep,
        String requiredTechCode
) {
}
