package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Часы хода и уход из-за стола — backlog-promo, пункт 11.
 */
class TurnClockRulesTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-02T12:00:00Z");

    @Test
    @DisplayName("Годятся только сроки окна новой игры — или без срока")
    void allowedSeconds() {
        assertTrue(TurnClockRules.allowed(null));
        assertTrue(TurnClockRules.allowed(90));
        assertTrue(TurnClockRules.allowed(600));
        assertFalse(TurnClockRules.allowed(1), "срок в секунду значил бы партию, которую не сыграть");
    }

    @Test
    @DisplayName("Срок истекает ровно через назначенное число секунд от начала хода")
    void expiresOnTime() {
        assertFalse(TurnClockRules.expired(START, 90, START.plusSeconds(89)));
        assertTrue(TurnClockRules.expired(START, 90, START.plusSeconds(90)));
        assertFalse(TurnClockRules.expired(START, null, START.plusSeconds(100_000)), "без срока не истекает");
        assertNull(TurnClockRules.deadline(START, null));
    }

    @Test
    @DisplayName("Три минуты без действия — ушёл")
    void idleAfterThreeMinutes() {
        assertFalse(TurnClockRules.idle(START, START.plusSeconds(179)));
        assertTrue(TurnClockRules.idle(START, START.plusSeconds(180)));
    }

    @Test
    @DisplayName("Ушедшего человека ведёт ИИ, вернувшегося — снова он сам; три пропуска подряд считаются")
    void presenceDrivesAi() {
        PresenceService presence = new PresenceService();
        PlayerEntity human = new PlayerEntity();
        human.setId(UUID.randomUUID());
        human.setPlayerType(PlayerType.HUMAN);

        assertFalse(presence.aiDriven(human));
        presence.leave(human.getId());
        assertTrue(presence.aiDriven(human), "ушедшего ведёт ИИ");
        presence.act(human.getId());
        assertFalse(presence.aiDriven(human), "любое действие возвращает место");

        assertEquals(1, presence.miss(human.getId()));
        assertEquals(2, presence.miss(human.getId()));
        presence.act(human.getId());
        assertEquals(1, presence.miss(human.getId()), "действие обнуляет счёт пропусков");
    }
}
