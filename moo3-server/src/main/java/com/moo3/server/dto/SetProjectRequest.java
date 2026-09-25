package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Что колония будет строить — п. 10.
 *
 * @param projectCode код здания из справочника либо особый проект: {@code HOUSING} или
 *                    {@code TRADE_GOODS}
 * @param top         поставить в голову очереди, сдвинув её вниз, а не в хвост; пусто —
 *                    в хвост, как в окне стройки самой колонии
 */
public record SetProjectRequest(
        @NotBlank
        String accessToken,

        @NotBlank
        String projectCode,

        Boolean top
) {
}
