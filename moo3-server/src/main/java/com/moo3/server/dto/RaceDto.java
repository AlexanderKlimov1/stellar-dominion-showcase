package com.moo3.server.dto;

import java.util.List;

/**
 * Готовая раса MOO II — п. 5 / п. 7.
 *
 * @param traits коды сторон расы: те же, что покупает игрок в конструкторе. Экран выбора
 *               расы разворачивает их в названия по справочнику особенностей, который у
 *               него уже есть, — второй раз одно и то же по сети не гоняем
 */
public record RaceDto(
        String code,
        String name,
        String description,
        String homeClimateCode,
        String color,
        List<String> traits
) {
}
