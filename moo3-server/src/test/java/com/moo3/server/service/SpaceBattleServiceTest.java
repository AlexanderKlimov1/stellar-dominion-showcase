package com.moo3.server.service;

import com.moo3.server.service.stub.SpaceBattleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила боя-заглушки — п. 8.
 * <p>
 * Настоящей тактической сцены ещё нет, но арифметика исхода уже влияет на партию: флоты
 * гибнут, потери попадают в итоги хода. Поэтому она и проверена — опечатка здесь стоила бы
 * игроку флота.
 */
class SpaceBattleServiceTest {

    /**
     * Зерно боя у проверок постоянное: им важна арифметика исхода, а не жребий взрывов
     * (п. 8). Само зерно в игре выводится из зерна партии — иначе повторный прогон той же
     * партии расходился бы сам с собой.
     */
    private static final Long SEED = 42L;

    private final SpaceBattleService battles = new SpaceBattleService(new BattleRules());

    @Test
    @DisplayName("Сильнейший побеждает, слабый флот гибнет целиком")
    void strongerWins() {
        SpaceBattleService.BattleOutcome outcome =
                battles.resolve("Нападающий", 10, 10, "Обороняющийся", 4, 4, SEED);

        assertEquals(4, outcome.defenderLosses(), "слабый флот гибнет весь");
        assertTrue(outcome.attackerLosses() < 10, "победитель не гибнет целиком");
        assertEquals(Boolean.TRUE, outcome.attackerWins(), "победил напавший");
        assertEquals("Нападающий", outcome.summary().args()[0], "в итоге назван победитель");
    }

    @Test
    @DisplayName("Чем ближе силы, тем дороже победа")
    void closerFightCostsMore() {
        Integer easy = battles.resolve("А", 10, 10, "Б", 1, 1, SEED).attackerLosses();
        Integer hard = battles.resolve("А", 10, 10, "Б", 9, 9, SEED).attackerLosses();

        assertTrue(hard > easy, "потери победителя растут вместе с силой противника: "
                + easy + " против " + hard);
    }

    @Test
    @DisplayName("Победитель всегда сохраняет хотя бы один корабль")
    void winnerSurvives() {
        SpaceBattleService.BattleOutcome outcome = battles.resolve("А", 3, 3, "Б", 2, 2, SEED);

        assertTrue(outcome.attackerLosses() < 3, "иначе побеждать было бы некому");
    }

    @Test
    @DisplayName("Равные силы уничтожают друг друга")
    void equalPowerDestroysBoth() {
        SpaceBattleService.BattleOutcome outcome = battles.resolve("А", 5, 5, "Б", 5, 5, SEED);

        assertEquals(5, outcome.attackerLosses());
        assertEquals(5, outcome.defenderLosses());
        assertNull(outcome.attackerWins(), "при равной силе победителя нет");
        assertEquals("battle.outcome.mutual", outcome.summary().key());
    }

    @Test
    @DisplayName("Обороняющийся может отбиться: сильнее — значит побеждает")
    void defenderCanWin() {
        SpaceBattleService.BattleOutcome outcome =
                battles.resolve("Нападающий", 2, 2, "Обороняющийся", 8, 8, SEED);

        assertEquals(2, outcome.attackerLosses(), "нападавший потерял всё");
        assertTrue(outcome.defenderLosses() < 8, "оборона выстояла");
        assertEquals(Boolean.FALSE, outcome.attackerWins(), "победил обороняющийся");
        assertEquals("Обороняющийся", outcome.summary().args()[0], "в итоге назван победитель");
    }
}
