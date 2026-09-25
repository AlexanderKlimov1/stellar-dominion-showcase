package com.moo3.server.service;

import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.enums.ShipRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цены расселения и покорения — п. 4.1, п. 8, п. 12.
 * <p>
 * Все шесть чисел взяты у оригинала: StrategyWiki, страница Units раздела
 * «Master of Orion II: Battle at Antares». Опечатка в любом из них не падает и не видна
 * на экране — она просто ломает темп партии: слишком дешёвая застава заселяет галактику
 * за полсотни ходов, слишком дорогой транспорт не даёт войне случиться вовсе.
 */
class ExpansionRulesTest {

    @Test
    @DisplayName("Цены гражданских кораблей и особых проектов — как в MOO II")
    void costsMatchTheOriginal() {
        assertEquals(500, ColonyProject.COLONY_SHIP_COST, "Colony Ship. Cost: 500");
        assertEquals(100, ColonyProject.OUTPOST_SHIP_COST, "Outpost Ship. Cost: 100");
        assertEquals(100, ColonyProject.TRANSPORT_COST, "Transport Ship. Cost: 100");
        assertEquals(200, ColonyProject.COLONY_BASE_COST, "Colony Base. Cost: 200");
        assertEquals(50, ColonyProject.FREIGHTER_COST, "Freighter Fleet. Cost: 50");
        assertEquals(100, ColonyProject.SPY_COST, "Spy. Cost: 100");
    }

    @Test
    @DisplayName("Транспорт везёт четырёх бойцов")
    void transportCarriesFourMarines() {
        assertEquals(4, ColonyProject.TRANSPORT_TROOPS, "Carries four marines");
    }

    @Test
    @DisplayName("Колониальная база вдвое с половиной дешевле корабля: селиться рядом выгоднее")
    void colonyBaseIsCheaperThanTheShip() {
        assertTrue(ColonyProject.COLONY_BASE_COST < ColonyProject.COLONY_SHIP_COST,
                "в MOO II база и есть дешёвый способ занять соседнюю орбиту");
        assertTrue(ColonyProject.OUTPOST_SHIP_COST < ColonyProject.COLONY_BASE_COST,
                "застава дешевле всего: жителей она не везёт");
    }

    @Test
    @DisplayName("Воюет только боевой корабль: гражданский в бой не выходит")
    void onlyWarshipsFight() {
        assertTrue(ShipRole.WARSHIP.isCombat());
        assertFalse(ShipRole.COLONY.isCombat());
        assertFalse(ShipRole.TRANSPORT.isCombat());
        assertFalse(ShipRole.OUTPOST.isCombat());
        assertEquals(1, Arrays.stream(ShipRole.values())
                .filter(role -> Boolean.TRUE.equals(role.isCombat())).count());
    }

    @Test
    @DisplayName("Каждый особый проект назван и не путается с кораблём по проекту")
    void specialProjectsAreDistinct() {
        assertTrue(ColonyProject.isSpecial(ColonyProject.COLONY_SHIP));
        assertTrue(ColonyProject.isSpecial(ColonyProject.OUTPOST_SHIP));
        assertTrue(ColonyProject.isSpecial(ColonyProject.TRANSPORT));
        // Гражданские корабли строятся по своей твёрдой цене, а не по цене проекта,
        // поэтому кодом корабля из окна дизайна они быть не должны — п. 8.
        assertFalse(ColonyProject.isShip(ColonyProject.COLONY_SHIP));
        assertFalse(ColonyProject.isShip(ColonyProject.OUTPOST_SHIP));
        assertFalse(ColonyProject.isShip(ColonyProject.TRANSPORT));
    }
}
