package com.moo3.server.dto;

import java.util.List;

/**
 * Уровень раздела: на нём игрок выбирает одну технологию из предложенных — п. 9.
 *
 * @param order          порядковый номер уровня в разделе, начиная с 1
 * @param cost           стоимость уровня в очках исследований
 * @param cumulativeCost стоимость раздела с самого начала до этого уровня включительно
 * @param general        общий уровень: его технологии выдаются все сразу, без выбора
 */
public record ResearchLevelDto(
        Integer order,
        String name,
        Integer cost,
        Integer cumulativeCost,
        Boolean general,
        List<ResearchOptionDto> options
) {
}
