package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.SpyMission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Задание шпиону — п. 13.
 *
 * @param targetPlayerId к кому отправить; не нужен, если агента отзывают домой
 */
public record AssignSpyRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID spyId,

        @NotNull
        SpyMission mission,

        UUID targetPlayerId
) {
}
