package com.moo3.server.galaxy;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.RaceEffectType;
import com.moo3.server.service.PopulationCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Стартовые системы у всех империй одинаковы — требование хозяина проекта.
 * <p>
 * Проверяется то, ради чего правило и заведено: не «похожи», а СОВПАДАЮТ планета в планету,
 * и у людей, и у ИИ. Разным остаётся только родной мир, и ровно настолько, насколько его
 * правит раса. Без этого всякий замер прогонами (этапы 0–2) мерит вперемешку сторону расы и
 * удачу стартовой системы.
 */
class HomeworldAllocatorTest {

    private final PopulationCalculator populationCalculator = new PopulationCalculator();
    private final PlanetGenerator planetGenerator = new PlanetGenerator(populationCalculator);
    private final HomeworldAllocator allocator =
            new HomeworldAllocator(populationCalculator, planetGenerator);

    /** Галактика из двух десятков звёзд со своими, разными системами. */
    private List<StarSystemEntity> galaxy(Random random) {
        List<StarSystemEntity> systems = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            StarSystemEntity system = new StarSystemEntity();
            system.setId(UUID.randomUUID());
            system.setName("Звезда " + i);
            system.setXParsec(random.nextDouble() * 20);
            system.setYParsec(random.nextDouble() * 14);
            system.setSpecial(Boolean.FALSE);
            planetGenerator.populate(system, random);
            systems.add(system);
        }
        return systems;
    }

    private PlayerEntity player(String raceCode) {
        PlayerEntity player = new PlayerEntity();
        player.setId(UUID.randomUUID());
        player.setRaceCode(raceCode);
        return player;
    }

    /** Приметы планеты, по которым две стартовые системы и сравниваются. */
    private String shape(PlanetEntity planet) {
        return planet.getOrbit() + " " + planet.getPlanetSize() + " " + planet.getClimate()
                + " " + planet.getMinerals() + " " + planet.getMaxPopulation();
    }

    private List<String> outerWorlds(StarSystemEntity system) {
        return system.getPlanets().stream()
                .filter(planet -> !Boolean.TRUE.equals(planet.getHomeworld()))
                .map(this::shape)
                .toList();
    }

    @Test
    @DisplayName("Стартовые системы совпадают планета в планету у всех империй")
    void everyHomeSystemIsTheSame() {
        Random random = new Random(4242);
        List<StarSystemEntity> systems = galaxy(random);
        List<PlayerEntity> players =
                List.of(player("HUMANS"), player("PSILONS"), player("MEKLAR"), player("SAKKRA"));

        allocator.allocate(systems, players, Map.of(), Map.of(), new Random(7));

        List<StarSystemEntity> homes = players.stream()
                .map(one -> systems.stream()
                        .filter(system -> system.getId().equals(one.getHomeSystemId()))
                        .findFirst()
                        .orElseThrow())
                .toList();

        assertThat(homes).hasSize(4);
        // Число планет одно на всех, и каждая орбита несёт один и тот же мир.
        assertThat(homes.stream().map(one -> one.getPlanets().size()).distinct()).hasSize(1);
        assertThat(homes.stream().map(this::outerWorlds).distinct()).hasSize(1);

        // Родной мир у всех тоже одинаков, пока раса его не правит: расы здесь без сторон.
        assertThat(homes.stream()
                .map(one -> one.getPlanets().stream()
                        .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                        .map(this::shape)
                        .toList())
                .distinct())
                .hasSize(1);

        // А прочие звёзды галактики своей одинаковости не получают: правило про СТАРТОВЫЕ.
        List<StarSystemEntity> rest = systems.stream()
                .filter(system -> players.stream()
                        .noneMatch(one -> system.getId().equals(one.getHomeSystemId())))
                .toList();
        assertThat(rest.stream().map(one -> one.getPlanets().size()).distinct().count())
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("Родной мир по умолчанию средний, а «большой мир» делает его большим")
    void homeworldStartsMedium() {
        // Правило MOO II: всякая раса начинает на СРЕДНЕМ земном мире, а сторона расы
        // «большой мир» поднимает его на ступень — до большого. Огромного мира в
        // конструкторе оригинала нет вовсе.
        //
        // Здесь по умолчанию стоял большой, и сторона выдавала огромный. Ошибка была
        // тихая — партия шла, ничего не падало, — а стоила дорого: вместимость родного
        // мира это население, население это выработка и наука, то есть два главных мерила
        // балансировки из семи. Число поэтому и закрыто проверкой.
        Random random = new Random(909);
        List<StarSystemEntity> systems = galaxy(random);
        PlayerEntity plain = player("HUMANS");
        PlayerEntity big = player("BULRATHI");

        allocator.allocate(systems, List.of(plain, big),
                Map.of("HUMANS", PlanetClimate.TERRAN, "BULRATHI", PlanetClimate.TERRAN),
                Map.of(big.getId(), new RaceEffects(com.moo3.server.domain.BuildingEffects.NONE,
                        Map.of(RaceEffectType.HOME_SIZE_STEPS, 1))),
                new Random(11));

        assertThat(homeworld(home(systems, plain)).getPlanetSize())
                .isEqualTo(com.moo3.server.domain.enums.PlanetSize.MEDIUM);
        assertThat(homeworld(home(systems, big)).getPlanetSize())
                .isEqualTo(com.moo3.server.domain.enums.PlanetSize.LARGE);
    }

    @Test
    @DisplayName("Раса правит родной мир и только его")
    void raceChangesTheHomeworldAndNothingElse() {
        Random random = new Random(909);
        List<StarSystemEntity> systems = galaxy(random);
        PlayerEntity plain = player("HUMANS");
        PlayerEntity big = player("BULRATHI");
        List<PlayerEntity> players = List.of(plain, big);

        Map<UUID, RaceEffects> effects = new LinkedHashMap<>();
        // Большой и богатый мир — ступень вверх по обеим шкалам (п. 7).
        effects.put(big.getId(), new RaceEffects(com.moo3.server.domain.BuildingEffects.NONE,
                Map.of(RaceEffectType.HOME_SIZE_STEPS, 1,
                        RaceEffectType.HOME_MINERALS_STEPS, 1)));

        allocator.allocate(systems, players, Map.of("HUMANS", PlanetClimate.TERRAN,
                "BULRATHI", PlanetClimate.TERRAN), effects, new Random(11));

        StarSystemEntity plainHome = home(systems, plain);
        StarSystemEntity bigHome = home(systems, big);

        // Всё, кроме родного мира, совпадает: раса до соседних планет не дотягивается.
        assertThat(outerWorlds(plainHome)).isEqualTo(outerWorlds(bigHome));

        PlanetEntity plainWorld = homeworld(plainHome);
        PlanetEntity bigWorld = homeworld(bigHome);
        assertThat(bigWorld.getPlanetSize().ordinal())
                .isEqualTo(plainWorld.getPlanetSize().ordinal() + 1);
        assertThat(bigWorld.getMinerals()).isNotEqualTo(plainWorld.getMinerals());
        assertThat(plainWorld.getMinerals()).isEqualTo(MineralRichness.AVERAGE);
    }

    private StarSystemEntity home(List<StarSystemEntity> systems, PlayerEntity player) {
        return systems.stream()
                .filter(system -> system.getId().equals(player.getHomeSystemId()))
                .findFirst()
                .orElseThrow();
    }

    private PlanetEntity homeworld(StarSystemEntity system) {
        return system.getPlanets().stream()
                .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("В стартовой системе всегда есть место под вторую колонию")
    void thereIsAlwaysSomewhereToSettle() {
        // Чертёж один на всех, и пустой бьёт по всем разом: партия, где ни одной империи
        // некуда расселяться, стоит на месте — на 300 ходах четыре из восьми так и остались
        // с одной колонией и без флота. Это замер застрявшей игры, а не баланса.
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            List<StarSystemEntity> systems = galaxy(random);
            PlayerEntity one = player("HUMANS");
            allocator.allocate(systems, List.of(one), Map.of(), Map.of(), new Random(seed * 7));

            StarSystemEntity home = home(systems, one);
            long free = home.getPlanets().stream()
                    .filter(planet -> !Boolean.TRUE.equals(planet.getHomeworld()))
                    .filter(planet -> planet.getClimate().getColonizable())
                    .count();
            assertThat(free)
                    .withFailMessage("зерно %d: в стартовой системе некуда селиться", seed)
                    .isGreaterThanOrEqualTo(1);
        }
    }
}
