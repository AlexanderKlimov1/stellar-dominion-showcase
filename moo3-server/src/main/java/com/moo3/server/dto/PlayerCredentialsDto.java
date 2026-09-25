package com.moo3.server.dto;

import java.util.UUID;

/** Выдаётся создателю игры и присоединившимся игрокам — по нему подтверждается владение слотом. */
public record PlayerCredentialsDto(
        UUID gameId,
        UUID playerId,
        String accessToken,
        Boolean host
) {
}
