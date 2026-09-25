package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.PlanetFind;
import com.moo3.server.dto.AcquiredTechnologyDto;
import com.moo3.server.repository.PlanetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Награда за разведку системы с находкой — п. 4.1.
 * <p>
 * Проверяется то же, что у клада особой звезды, и по той же причине: доехать до артефактов
 * в живой партии — дело случая, а правило состоит из условий, каждое из которых в этом
 * проекте уже однажды ломалось — платить ТОЛЬКО за артефакты, платить ПЕРВОМУ и платить
 * ОДИН раз.
 */
class PlanetFindRewardTest {

    private final ResearchService researchService = Mockito.mock(ResearchService.class);
    private final PlanetRepository planetRepository = Mockito.mock(PlanetRepository.class);
    private final PlayerEventService playerEvents = Mockito.mock(PlayerEventService.class);
    private final PlanetFindReward reward =
            new PlanetFindReward(researchService, planetRepository, playerEvents);

    private final GameEntity game = new GameEntity();
    private final PlayerEntity player = new PlayerEntity();
    private final PlayerEntity second = new PlayerEntity();
    private final StarSystemEntity system = new StarSystemEntity();
    private final PlanetEntity planet = new PlanetEntity();

    PlanetFindRewardTest() {
        game.setId(UUID.randomUUID());
        game.setSeed(4242L);
        game.setTurn(17);
        player.setId(UUID.randomUUID());
        player.setName("Первый");
        second.setId(UUID.randomUUID());
        second.setName("Опоздавший");
        planet.setId(UUID.randomUUID());
        planet.setOrbit(3);
        planet.setName("Gliese 581 III");
        system.setId(UUID.randomUUID());
        system.setName("Gliese 581");
        system.setPlanets(new ArrayList<>(List.of(planet)));
        when(researchService.grantGift(any(), anyInt(), any(RandomGenerator.class), any()))
                .thenReturn(List.of(new AcquiredTechnologyDto("power", 2, "colony-ship",
                        "Colony Ship", 17)));
    }

    @Test
    @DisplayName("Артефакты платят тому, кто разведал систему, и отмечаются взятыми")
    void artifactsPayExplorer() {
        planet.setFind(PlanetFind.ARTIFACTS);

        reward.claim(game, player, system);

        verify(researchService, atLeastOnce())
                .grantGift(eq(player), anyInt(), any(RandomGenerator.class), any());
        verify(playerEvents).record(eq(game.getId()), eq(player.getId()), eq(game.getTurn()),
                eq("EXPLORATION"), any(MessageKey.class), eq(system.getId()), any());
        assertThat(planet.getFindClaimed()).isTrue();
    }

    @Test
    @DisplayName("Золото за разведку не платит: жила ждёт колонию, а не разведчика")
    void goldPaysNothingForLooking() {
        planet.setFind(PlanetFind.GOLD_DEPOSITS);

        reward.claim(game, player, system);

        verify(researchService, Mockito.never())
                .grantGift(any(), anyInt(), any(RandomGenerator.class), any());
        assertThat(planet.getFindClaimed()).isFalse();
    }

    @Test
    @DisplayName("Второй пришедший застаёт артефакты разобранными")
    void secondExplorerGetsNothing() {
        planet.setFind(PlanetFind.ARTIFACTS);
        reward.claim(game, player, system);
        Mockito.clearInvocations(researchService, playerEvents);

        reward.claim(game, second, system);

        verify(researchService, Mockito.never())
                .grantGift(any(), anyInt(), any(RandomGenerator.class), any());
        Mockito.verifyNoInteractions(playerEvents);
    }

    @Test
    @DisplayName("Империя с изученным деревом артефакты всё равно разбирает, но молча")
    void emptyTreeStillClaims() {
        planet.setFind(PlanetFind.ARTIFACTS);
        when(researchService.grantGift(any(), anyInt(), any(RandomGenerator.class), any()))
                .thenReturn(List.of());

        reward.claim(game, player, system);

        assertThat(planet.getFindClaimed()).isTrue();
        // Событие, которого игрок не увидел, для игры не случилось: сообщать не о чем.
        Mockito.verifyNoInteractions(playerEvents);
    }

    @Test
    @DisplayName("Обыкновенная планета награды не даёт и в базу не пишется")
    void plainPlanetIsUntouched() {
        reward.claim(game, player, system);

        Mockito.verifyNoInteractions(researchService, planetRepository, playerEvents);
    }
}
