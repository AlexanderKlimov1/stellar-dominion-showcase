package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Карта галактики: прямоугольное поле в парсеках и звёздные системы — п. 3 / п. 11.3.
 * <p>
 * Парсеки те же, что у дальности топлива и скорости кораблей (п. 8): множителя между
 * картой и полётом больше нет — четыре парсека Standard Fuel Cells и есть четыре парсека
 * на карте.
 */
public record GalaxyMapDto(
        UUID gameId,
        String galaxySize,
        Integer widthParsecs,
        Integer heightParsecs,
        Double minStarDistanceParsecs,
        List<StarSystemDto> systems
) {
}
