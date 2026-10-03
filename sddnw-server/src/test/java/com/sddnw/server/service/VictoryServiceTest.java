package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.VictoryKind;
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
            new CouncilService(new CouncilRules(), null, null, null, null, null, null, null),
            // Мощь империй нужна только итогу по могуществу; у этих партий он выключен.
            null);

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

    @Test
    @DisplayName("Двадцать ходов одному у Wardenhold — победа удержанием")
    void holdingWardenholdTwentyTurnsWins() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        GameEntity game = game();
        game.setWardenholdVictory(Boolean.TRUE);
        StarSystemEntity special = wardenhold(colony(first, 3));
        PlanetEntity rivalHome = colony(second, 8);

        service.check(context(game, List.of(first, second), special, rivalHome));
        assertEquals(first.getId(), game.getWardenholdHolderPlayerId());
        assertEquals(10, game.getWardenholdSinceTurn());

        game.setTurn(28);
        service.check(context(game, List.of(first, second), special, rivalHome));
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());

        game.setTurn(29);
        service.check(context(game, List.of(first, second), special, rivalHome));
        assertEquals(GameStatus.FINISHED, game.getStatus());
        assertEquals(first.getId(), game.getWinnerPlayerId());
        assertEquals(VictoryKind.WARDENHOLD, game.getVictoryKind());
    }

    @Test
    @DisplayName("Чужая застава у Wardenhold обрывает счёт, и он начинается заново")
    void rivalOutpostResetsTheHold() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        GameEntity game = game();
        game.setWardenholdVictory(Boolean.TRUE);
        PlanetEntity mine = colony(first, 3);
        PlanetEntity rivalHome = colony(second, 8);

        service.check(context(game, List.of(first, second), wardenhold(mine), rivalHome));
        game.setTurn(20);
        PlanetEntity outpost = colony(second, 0);
        service.check(context(game, List.of(first, second), wardenhold(mine, outpost), rivalHome));
        assertNull(game.getWardenholdHolderPlayerId());

        game.setTurn(29);
        service.check(context(game, List.of(first, second), wardenhold(mine), rivalHome));
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertEquals(29, game.getWardenholdSinceTurn());
    }

    @Test
    @DisplayName("Без признака партии удержание Wardenhold не побеждает")
    void wardenholdIsOffByDefault() {
        PlayerEntity first = player(1);
        PlayerEntity second = player(2);
        GameEntity game = game();
        game.setTurn(500);

        service.check(context(game, List.of(first, second), wardenhold(colony(first, 3)), colony(second, 8)));

        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertNull(game.getWardenholdHolderPlayerId());
    }

    /** Особая звезда с планетами; колонии с жителями попадают и в общий список хода. */
    private StarSystemEntity wardenhold(PlanetEntity... planets) {
        StarSystemEntity system = new StarSystemEntity();
        system.setId(UUID.randomUUID());
        system.setName("Wardenhold");
        system.setSpecial(Boolean.TRUE);
        for (PlanetEntity planet : planets) {
            system.addPlanet(planet);
        }
        return system;
    }

    /** Ход с особой звездой и родными мирами соперников в обычной системе. */
    private TurnContext context(GameEntity game, List<PlayerEntity> players,
                                StarSystemEntity special, PlanetEntity... homes) {
        StarSystemEntity other = new StarSystemEntity();
        other.setId(UUID.randomUUID());
        for (PlanetEntity home : homes) {
            other.addPlanet(home);
        }
        List<PlanetEntity> colonies = new java.util.ArrayList<>(List.of(homes));
        special.getPlanets().stream().filter(planet -> planet.getPopulation() > 0).forEach(colonies::add);
        return new TurnContext(game, players, List.of(special, other), colonies, null,
                new TurnReport(game.getTurn()));
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
