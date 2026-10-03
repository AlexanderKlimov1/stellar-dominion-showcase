package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Приказ «разведывать самим» — backlog-promo, пункт 24: включить или снять.
 *
 * @param accessToken пропуск игрока
 * @param on          включить ({@code true}) или снять ({@code false})
 */
public record FleetExploreRequest(
        @NotBlank String accessToken,
        @NotNull Boolean on
) {
}
