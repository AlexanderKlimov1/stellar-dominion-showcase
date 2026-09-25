package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Конец хода игрока — п. 11.1.
 * <p>
 * Игрок объявляет, что закончил ход, и ждёт остальных: галактика считается один раз,
 * когда закончили все люди партии.
 */
public record EndTurnRequest(
        @NotBlank
        String accessToken
) {
}
