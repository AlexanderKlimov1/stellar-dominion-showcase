package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Высадка с флота — п. 4.1 и п. 12: колония с колониального корабля или десант с
 * транспортов.
 * <p>
 * Запрос один на оба действия: во флоте выбирать нечего — колонию высаживает
 * колониальный корабль, десант высаживают все транспорты разом, — и различает их путь
 * запроса, а не его тело.
 *
 * @param targetPlanetId планета той системы, где стоит флот
 */
public record FleetLandingRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetPlanetId
) {
}
