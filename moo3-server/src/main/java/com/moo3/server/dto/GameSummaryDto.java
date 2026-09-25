package com.moo3.server.dto;

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
        OffsetDateTime createdAt
) {
}
