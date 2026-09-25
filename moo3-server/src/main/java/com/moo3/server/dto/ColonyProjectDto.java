package com.moo3.server.dto;

/**
 * Проект, доступный колонии для постройки — п. 10.
 *
 * @param special    особый проект MOO II — дома или товары: он не заканчивается, зданием
 *                   не становится и на планете ничего не занимает
 * @param sellValue  сколько кредитов даст казне продажа этой постройки — п. 10; пусто у
 *                   особых проектов: продавать в них нечего
 * @param repeatable можно ли поставить проект в очередь ещё раз, когда он уже строится, —
 *                   п. 10: здание на планете одно, а кораблей, шпионов и грузовиков
 *                   строят сколько угодно. Правило живёт на сервере
 *                   ({@link com.moo3.server.domain.ColonyProject#repeatable}), а экран
 *                   по нему гасит кнопку «в очередь»
 */
public record ColonyProjectDto(
        String code,
        String name,
        String description,
        Integer cost,
        Integer upkeep,
        Boolean special,
        Integer sellValue,
        Boolean repeatable
) {
}
