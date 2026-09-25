package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Язык игрока — п. 3.5: {@code en} или {@code ru}.
 * <p>
 * Что язык один из двух, проверяет служба, а не аннотация: список языков игры живёт в одном
 * месте ({@code AccountService.SUPPORTED_LOCALES}), и перечислять его вторым списком здесь
 * значило бы завести две правды об одном.
 */
public record LocaleRequest(
        @NotBlank String locale
) {
}
