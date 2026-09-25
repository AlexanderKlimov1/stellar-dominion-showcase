package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила прорыва — п. 9. Стоимость уровня взята из дерева: Advanced Engineering, 80 ОИ. */
class ResearchRulesTest {

    private static final Integer LEVEL_COST = 80;

    private final ResearchRules rules = new ResearchRules();

    @Test
    @DisplayName("До базовой стоимости прорыв невозможен")
    void noBreakthroughBelowBaseCost() {
        assertEquals(80, rules.remainingToBaseCost(LEVEL_COST, 0));
        assertEquals(1, rules.remainingToBaseCost(LEVEL_COST, 79));
        assertEquals(0, rules.breakthroughPercent(LEVEL_COST, 79));
        assertFalse(rules.breakthrough(LEVEL_COST, 79, new Random(1)));
    }

    @Test
    @DisplayName("Оплаченная базовая стоимость открывает прорыв, но не гарантирует его")
    void baseCostOpensBreakthrough() {
        assertEquals(0, rules.remainingToBaseCost(LEVEL_COST, LEVEL_COST));
        assertEquals(0, rules.breakthroughPercent(LEVEL_COST, LEVEL_COST));
        assertEquals(50, rules.breakthroughPercent(LEVEL_COST, 120));
    }

    @Test
    @DisplayName("На двойной базовой стоимости прорыв гарантирован")
    void doubleBaseCostGuaranteesBreakthrough() {
        assertEquals(100, rules.breakthroughPercent(LEVEL_COST, 160));
        assertEquals(100, rules.breakthroughPercent(LEVEL_COST, 500));
        // Гарантированный прорыв не спрашивает генератор: он не зависит от зерна.
        for (int seed = 0; seed < 20; seed++) {
            assertTrue(rules.breakthrough(LEVEL_COST, 160, new Random(seed)));
        }
    }

    @Test
    @DisplayName("Между базовой и двойной стоимостью прорыв случаен")
    void breakthroughIsRandomBetweenBounds() {
        int hits = 0;
        for (int seed = 0; seed < 1000; seed++) {
            if (rules.breakthrough(LEVEL_COST, 120, new Random(seed))) {
                hits++;
            }
        }
        // Половина пути от базовой стоимости к двойной — примерно половина прорывов.
        assertTrue(hits > 400 && hits < 600, "прорывов на 50%: " + hits);
    }
}
