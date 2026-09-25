package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Какую постройку колония продаёт — п. 10.
 *
 * @param buildingCode код здания из справочника; оно должно стоять на этой планете
 */
public record SellBuildingRequest(
        @NotBlank
        String accessToken,

        @NotBlank
        String buildingCode
) {
}
