package com.moo3.server.dto;

/**
 * Корпус корабля в окне дизайна — п. 8.
 *
 * @param structure        прочность корпуса до брони и щитов
 * @param command          командных очков забирает корабль у империи (1 у фрегата, 6 у Leviathan)
 * @param hitChancePercent шанс MOO II попасть по кораблю такого размера
 * @param systemFactor     во сколько раз дороже и объёмнее на этом корпусе всё, кроме оружия
 * @param available        изучена ли технология корпуса: чего нет, из того не строят
 * @param size             размер корпуса 1..6: два наименьших строит колония и без верфи
 */
public record ShipHullDto(
        String code,
        String name,
        String description,
        Integer size,
        Integer space,
        Integer cost,
        Integer structure,
        Integer command,
        Integer hitChancePercent,
        Integer systemFactor,
        String requiredTechCode,
        /** Название нужной технологии на языке читателя — п. 3.5; пусто, если не нужна. */
        String requiredTechName,
        Boolean available
) {
}
