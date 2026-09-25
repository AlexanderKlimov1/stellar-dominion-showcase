package com.moo3.server.service;

import com.moo3.server.domain.LocalizedText;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.Building;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.enums.BuildingEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Описание зданий — п. 10. Проверка читает тот самый файл, который уходит в игру,
 * поэтому опечатка в нём ломает сборку, а не партию.
 */
class BuildingCatalogTest {

    private final BuildingCatalog catalog = new BuildingCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    @Test
    @DisplayName("Справочник читается целиком, коды не повторяются")
    void catalogReads() {
        List<Building> buildings = catalog.all();
        assertEquals(32, buildings.size());
        assertEquals(buildings.size(), buildings.stream().map(Building::code).distinct().count());

        for (Building building : buildings) {
            assertNotNull(building.name(), building.code());
            assertTrue(building.cost() > 0, "стоимость здания " + building.code());
            assertTrue(building.upkeep() >= 0, "содержание здания " + building.code());
        }

        // Здание действует числом или правилом. Числом — все, кроме обороны колонии: её
        // действие не выражается прибавкой. Звёздная база — верфь (п. 8: от неё зависит,
        // какие корпуса сходят со стапеля), а она же вместе с боевой станцией, звёздной
        // крепостью, ракетной базой, батареями и ангарами выходит платформой в бой
        // (п. 11) — чем именно она вооружена, решает изученное, а не справочник зданий.
        Set<String> defence = Set.of("star-base", "battle-station", "star-fortress",
                "missile-base", "ground-batteries", "fighter-garrison",
                "planetary-flux-shield", "planetary-barrier-shield", "artemis-system-net");
        for (Building building : buildings) {
            assertTrue(!building.effects().isEmpty() || defence.contains(building.code()),
                    "здание без действия: " + building.code());
        }
    }

    @Test
    @DisplayName("Стоимость, содержание и технология берутся из файла")
    void costsComeFromFile() {
        Building biospheres = catalog.require("biospheres");
        assertEquals(60, biospheres.cost());
        assertEquals(1, biospheres.upkeep());
        assertEquals("biospheres", biospheres.requiredTechCode());
        assertEquals(2, biospheres.amount(BuildingEffectType.MAX_POPULATION));
        assertEquals(0, biospheres.amount(BuildingEffectType.FOOD_FLAT));
    }

    @Test
    @DisplayName("У здания бывает несколько действий сразу")
    void buildingsMayHaveSeveralEffects() {
        Building university = catalog.require("astro-university");
        assertEquals(Map.of(
                        BuildingEffectType.FOOD_PER_FARMER, 1,
                        BuildingEffectType.PRODUCTION_PER_WORKER, 1,
                        BuildingEffectType.RESEARCH_PER_SCIENTIST, 1),
                university.effects());
    }

    @Test
    @DisplayName("Действия зданий колонии складываются, содержание тоже")
    void effectsAddUp() {
        BuildingEffects effects = BuildingEffects.sum(List.of(
                catalog.require("automated-factory"),
                catalog.require("robo-miners")));
        assertEquals(15, effects.productionFlat());
        assertEquals(3, effects.productionPerWorker());
        assertEquals(3, effects.upkeep());
        assertEquals(0, effects.foodFlat());
    }

    @Test
    @DisplayName("Здание без технологии доступно с начала партии")
    void buildingWithoutTechIsAlwaysAvailable() {
        // Таких зданий в MOO II ровно два, и оба стоят на родном мире с первого хода:
        // звёздная база (верфь, п. 8) и казармы морской пехеты (оборона колонии, п. 12) —
        // «Marine Barracks is a starting tech». Всё остальное открывается технологией.
        assertNull(catalog.require("star-base").requiredTechCode());
        assertNull(catalog.require("marine-barracks").requiredTechCode());
        assertTrue(catalog.all().stream()
                        .filter(building -> !"star-base".equals(building.code()))
                        .filter(building -> !"marine-barracks".equals(building.code()))
                        .allMatch(building -> building.requiredTechCode() != null),
                "остальные здания в справочнике открываются технологией");
        assertNull(new Building("x", LocalizedText.of("x"), LocalizedText.of(""), 1, 0, null, Map.of()).requiredTechCode());
    }

    @Test
    @DisplayName("Звёздная база — верфь колонии: цена и содержание из MOO II")
    void starBase() {
        Building starBase = catalog.require("star-base");
        assertEquals(90, starBase.cost());
        assertEquals(2, starBase.upkeep(), "в MOO II база стоит два кредита содержания");
        assertTrue(starBase.effects().isEmpty(), "её действие — правило стройки, а не число");
    }
}
