package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;

/** Сохранение партии по команде игрока — «Игра» → «Сохранить». */
public record SaveGameRequest(
        @NotBlank
        String accessToken
) {
}
