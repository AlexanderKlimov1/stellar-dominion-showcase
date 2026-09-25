package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Ответ на конец хода игрока — п. 11.1.
 * <p>
 * Ход объявляет каждый игрок сам, а галактика считается один раз, когда объявили все.
 * Поэтому ответ говорит две вещи: пересчитан ли ход ({@code advanced}) или партия ждёт
 * остальных, и кого именно ждёт. Отчёт приходит только тому, чей запрос и запустил
 * пересчёт; остальные получат свой той же формы через подписку на события партии.
 *
 * @param state      состояние партии после действия
 * @param advanced   ход пересчитан; {@code false} — игрок отметился и ждёт остальных
 * @param waitingFor кого ещё ждут: игроки-люди, не закончившие ход
 * @param report     что изменилось за ход; пусто, пока ход не пересчитан
 */
public record EndTurnResponse(
        GameDetailsDto state,
        Boolean advanced,
        List<UUID> waitingFor,
        TurnReportDto report
) {
}
