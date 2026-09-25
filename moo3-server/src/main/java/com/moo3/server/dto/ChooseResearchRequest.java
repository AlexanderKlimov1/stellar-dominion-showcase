package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Выбор технологии для исследования — п. 9.
 * <p>
 * Уровень указывается вместе с разделом: технология внутри уровня одна, но по одному
 * её коду нельзя проверить, что игрок не перепрыгнул через неизученные уровни.
 */
public record ChooseResearchRequest(
        @NotBlank
        String accessToken,

        @NotBlank
        String categoryCode,

        @NotNull
        Integer levelOrder,

        @NotBlank
        String optionCode
) {
}
