package com.moo3.server.dto;

import java.util.UUID;

/**
 * Чем кончился прогон нескольких ходов — этап 0 балансировки.
 *
 * @param played      сколько ходов и правда сыграно: меньше запрошенного значит, что
 *                    партия кончилась или ждёт другого человека
 * @param turn        номер хода после прогона
 * @param status      состояние партии: {@code FINISHED} — победитель найден
 * @param winnerSlot  место победителя; пусто — партия ещё идёт
 * @param victoryKind чем кончилась партия: покорением или голосом Совета
 */
public record AdvanceTurnsResponse(
        UUID gameId,
        Integer played,
        Integer turn,
        String status,
        Integer winnerSlot,
        String victoryKind
) {
}
