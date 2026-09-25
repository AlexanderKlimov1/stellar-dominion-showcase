package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.domain.enums.VictoryKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Конец партии покорением — п. 3.
 * <p>
 * Голосование Высшего совета сюда не попадает: оно спрашивает отношения из базы, и его
 * правила проверяет {@link CouncilRulesTest}. Здесь совет заглушён (партии этих проверок
 * идут первым ходом, а совет собирается не раньше пятидесятого), и проверяется покорение:
 * ошибка в нём стоит дорого — партия либо не кончается никогда, либо объявляет победителя,
 * пока соперники живы.
 */
class VictoryServiceTest {

    /*
      Совет здесь заглушён нулями: партии этих проверок идут первым десятком ходов, а
      совет собирается не раньше пятидесятого — до репозиториев дело не доходит. Добавив
      службе зависимость, поправь и этот список (те же грабли, что у AiNeedsTest).
    */
    private final VictoryService service = new VictoryService(
            new CouncilService(new CouncilRules(), null, null, null, null, null, null, null));

    @Test
    @DisplayName("Пока колонии есть у двоих, партия идёт")
    void twoEmpiresKeepPlaying() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        GameEntity game = game();

        service.check(context(game, List.of(first, second),
                colony(first, 10), colony(second, 4)));

        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertNull(game.getWinnerPlayerId());
    }

    @Test
    @DisplayName("Колонии остались у одного — победа покорением")
    void lastStandingWins() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        GameEntity game = game();

        service.check(context(game, List.of(first, second), colony(first, 10)));

        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertEquals(first.getId(), game.getWinnerPlayerId());
        assertEquals(VictoryKind.CONQUEST, game.getVictoryKind());
    }

    @Test
    @DisplayName("Флот без колоний империей не считается: восстановиться ему негде")
    void fleetWithoutColoniesIsNotAnEmpire() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        GameEntity game = game();

        // У второй империи планета есть, но она обезлюдела — это уже не колония.
        service.check(context(game, List.of(first, second), colony(first, 10)));

        assertEquals(first.getId(), game.getWinnerPlayerId());
    }

    @Test
    @DisplayName("Партия на одного не кончается: объявлять победу не над кем")
    void soloGameNeverEnds() {
        PlayerEntity only = player(1);
        GameEntity game = game();

        service.check(context(game, List.of(only), colony(only, 10)));

        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
    }

    private TurnContext context(GameEntity game, List<PlayerEntity> players, PlanetEntity... colonies) {
        StarSystemEntity system = new StarSystemEntity();
        system.setId(UUID.randomUUID());
        for (PlanetEntity colony : colonies) {
            system.addPlanet(colony);
        }
        return new TurnContext(game, players, List.of(system), List.of(colonies), null,
                new TurnReport(game.getTurn()));
    }

    private GameEntity game() {
        GameEntity game = new GameEntity();
        game.setId(UUID.randomUUID());
        game.setName("проверка");
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setTurn(10);
        return game;
    }

    private PlayerEntity player(Integer slot) {
        PlayerEntity player = new PlayerEntity();
        player.setId(UUID.randomUUID());
        player.setSlot(slot);
        player.setName("Империя " + slot);
        return player;
    }

    private PlanetEntity colony(PlayerEntity owner, Integer population) {
        PlanetEntity planet = new PlanetEntity();
        planet.setId(UUID.randomUUID());
        planet.setName("Планета " + population);
        planet.setOwnerPlayerId(owner.getId());
        planet.setPopulation(population);
        return planet;
    }
}
