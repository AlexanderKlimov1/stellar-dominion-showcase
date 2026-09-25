package com.moo3.server.dto;

import java.util.UUID;

/**
 * Шпион империи — п. 13.
 *
 * @param mission      задание кодом: HOME, STEAL_TECH, SABOTAGE
 * @param missionLabel то же по-русски
 * @param missionCost  во сколько очков обходится операция; у работы дома — ноль
 * @param points       накоплено очков на текущую операцию
 * @param targetName   к кому отправлен; пусто — агент дома
 */
public record SpyDto(
        UUID id,
        String mission,
        String missionLabel,
        Integer missionCost,
        Integer points,
        UUID targetPlayerId,
        String targetName,
        Integer createdTurn
) {
}
