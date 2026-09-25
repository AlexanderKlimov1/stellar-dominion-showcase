package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.enums.SpaceMonster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Из чего чудище собрано для тактического боя — п. 8, п. 11.1.
 * <p>
 * Главная проверка здесь одна: <b>собранное чудище весит свою силу</b>. Игра отвечает на
 * вопрос «силён ли дракон» двумя путями — быстрым боём на подлёте (сила против силы) и
 * тактическим полем (тело, шкура и когти), — и разойдись эти два ответа, один и тот же
 * дракон был бы разным в зависимости от того, кто к нему прилетел. Ошибка эта не падает
 * и на экране не видна: заметить её можно было бы только замером через месяц.
 */
class MonsterBattleRulesTest {

    private final ShipCatalog catalog = new ShipCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private final ShipDesignRules shipDesignRules = new ShipDesignRules();
    private final MonsterBattleRules rules = new MonsterBattleRules(catalog, shipDesignRules);

    @Test
    @DisplayName("У каждого чудища есть своё тело, и в списки кораблей оно не попадает")
    void everyMonsterHasABody() {
        for (SpaceMonster kind : SpaceMonster.all()) {
            ShipHull body = rules.hull(kind);
            assertTrue(Boolean.TRUE.equals(body.monster()),
                    "тело чудища обязано быть помечено в справочнике: " + kind);
        }
        // Тела и части чудищ прячет сам справочник: иначе тело дракона встало бы в окно
        // дизайна, а когти — в список изученного оружия (платформы так просачивались дважды).
        assertFalse(catalog.hulls().stream().anyMatch(hull -> Boolean.TRUE.equals(hull.monster())));
        assertFalse(catalog.components().stream()
                .anyMatch(component -> Boolean.TRUE.equals(component.monster())));
    }

    @Test
    @DisplayName("Собранное чудище весит свою силу — тем же мерилом, что и флот")
    void bodyWeighsItsStrength() {
        for (SpaceMonster kind : SpaceMonster.all()) {
            Integer strength = kind.getStrength();
            ShipStats stats = shipDesignRules.stats(rules.hull(kind),
                    rules.items(kind, strength), RaceEffects.NONE);
            Integer power = shipDesignRules.power(stats);

            // Допуск в четверть: когти кладутся целыми, и у самого слабого чудища один
            // коготь — это заметная доля его силы. Точнее целыми стволами не выйдет.
            assertTrue(Math.abs(power - strength) * 4 <= strength,
                    kind + ": сила собранного " + power + " при заявленной " + strength);
        }
    }

    @Test
    @DisplayName("Раненое чудище выходит на поле слабее, но когти у него остаются")
    void woundedMonsterKeepsAtLeastOneClaw() {
        SpaceMonster dragon = SpaceMonster.DRAGON;
        Integer whole = rules.claws(dragon, dragon.getStrength());
        Integer wounded = rules.claws(dragon, dragon.getStrength() / 4);
        assertTrue(wounded < whole, "ослабевшее чудище бьёт слабее целого");
        assertTrue(rules.claws(dragon, 1) >= 1, "без когтей чудище стало бы мишенью, "
                + "и бой с ним не кончился бы никогда");
    }

    @Test
    @DisplayName("После боя сила чудища — доля уцелевшего тела")
    void strengthLeftFollowsTheBody() {
        SpaceMonster kind = SpaceMonster.HYDRA;
        Integer strength = kind.getStrength();
        ShipStats full = shipDesignRules.stats(rules.hull(kind),
                rules.items(kind, strength), RaceEffects.NONE);

        assertEquals(strength,
                rules.leftAfter(strength, full, full.structure(), full.armour()),
                "целое чудище остаётся при своей силе");
        assertEquals(0, rules.leftAfter(strength, full, 0, 0),
                "разбитое наголову не остаётся вовсе");
        Integer half = rules.leftAfter(strength, full, full.structure() / 2, full.armour() / 2);
        assertTrue(half > 0 && half < strength, "полтела — половина силы: " + half);
    }

    @Test
    @DisplayName("Части чудища достаются по коду — бою нужно собрать его, а окну осмотра показать")
    void partsAreStillReachableByCode() {
        assertEquals(List.of("monster-claws", "monster-hide", "monster-fins"),
                List.of(catalog.component(MonsterBattleRules.CLAWS).code(),
                        catalog.component(MonsterBattleRules.HIDE).code(),
                        catalog.component(MonsterBattleRules.FINS).code()));
    }
}
