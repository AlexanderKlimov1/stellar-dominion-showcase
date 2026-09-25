package com.moo3.server.dto;

import java.util.List;

/**
 * Разведка империи — п. 13.
 *
 * @param points        накоплено очков шпионажа
 * @param pointsPerTurn сколько прибавляется за ход
 * @param racePoints    расовая поправка к очкам за ход
 * @param spies         шпионы империи: их строят колонии, каждый приносит очко за ход
 * @param counterStrength сила контрразведки: столько очков империя отнимает у каждого
 *                        чужого агента за ход — п. 13
 * @param agents        сами агенты: у каждого своё задание и свои накопленные очки
 */
public record EspionageDto(
        Integer points,
        Integer pointsPerTurn,
        Integer racePoints,
        Integer spies,
        Integer counterStrength,
        List<SpyDto> agents
) {
}
