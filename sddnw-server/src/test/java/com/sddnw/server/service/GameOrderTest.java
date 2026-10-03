package com.sddnw.server.service;

import com.sddnw.server.domain.entity.FleetEncounterEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.FleetShipEntity;
import com.sddnw.server.domain.entity.PopulationTransferEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Порядок, в котором ход читает свои строки, — этап 0 балансировки.
 * <p>
 * <b>Зачем это проверять.</b> Порядок здесь не удобство чтения, а часть правил игры: первому
 * пришедшему флоту достаётся сторожевое чудище, первому рейсу — место на планете, слабейшим
 * кораблём платят за победу. Ошибка в ключе не роняет ни сборку, ни партию — она делает
 * партию НЕПОВТОРИМОЙ, и замечают это через сотню ходов по разошедшейся летописи. Проверка
 * же ловит её за миллисекунды.
 * <p>
 * Ключи нарочно подобраны так, что игровой порядок строк ПРОТИВОПОЛОЖЕН порядку их
 * идентификаторов: звезда «Achird» получила UUID, начинающийся на {@code ffff}, а «Zavijava»
 * — на {@code 0000}. Сортировка, соскользнувшая на идентификатор, тут же выдаёт обратный
 * ответ.
 */
class GameOrderTest {

    private static final UUID EARLY_STAR = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
    private static final UUID LATE_STAR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_PLAYER = UUID.fromString("ffffffff-0000-0000-0000-000000000003");
    private static final UUID SECOND_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000004");

    /** Звёзды: имена и идентификаторы идут в обратном порядке — см. пояснение к классу. */
    private Map<UUID, String> stars() {
        Map<UUID, String> names = new HashMap<>();
        names.put(EARLY_STAR, "Achird");
        names.put(LATE_STAR, "Zavijava");
        return names;
    }

    /** Места игроков — так же наперекор порядку их идентификаторов. */
    private Map<UUID, Integer> slots() {
        Map<UUID, Integer> slots = new HashMap<>();
        slots.put(FIRST_PLAYER, 1);
        slots.put(SECOND_PLAYER, 2);
        return slots;
    }

    private FleetEntity fleet(UUID owner, UUID from, UUID to, Integer arrival, Integer ships) {
        FleetEntity fleet = new FleetEntity();
        fleet.setId(UUID.randomUUID());
        fleet.setOwnerPlayerId(owner);
        fleet.setStarSystemId(from);
        fleet.setTargetSystemId(to);
        fleet.setArrivalTurn(arrival);
        fleet.setShips(ships);
        return fleet;
    }

    @Test
    @DisplayName("Флоты: ход прибытия, место хозяина, звезда — и никакого идентификатора")
    void fleetsGoByGameKeys() {
        FleetEntity soon = fleet(SECOND_PLAYER, LATE_STAR, EARLY_STAR, 10, 1);
        FleetEntity later = fleet(FIRST_PLAYER, EARLY_STAR, LATE_STAR, 12, 1);
        FleetEntity alsoSoon = fleet(FIRST_PLAYER, EARLY_STAR, LATE_STAR, 10, 1);

        List<FleetEntity> rows = new ArrayList<>(List.of(later, soon, alsoSoon));
        rows.sort(GameOrder.fleets(stars(), slots()));

        assertEquals(List.of(alsoSoon, soon, later), rows,
                "сперва прибывающие раньше, среди них — империя с меньшим местом");
    }

    @Test
    @DisplayName("Флоты: перевыдача идентификаторов порядка не меняет")
    void fleetsIgnoreRowIds() {
        List<FleetEntity> rows = new ArrayList<>(List.of(
                fleet(FIRST_PLAYER, EARLY_STAR, LATE_STAR, 10, 1),
                fleet(SECOND_PLAYER, EARLY_STAR, LATE_STAR, 10, 1),
                fleet(FIRST_PLAYER, LATE_STAR, EARLY_STAR, 10, 1)));
        List<String> expected = marks(rows);

        // Те же строки с другими идентификаторами и в другом порядке: так выглядит второй
        // прогон той же партии — идентификаторы там свои, а решения обязаны совпасть.
        for (FleetEntity row : rows) {
            row.setId(UUID.randomUUID());
        }
        Collections.reverse(rows);

        assertEquals(expected, marks(rows), "второй прогон читает флоты в том же порядке");
    }

    /** Флоты по порядку, названные тем, что выводится из зерна партии. */
    private List<String> marks(List<FleetEntity> rows) {
        List<FleetEntity> sorted = new ArrayList<>(rows);
        sorted.sort(GameOrder.fleets(stars(), slots()));
        return sorted.stream()
                .map(fleet -> slots().get(fleet.getOwnerPlayerId()) + "@"
                        + stars().get(fleet.getStarSystemId()))
                .toList();
    }

    @Test
    @DisplayName("Состав флота: два проекта одного корпуса различает ход их создания")
    void fleetShipsGoByDesignAge() {
        UUID oldDesignId = UUID.fromString("ffffffff-0000-0000-0000-00000000000a");
        UUID newDesignId = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Map<UUID, ShipDesignEntity> designs = new HashMap<>();
        designs.put(oldDesignId, design(oldDesignId, 4, 1, "Battleship"));
        designs.put(newDesignId, design(newDesignId, 4, 49, "Battleship"));

        FleetShipEntity newer = row(newDesignId);
        FleetShipEntity older = row(oldDesignId);
        List<FleetShipEntity> rows = new ArrayList<>(List.of(newer, older));
        rows.sort(GameOrder.fleetShips(designs));

        // Ячейка и название у этих проектов одни и те же — различает только ход создания,
        // и именно он решал, какой из двух линкоров съест чудище (журнал, 25.09.2026).
        assertEquals(List.of(older, newer), rows, "старший проект идёт раньше нового");
    }

    private ShipDesignEntity design(UUID id, Integer slot, Integer createdTurn, String name) {
        ShipDesignEntity design = new ShipDesignEntity();
        design.setId(id);
        design.setSlot(slot);
        design.setCreatedTurn(createdTurn);
        design.setName(name);
        return design;
    }

    private FleetShipEntity row(UUID designId) {
        FleetShipEntity row = new FleetShipEntity();
        row.setId(UUID.randomUUID());
        row.setDesignId(designId);
        row.setShips(1);
        return row;
    }

    @Test
    @DisplayName("Встречи одного хода различают звезда и места сторон")
    void encountersGoByStarAndSlots() {
        FleetEncounterEntity atZavijava = encounter(LATE_STAR, FIRST_PLAYER, SECOND_PLAYER, 20);
        FleetEncounterEntity atAchird = encounter(EARLY_STAR, SECOND_PLAYER, FIRST_PLAYER, 20);
        FleetEncounterEntity earlier = encounter(LATE_STAR, FIRST_PLAYER, SECOND_PLAYER, 19);

        List<FleetEncounterEntity> rows = new ArrayList<>(List.of(atZavijava, atAchird, earlier));
        rows.sort(GameOrder.encounters(stars(), slots()));

        assertEquals(List.of(earlier, atAchird, atZavijava), rows,
                "сперва встреча прошлого хода, затем по названию звезды");
    }

    private FleetEncounterEntity encounter(UUID star, UUID first, UUID second, Integer turn) {
        FleetEncounterEntity encounter = new FleetEncounterEntity();
        encounter.setId(UUID.randomUUID());
        encounter.setStarSystemId(star);
        encounter.setFirstPlayerId(first);
        encounter.setSecondPlayerId(second);
        encounter.setTurn(turn);
        return encounter;
    }

    @Test
    @DisplayName("Рейсы с жителями: ход отправки, место хозяина, планета назначения")
    void transfersGoByGameKeys() {
        UUID home = UUID.fromString("ffffffff-0000-0000-0000-00000000000c");
        UUID far = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        Map<UUID, String> planets = new HashMap<>();
        planets.put(home, "Achird I");
        planets.put(far, "Zavijava II");

        PopulationTransferEntity toFar = transfer(FIRST_PLAYER, home, far, 5, 3);
        PopulationTransferEntity toHome = transfer(FIRST_PLAYER, far, home, 5, 3);
        PopulationTransferEntity earlier = transfer(SECOND_PLAYER, far, home, 4, 3);

        List<PopulationTransferEntity> rows = new ArrayList<>(List.of(toFar, toHome, earlier));
        rows.sort(GameOrder.transfers(planets, slots()));

        assertEquals(List.of(earlier, toHome, toFar), rows,
                "сперва отправленный раньше, затем по названию планеты назначения");
    }

    private PopulationTransferEntity transfer(UUID owner, UUID from, UUID to,
                                              Integer departed, Integer people) {
        PopulationTransferEntity transfer = new PopulationTransferEntity();
        transfer.setId(UUID.randomUUID());
        transfer.setOwnerPlayerId(owner);
        transfer.setFromPlanetId(from);
        transfer.setToPlanetId(to);
        transfer.setDepartedTurn(departed);
        transfer.setPopulation(people);
        return transfer;
    }

    @Test
    @DisplayName("Зерно боя: от партии, хода и мест сторон — и одно на оба боя")
    void battleSeedComesFromTheGame() {
        Long first = BattleRules.battleSeed(777L, 40, 1, 2);

        assertEquals(first, BattleRules.battleSeed(777L, 40, 1, 2),
                "тот же бой той же партии — то же зерно");
        assertEquals(3, List.of(BattleRules.battleSeed(777L, 41, 1, 2),
                BattleRules.battleSeed(778L, 40, 1, 2),
                BattleRules.battleSeed(777L, 40, 2, 1)).stream().distinct().count(),
                "другой ход, другая партия и другие стороны дают свои зёрна");
    }
}
