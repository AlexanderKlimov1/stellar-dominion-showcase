package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Заложить колонии шаблон стройки — п. 10.
 *
 * @param templateId шаблон игрока; он обязан быть своим — чужим закладывать нечего
 */
public record EnqueueTemplateRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID templateId
) {
}
