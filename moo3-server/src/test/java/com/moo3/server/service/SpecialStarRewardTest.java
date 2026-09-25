package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.dto.AcquiredTechnologyDto;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Клад особой звезды — п. 4.2.1.
 * <p>
 * Правило проверяется без партии: дойти до Wardenhold по-честному значит разбить Стража
 * флотом поздней игры, и сквозной прогон этого не сделает. А проверять здесь есть что —
 * правило состоит из трёх условий, и каждое из них однажды ломалось в похожих местах
 * проекта: выдать РОВНО три, выдать ПЕРВОМУ поселенцу и выдать ОДИН раз.
 */
class SpecialStarRewardTest {

    private final ResearchService researchService = Mockito.mock(ResearchService.class);
    private final SpecialStarReward reward = new SpecialStarReward(researchService);

    private final GameEntity game = new GameEntity();
    private final PlayerEntity player = new PlayerEntity();
    private final StarSystemEntity special = new StarSystemEntity();
    private final PlanetEntity planet = new PlanetEntity();

    SpecialStarRewardTest() {
        game.setId(UUID.randomUUID());
        game.setSeed(4242L);
        game.setTurn(12);
        player.setId(UUID.randomUUID());
        player.setName("Ловчий");
        planet.setId(UUID.randomUUID());
        special.setId(UUID.randomUUID());
        special.setName("Wardenhold");
        special.setSpecial(Boolean.TRUE);
        special.setSpecialClaimed(Boolean.FALSE);
        special.setPlanets(new ArrayList<>(List.of(planet)));
        when(researchService.grantGift(any(), anyInt(), any(RandomGenerator.class), any()))
                .thenReturn(List.of(new AcquiredTechnologyDto("power", 2, "colony-ship",
                        "Colony Ship", 12)));
    }

    private TurnContext context() {
        return new TurnContext(game, List.of(player), List.of(special), List.of(), null,
                new TurnReport(game.getTurn()));
    }

    @Test
    @DisplayName("Заселённая особая звезда даёт три технологии и строку в отчёт")
    void settledStarPaysThree() {
        planet.setOwnerPlayerId(player.getId());

        TurnContext context = context();
        reward.apply(context);

        verify(researchService, times(3))
                .grantGift(any(), anyInt(), any(RandomGenerator.class), any());
        assertThat(special.getSpecialClaimed()).isTrue();
        assertThat(context.report().forPlayer(player.getId()).events())
                .extracting("code").containsExactly("SPECIAL_STAR");
    }

    @Test
    @DisplayName("Незаселённая звезда клада не отдаёт: его берут боем, а не разведкой")
    void unsettledStarPaysNothing() {
        reward.apply(context());

        verify(researchService, Mockito.never())
                .grantGift(any(), anyInt(), any(RandomGenerator.class), any());
        assertThat(special.getSpecialClaimed()).isFalse();
    }

    @Test
    @DisplayName("Клад достаётся ОДИН раз: отбитая и заселённая заново колония его не повторяет")
    void claimedStarPaysOnce() {
        planet.setOwnerPlayerId(player.getId());
        reward.apply(context());
        Mockito.clearInvocations(researchService);

        // Следующий ход: хозяин тот же, признак стоит — выдавать больше нечего.
        TurnContext next = context();
        reward.apply(next);

        verify(researchService, Mockito.never())
                .grantGift(any(), anyInt(), any(RandomGenerator.class), any());
        assertThat(next.report().forPlayer(player.getId()).events()).isEmpty();
    }

    @Test
    @DisplayName("Империя, изучившая дерево, клад всё равно забирает: звезда не остаётся неразграбленной")
    void emptyTreeStillClaims() {
        planet.setOwnerPlayerId(player.getId());
        when(researchService.grantGift(any(), anyInt(), any(RandomGenerator.class), any()))
                .thenReturn(List.of());

        TurnContext context = context();
        reward.apply(context);

        assertThat(special.getSpecialClaimed()).isTrue();
        // Строки в отчёте нет: сообщать не о чем, а событие, которого игрок не увидел, для
        // игры не случилось.
        assertThat(context.report().forPlayer(player.getId()).events()).isEmpty();
    }
}
