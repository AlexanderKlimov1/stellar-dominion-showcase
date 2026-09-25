package com.moo3.server.dto;

import java.util.List;

/**
 * Экран лидеров — п. 6: офицерский резерв империи целиком.
 *
 * @param offers        кто предлагает службу прямо сейчас
 * @param colony        колониальные лидеры на службе
 * @param ship          корабельные лидеры на службе
 * @param colonySlots   сколько мест для колониальных лидеров всего
 * @param shipSlots     сколько мест для корабельных лидеров всего
 * @param salaryPerTurn сколько империя платит лидерам за ход (уже за вычетом того, что
 *                      приносят «Богачи»)
 * @param credits       казна империи: по ней видно, хватает ли на наём
 */
public record LeadersDto(
        List<LeaderDto> offers,
        List<LeaderDto> colony,
        List<LeaderDto> ship,
        Integer colonySlots,
        Integer shipSlots,
        Integer salaryPerTurn,
        Integer credits
) {
}
