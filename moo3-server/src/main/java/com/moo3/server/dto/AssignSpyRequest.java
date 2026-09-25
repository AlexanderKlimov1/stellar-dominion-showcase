package com.moo3.server.dto;

import com.moo3.server.domain.enums.SpyMission;
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
