package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Строка списка игр, видимого всем, кто видит IP сервера — п. 3.1. */
public record GameSummaryDto(
        UUID id,
        String name,
        String status,
        String galaxySize,
        Integer widthParsecs,
        Integer heightParsecs,
        Integer starCount,
        Integer humanPlayers,
        Integer maxHumanPlayers,
        Integer totalPlayers,
        Integer turn,
        UUID hostPlayerId,
        /** Идут ли в партии случайные галактические события — п. 11.1. */
        Boolean galacticEvents,
        /** Победитель партии — п. 3; пусто, пока партия идёт. */
        UUID winnerPlayerId,
        /** Чем взята победа: покорением или Высшим советом; пусто, пока партия идёт. */
        String victoryKind,
        OffsetDateTime createdAt,
        /** Побеждает ли удержание Wardenhold — п. 3. */
        Boolean wardenholdVictory,
        /** Кто держит особую звезду; пусто — никто. */
        UUID wardenholdHolderPlayerId,
        /**
         * Сколько ходов держателю осталось до победы; пусто — звезду никто не держит. Число
         * считает сервер ({@code WardenholdRules}): правило счёта живёт в одном месте.
         */
        Integer wardenholdTurnsLeft,
        /** Собирается ли Высший совет — п. 3. */
        Boolean council,
        /** Подводится ли итог по могуществу — п. 3. */
        Boolean mightVictory,
        /** На каком ходу подводится итог по могуществу; число правила, а не настройка партии. */
        Integer mightVictoryTurn,
        /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
        Integer turnSeconds,
        /**
         * Когда истекает текущий ход; пусто — срока нет. Мгновение, а не «осталось секунд»:
         * клиент ведёт обратный отсчёт сам, и сводка, прочитанная минуту назад, не врёт.
         */
        OffsetDateTime turnDeadline
) {
}
