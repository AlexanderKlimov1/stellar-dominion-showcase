package com.moo3.server.dto;

import java.util.UUID;

/**
 * Событие партии, которое сервер шлёт подписчикам — п. 11.1.
 * <p>
 * Игроки ходят одновременно, и большинство изменений происходит не по их запросу:
 * сосед закончил ход, галактика пересчиталась, империя познакомилась с чужой расой.
 * Опрашивать сервер ради этого пришлось бы всем и постоянно, поэтому сервер сам
 * рассказывает о событиях по подписке (SSE), а клиент только слушает.
 *
 * @param type     вид события: PLAYER_READY, TURN_ADVANCED, GAME_STARTED, GAME_DELETED
 * @param gameId   партия, к которой относится событие
 * @param turn     ход партии на момент события
 * @param playerId игрок, о котором событие; пусто — событие про всю партию
 * @param text     короткая строка для журнала
 * @param report   что изменилось лично у получателя; только у TURN_ADVANCED
 */
public record GameEventDto(
        String type,
        UUID gameId,
        Integer turn,
        UUID playerId,
        String text,
        TurnReportDto report
) {
}
