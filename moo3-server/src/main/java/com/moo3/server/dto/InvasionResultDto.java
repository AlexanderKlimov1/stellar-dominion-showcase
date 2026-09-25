package com.moo3.server.dto;

/**
 * Итог наземного боя — п. 12.
 *
 * @param captured     колония захвачена
 * @param survivors    сколько жителей осталось у победителя
 * @param attackPower  сила десанта
 * @param defencePower сила обороны
 * @param system       система после боя: в ней изменились обе планеты
 */
public record InvasionResultDto(
        Boolean captured,
        Integer survivors,
        Integer attackPower,
        Integer defencePower,
        StarSystemDto system
) {
}
