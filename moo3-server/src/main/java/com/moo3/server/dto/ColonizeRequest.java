package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Заселение планеты готовой колониальной базой — п. 4.1.
 *
 * @param targetPlanetId планета, которую заселяют: свободная и пригодная для жизни
 *                       планета той же системы, где стоит построившая базу колония
 */
public record ColonizeRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetPlanetId
) {
}
