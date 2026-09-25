package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Перераспределение жителей колонии — п. 4.1.
 * <p>
 * Присылаются все три занятия сразу, а не сдвиг одного: сумма должна сойтись с населением
 * планеты, и проверить это можно только по полному набору.
 */
public record SetPopulationRequest(
        @NotBlank
        String accessToken,

        @NotNull @PositiveOrZero
        Integer farmers,

        @NotNull @PositiveOrZero
        Integer workers,

        @NotNull @PositiveOrZero
        Integer scientists
) {
}
