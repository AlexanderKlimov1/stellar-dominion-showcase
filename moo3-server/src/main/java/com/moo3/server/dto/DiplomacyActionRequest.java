package com.moo3.server.dto;

import com.moo3.server.domain.enums.DiplomacyAction;
import com.moo3.server.domain.enums.DiplomacyTreaty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Дипломатическое действие — п. 15.
 *
 * @param treaty        какой договор предлагают или разрывают; нужен действиям
 *                      PROPOSE_TREATY и BREAK_TREATY, остальным — нет
 * @param offeredTech   что отдают: технология обмена (EXCHANGE_TECH) или подарка (GIFT_TECH)
 * @param requestedTech что просят взамен; нужен только обмену (EXCHANGE_TECH)
 * @param credits       сколько кредитов дарят; нужен только подарку деньгами (GIFT_CREDITS)
 */
public record DiplomacyActionRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetPlayerId,

        @NotNull
        DiplomacyAction action,

        DiplomacyTreaty treaty,

        String offeredTech,

        String requestedTech,

        @Positive
        Integer credits
) {
}
