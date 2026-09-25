package com.moo3.server.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Перевозка жителей в другую колонию — п. 4.1.1.
 * <p>
 * Грузовой флот резервируется из расчёта <b>один грузовик на единицу населения</b>:
 * пока рейс в пути, эти грузовики не возят еду.
 *
 * @param targetPlanetId колония назначения — своя же, в любой системе
 * @param population     сколько жителей отправить; столько же грузовиков и займётся
 */
public record TransferPopulationRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetPlanetId,

        @NotNull
        @Min(1)
        Integer population
) {
}
