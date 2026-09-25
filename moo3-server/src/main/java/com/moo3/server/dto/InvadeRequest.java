package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Высадка десанта на чужую колонию — п. 12.
 *
 * @param targetPlanetId чужая колония той же системы
 * @param troops         сколько жителей отправлено в десант
 */
public record InvadeRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetPlanetId,

        @NotNull
        @Positive
        Integer troops
) {
}
