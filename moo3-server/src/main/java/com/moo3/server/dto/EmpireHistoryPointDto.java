package com.moo3.server.dto;

/**
 * Замер империи за один ход — п. 11.1: точка на графике окна «Инфо».
 * <p>
 * Величин пять, и выбраны они по тому, что игрок и так сравнивает по соседям: сколько у
 * империи людей, сколько она делает, сколько знает и чем воюет. <i>Реконструкция:</i>
 * какие именно графики рисует MOO II, справочники не перечисляют — сказано лишь, что
 * график «показывает, как империя игрока смотрится рядом с соперниками».
 *
 * @param populationK население в тысячах — в тех же единицах, что и у колонии
 * @param buildings   постройки ценой: каждое здание весит свою стоимость производства,
 *                    как на графике оригинала
 * @param fleetPower  сила флотов империи
 * @param might       мощь империи одним числом ({@link com.moo3.server.service.EmpireMightRules}):
 *                    первая линия графика и та же мера, по которой сравнивают себя
 *                    империи ИИ (п. 15)
 */
public record EmpireHistoryPointDto(
        Integer turn,
        Integer populationK,
        Integer colonies,
        Integer buildings,
        Integer production,
        Integer research,
        Integer fleetPower,
        Integer technologies,
        Integer credits,
        Integer might
) {
}
