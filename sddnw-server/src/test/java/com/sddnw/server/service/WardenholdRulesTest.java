package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.enums.GalaxySize;
import com.sddnw.server.dto.CreateGameRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Победа удержанием Wardenhold и гостевая партия — п. 3, backlog-promo, пункт 1.
 * <p>
 * Правило держателя проверяется на том, что делает его правилом, а не формальностью:
 * застава звезду не держит, а чужая планета рядом обрывает счёт.
 */
class WardenholdRulesTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID RIVAL = UUID.randomUUID();

    private static PlanetEntity planet(UUID owner, Integer population) {
        PlanetEntity planet = new PlanetEntity();
        planet.setOwnerPlayerId(owner);
        planet.setPopulation(population);
        return planet;
    }

    @Test
    @DisplayName("Колония у звезды и никого рядом — звезда держится")
    void soleColonyHolds() {
        assertThat(WardenholdRules.holder(List.of(planet(ME, 3), planet(null, 0), planet(ME, 0))))
                .isEqualTo(ME);
    }

    @Test
    @DisplayName("Одни заставы звезду не держат")
    void outpostsDoNotHold() {
        assertThat(WardenholdRules.holder(List.of(planet(ME, 0), planet(null, 0)))).isNull();
    }

    @Test
    @DisplayName("Чужая планета у звезды — даже застава — обрывает удержание")
    void rivalPresenceBreaksHold() {
        assertThat(WardenholdRules.holder(List.of(planet(ME, 5), planet(RIVAL, 0)))).isNull();
    }

    @Test
    @DisplayName("Двадцатый ход удержания — победа, девятнадцатый — ещё нет")
    void twentyTurnsWin() {
        assertThat(WardenholdRules.turnsLeft(10, 28)).isEqualTo(1);
        assertThat(WardenholdRules.turnsLeft(10, 29)).isZero();
        assertThat(WardenholdRules.heldTurns(10, 10)).isEqualTo(1);
    }

    @Test
    @DisplayName("Гостю назначаются малая галактика, четыре империи, совет и Wardenhold")
    void guestGameIsRestricted() {
        CreateGameRequest asked = new CreateGameRequest("Партия", GalaxySize.HUGE, "Игрок", null,
                null, null, null, 42L, Boolean.FALSE, Boolean.TRUE, 8, Boolean.FALSE,
                Boolean.FALSE, null, Boolean.FALSE, Boolean.FALSE, null);

        CreateGameRequest given = GuestGameRules.restrict(asked);

        assertThat(given.galaxySize()).isEqualTo(GalaxySize.SMALL);
        assertThat(given.totalPlayers()).isEqualTo(4);
        assertThat(given.observerOrDefault()).isFalse();
        assertThat(given.councilOrDefault()).isTrue();
        assertThat(given.aiActiveOrDefault()).isTrue();
        assertThat(given.wardenholdVictoryOrDefault()).isTrue();
        assertThat(given.mightVictoryOrDefault()).isTrue();
        assertThat(given.playerName()).isEqualTo("Игрок");
    }
}
