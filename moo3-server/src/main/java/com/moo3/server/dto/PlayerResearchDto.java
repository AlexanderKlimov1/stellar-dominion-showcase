package com.moo3.server.dto;

import java.util.List;

/**
 * Исследования игрока — п. 9: текущая цель, вложенные в неё очки и изученное.
 * <p>
 * Империя исследует одну технологию за раз, поэтому цель здесь одна. Коды раздела,
 * уровня и технологии пусты, пока игрок не выбрал, что исследовать.
 *
 * @param levelCost          базовая стоимость уровня в очках исследований
 * @param researchPoints     вложено в текущую цель
 * @param researchPerTurn    доход очков исследований за ход
 * @param remainingPoints    сколько осталось вложить до базовой стоимости
 * @param breakthroughPercent шанс прорыва на ближайшем ходу; 100 — прорыв гарантирован
 * @param creative           изобретательная раса (п. 7): прорыв выдаёт весь уровень, а не
 *                           одну технологию. Признак нужен экрану выбора: у такой расы
 *                           выбирается уровень целиком, и отмечать в нём одну строку
 *                           значило бы соврать — придут все
 */
public record PlayerResearchDto(
        String categoryCode,
        Integer levelOrder,
        String optionCode,
        String optionName,
        Integer levelCost,
        Integer researchPoints,
        Integer researchPerTurn,
        Integer remainingPoints,
        Integer breakthroughPercent,
        Boolean creative,
        List<AcquiredTechnologyDto> acquired
) {
}
