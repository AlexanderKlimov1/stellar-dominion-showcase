package com.moo3.server.service;

import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.Leader;
import com.moo3.server.domain.enums.LeaderKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Числовые правила лидеров — п. 6.
 * <p>
 * Проверяется то, что задаёт саму механику: раса меняет частоту появления и цену, звание
 * поднимает силу способностей, «Богач» жалованья не берёт, а лидеры приходят не раньше
 * своего срока. Ошибка здесь не падает и не видна на экране — просто лидеры перестают
 * зависеть от расы или дорожают невпопад.
 */
class LeaderRulesTest {

    private final LeaderRules rules = new LeaderRules();

    private Leader leader(Integer startExperience, List<Leader.LeaderSkill> skills, List<String> techs) {
        return new Leader(1, "test", LocalizedText.of("Test"), LocalizedText.of("Проверочный"), LeaderKind.COLONY, null,
                startExperience, skills, techs, Boolean.FALSE, 0);
    }

    @Test
    @DisplayName("Отталкивающей расе лидеры приходят реже, обаятельной — чаще")
    void raceChangesOfferChance() {
        Integer plain = rules.offerChancePercent(Boolean.FALSE, Boolean.FALSE);
        Integer repulsive = rules.offerChancePercent(Boolean.TRUE, Boolean.FALSE);
        Integer charismatic = rules.offerChancePercent(Boolean.FALSE, Boolean.TRUE);

        assertTrue(repulsive < plain, "у отталкивающей выбор лидеров беднее — п. 7");
        assertTrue(charismatic > plain, "обаятельная — зеркало отталкивающей");
    }

    @Test
    @DisplayName("Отталкивающей дороже, обаятельной вдвое дешевле")
    void raceChangesHireCost() {
        Leader hero = leader(60, List.of(new Leader.LeaderSkill("LABOR", 20.0, 10.0)), List.of());

        Integer plain = rules.hireCost(hero, 1, 0, Boolean.FALSE, Boolean.FALSE);
        Integer repulsive = rules.hireCost(hero, 1, 0, Boolean.TRUE, Boolean.FALSE);
        Integer charismatic = rules.hireCost(hero, 1, 0, Boolean.FALSE, Boolean.TRUE);

        assertTrue(repulsive > plain, "отталкивающей лидеры обходятся дороже");
        assertEquals(plain / 2, charismatic, "обаятельной — вдвое дешевле");
    }

    @Test
    @DisplayName("Знаменитость удешевляет наём, но не делает лидеров бесплатными")
    void famousDiscountApplies() {
        Leader hero = leader(60, List.of(new Leader.LeaderSkill("LABOR", 20.0, 10.0)), List.of());

        Integer full = rules.hireCost(hero, 1, 0, Boolean.FALSE, Boolean.FALSE);
        Integer discounted = rules.hireCost(hero, 1, 60, Boolean.FALSE, Boolean.FALSE);
        Integer huge = rules.hireCost(hero, 1, 100_000, Boolean.FALSE, Boolean.FALSE);

        assertEquals(full - 60, discounted);
        assertEquals(0, huge, "даром лидеры не служат даже у самой прославленной империи");
    }

    @Test
    @DisplayName("«Богач» жалованья не берёт, остальные берут")
    void megawealthTakesNoSalary() {
        Leader rich = leader(60, List.of(new Leader.LeaderSkill("MEGAWEALTH", 10.0, 0.0)), List.of());
        Leader plain = leader(60, List.of(new Leader.LeaderSkill("LABOR", 20.0, 10.0)), List.of());

        assertEquals(0, rules.salary(rich, 600));
        assertTrue(rules.salary(plain, 600) > 0);
    }

    @Test
    @DisplayName("Способность растёт со званием, а до своего звания не растёт")
    void skillGrowsWithRank() {
        Leader.LeaderSkill skill = new Leader.LeaderSkill("LABOR", 20.0, 10.0);

        assertEquals(20, rules.skillValue(skill, 1, 1), "на своём звании — базовая сила");
        assertEquals(30, rules.skillValue(skill, 2, 1), "звание выше — прибавка");
        assertEquals(20, rules.skillValue(skill, 0, 1), "ниже стартового звания не опускаемся");
    }

    @Test
    @DisplayName("Лучшие лидеры приходят не сразу")
    void strongLeadersComeLater() {
        Leader novice = leader(0, List.of(), List.of());
        Leader master = leader(300, List.of(), List.of());

        assertTrue(rules.dueByTurn(novice, 1), "новичок приходит с первых ходов");
        assertFalse(rules.dueByTurn(master, 100), "комиссаров раньше полутора сотен ходов не ждут");
        assertTrue(rules.dueByTurn(master, 150));
    }

    @Test
    @DisplayName("Отталкивающей расе дипломатов и торговцев не предлагают")
    void repulsiveGetsNoDiplomats() {
        Leader diplomat = leader(60, List.of(new Leader.LeaderSkill("DIPLOMAT", 20.0, 10.0)), List.of());
        Leader worker = leader(60, List.of(new Leader.LeaderSkill("LABOR", 20.0, 10.0)), List.of());

        assertFalse(rules.suitable(diplomat, Boolean.TRUE),
                "договоров такая империя не ведёт — дипломат брал бы деньги ни за что");
        assertTrue(rules.suitable(worker, Boolean.TRUE));
        assertTrue(rules.suitable(diplomat, Boolean.FALSE));
    }
}
