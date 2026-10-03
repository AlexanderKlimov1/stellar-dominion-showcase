package com.sddnw.server.dto;

import java.util.List;

/** Полное состояние игры без карты. */
public record GameDetailsDto(
        GameSummaryDto game,
        List<PlayerDto> players
) {
}
