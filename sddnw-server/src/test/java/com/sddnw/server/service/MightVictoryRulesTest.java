package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Итог по могуществу — п. 3, backlog-promo, пункт 2: на трёхсотом ходу побеждает
 * сильнейшая ЖИВАЯ империя, а равенство решает место игрока — партия обязана повторяться.
 */
class MightVictoryRulesTest {

    private static PlayerEntity player(int slot) {
        PlayerEntity player = new PlayerEntity();
        player.setId(UUID.randomUUID());
        player.setSlot(slot);
        return player;
    }

    @Test
    @DisplayName("Побеждает сильнейшая по графику «Инфо»")
    void strongestWins() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        PlayerEntity winner = MightVictoryRules.winner(
                Map.of(first.getId(), 300, second.getId(), 900), List.of(first, second),
                Set.of(first.getId(), second.getId()));
        assertThat(winner).isSameAs(second);
    }

    @Test
    @DisplayName("Империя без колоний по очкам не побеждает, даже с флотом")
    void deadEmpireCannotWin() {
        PlayerEntity first = player(1);
        PlayerEntity fleetOnly = player(2);
        PlayerEntity winner = MightVictoryRules.winner(
                Map.of(first.getId(), 100, fleetOnly.getId(), 5000), List.of(first, fleetOnly),
                Set.of(first.getId()));
        assertThat(winner).isSameAs(first);
    }

    @Test
    @DisplayName("Равную мощь решает меньшее место")
    void tieGoesToLowerSlot() {
        PlayerEntity third = player(3);
        PlayerEntity second = player(2);
        PlayerEntity winner = MightVictoryRules.winner(
                Map.of(third.getId(), 500, second.getId(), 500), List.of(third, second),
                Set.of(third.getId(), second.getId()));
        assertThat(winner).isSameAs(second);
    }

    @Test
    @DisplayName("Срок — трёхсотый ход, напоминания — за 50, 10 и 1 ход")
    void dueAndReminders() {
        assertThat(MightVictoryRules.due(299)).isFalse();
        assertThat(MightVictoryRules.due(300)).isTrue();
        assertThat(MightVictoryRules.remind(250)).isTrue();
        assertThat(MightVictoryRules.remind(290)).isTrue();
        assertThat(MightVictoryRules.remind(299)).isTrue();
        assertThat(MightVictoryRules.remind(280)).isFalse();
    }
}
