package com.moo3.server.dto;

import com.moo3.server.domain.enums.EncounterDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Решение игрока при встрече флотов — п. 8.
 *
 * @param decision атаковать или разойтись
 * @param auto     считать бой автоматически; {@code null} — считать: «авто» включено
 *                 по умолчанию, а ручной бой пока заглушка
 */
public record EncounterDecisionRequest(
        @NotBlank
        String accessToken,

        @NotNull
        EncounterDecision decision,

        Boolean auto
) {
    /** «Авто» по умолчанию включено — ручного боя ещё нет. */
    public Boolean autoOrDefault() {
        return auto == null || Boolean.TRUE.equals(auto);
    }
}
