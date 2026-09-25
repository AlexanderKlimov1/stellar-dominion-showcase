package com.moo3.server.domain;

/**
 * Характеристики проекта корабля — п. 8. То, что показывает окно дизайна и по чему
 * считается бой.
 *
 * @param space     полезное место корпуса с учётом боевых отсеков
 * @param spaceUsed сколько места занято компонентами
 * @param cost      во что обходится корабль колонии, единиц производства
 * @param structure прочность корпуса: сколько урона он держит после брони
 * @param armour    броневые очки — снимаются раньше прочности
 * @param shield    очки щита — держат удар раньше брони
 * @param speed     скорость в ГРЕ за ход
 * @param combatSpeed боевая скорость двигателя — п. 8, число оригинала; поле боя делит её
 *                  на четыре и получает клетки за ход
 * @param attack    залп с учётом приборов и расы
 * @param defense   уклонение корпуса с расой — от лучей и снарядов
 * @param missileEvasion уклонение от ракет сверх обычного: постановщик помех — п. 8
 * @param troops    морская пехота на борту — п. 12
 * @param command   командных очков забирает корабль у империи
 */
public record ShipStats(
        Integer space,
        Integer spaceUsed,
        Integer cost,
        Integer structure,
        Integer armour,
        Integer shield,
        Integer speed,
        Integer combatSpeed,
        Integer attack,
        Integer defense,
        Integer missileEvasion,
        Integer troops,
        Integer command
) {

    /** Свободное место в корпусе; отрицательное — проект не помещается. */
    public Integer spaceLeft() {
        return space - spaceUsed;
    }

    /** Помещается ли проект в корпус. */
    public Boolean fits() {
        return spaceUsed <= space;
    }
}
