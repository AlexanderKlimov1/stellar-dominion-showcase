package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.BalanceRunEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Какой балансовый прогон закрывать как брошенный — трек техдолга, пункт 15.
 * <p>
 * Прежде при старте закрывался каждый «идущий» прогон, и сервер, поднятый рядом, обрывал
 * живой оракул соседа. Правило теперь такое: свой прогон не брошен никогда, чужой — только
 * когда его пульс замолчал.
 */
class BalanceRunAbandonedTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-27T12:00:00+03:00");

    private static BalanceRunEntity run(String owner, OffsetDateTime heartbeat) {
        BalanceRunEntity run = new BalanceRunEntity();
        run.setOwnerInstance(owner);
        run.setHeartbeatAt(heartbeat);
        return run;
    }

    @Test
    @DisplayName("Прогон соседа с живым пульсом не трогается — это и была поломка")
    void livingNeighbourIsLeftAlone() {
        assertFalse(BalanceRunProgress.abandoned(run("сосед", NOW.minusSeconds(40)), "я", NOW));
    }

    @Test
    @DisplayName("Прогон соседа, чей пульс замолчал, закрывается: вести его больше некому")
    void silentNeighbourIsClosed() {
        assertTrue(BalanceRunProgress.abandoned(
                run("сосед", NOW.minus(BalanceRunProgress.STALE).minusSeconds(1)), "я", NOW));
    }

    @Test
    @DisplayName("Свой прогон не брошен, даже если пульс не успел отметиться")
    void ownRunIsNeverAbandoned() {
        assertFalse(BalanceRunProgress.abandoned(run("я", null), "я", NOW));
        assertFalse(BalanceRunProgress.abandoned(run("я", NOW.minusHours(3)), "я", NOW));
    }

    @Test
    @DisplayName("После сна машины первый обход прогон соседа не закрывает: хозяин успеет отметить пульс")
    void wakeUpGivesTheOwnerOneSweep() {
        // Трек техдолга, пункт 20: машина спала час, пульс «молчит» час у всех.
        Map<UUID, OffsetDateTime> suspects = new HashMap<>();
        OffsetDateTime beforeSleep = NOW.minusHours(1);
        BalanceRunEntity neighbour = run("сосед", beforeSleep);
        neighbour.setId(UUID.randomUUID());

        assertFalse(BalanceRunProgress.closable(neighbour, "я", NOW, suspects), "первый обход — только подозрение");
        // Хозяин проснулся и отметился между обходами.
        neighbour.setHeartbeatAt(NOW.plusSeconds(30));
        assertFalse(BalanceRunProgress.closable(neighbour, "я", NOW.plusSeconds(60), suspects));
        assertTrue(suspects.isEmpty(), "ожившего хозяина больше не подозревают");
    }

    @Test
    @DisplayName("Прогон, чей пульс молчит два обхода подряд, закрывается вторым")
    void silentTwiceIsClosed() {
        Map<UUID, OffsetDateTime> suspects = new HashMap<>();
        BalanceRunEntity neighbour = run("сосед", NOW.minusHours(1));
        neighbour.setId(UUID.randomUUID());

        assertFalse(BalanceRunProgress.closable(neighbour, "я", NOW, suspects));
        assertTrue(BalanceRunProgress.closable(neighbour, "я", NOW.plusSeconds(60), suspects));
    }

    @Test
    @DisplayName("Прогон без пульса заведён до миграции 073 — вести его некому")
    void runWithoutHeartbeatIsClosed() {
        assertTrue(BalanceRunProgress.abandoned(run(null, null), "я", NOW));
    }
}
