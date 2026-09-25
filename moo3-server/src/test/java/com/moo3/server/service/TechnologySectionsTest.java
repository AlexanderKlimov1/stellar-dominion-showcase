package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.enums.TechnologySection;
import com.moo3.server.dto.ResearchCategoryDto;
import com.moo3.server.dto.ResearchLevelDto;
import com.moo3.server.dto.ResearchOptionDto;
import com.moo3.server.dto.ResearchTreeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Четыре раздела списка изученного — п. 11.1, окно Tech Review MOO II.
 * <p>
 * Раздел выводится из справочников: технология, открывающая здание, — про колонии,
 * открывающая пушку — про оружие. Ошибиться здесь легко и незаметно: раздел не падает
 * и не виден в числах, он просто прячет технологию не в ту кнопку.
 */
class TechnologySectionsTest {

    private final ResearchTreeDto tree = sections().withSections(catalog().tree());

    private static GameProperties properties() {
        return new GameProperties(8, 4, 1.5, "star-names.txt",
                "../resources/Technologies/tech.json",
                "../resources/Buildings/buildings.json",
                "../resources/Races/race-traits.json",
                "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json");
    }

    private static ResearchCatalog catalog() {
        return new ResearchCatalog(properties(), new ObjectMapper());
    }

    private static TechnologySections sections() {
        ObjectMapper mapper = new ObjectMapper();
        return new TechnologySections(
                new BuildingCatalog(properties(), mapper),
                new ShipCatalog(properties(), mapper),
                // Название раздела приходит из словаря — п. 3.5: без контекста запроса
                // оно английское, и проверяется здесь только его наличие.
                Messages.standalone());
    }

    /** Раздел технологии по её коду; неизвестный код — пусто. */
    private String sectionOf(String code) {
        Map<String, String> byCode = new HashMap<>();
        for (ResearchCategoryDto category : tree.categories()) {
            for (ResearchLevelDto level : category.levels()) {
                for (ResearchOptionDto option : level.options()) {
                    byCode.put(option.code(), option.section());
                }
            }
        }
        return byCode.get(code);
    }

    @Test
    @DisplayName("Раздел проставлен у каждой технологии дерева")
    void everyTechnologyHasSection() {
        for (ResearchCategoryDto category : tree.categories()) {
            for (ResearchLevelDto level : category.levels()) {
                for (ResearchOptionDto option : level.options()) {
                    assertTrue(option.section() != null && option.sectionLabel() != null,
                            option.name() + ": раздел не проставлен");
                }
            }
        }
    }

    @Test
    @DisplayName("Здание — про колонии, пушка — про оружие, модуль корабля — про корабли")
    void sectionsFollowWhatTechnologyUnlocks() {
        assertEquals(TechnologySection.COLONY.name(), sectionOf("automated-factory"),
                "автоматический завод — здание колонии");
        assertEquals(TechnologySection.COLONY.name(), sectionOf("hydroponic-farm"));
        assertEquals(TechnologySection.WEAPON.name(), sectionOf("fusion-beam"),
                "термоядерный луч — оружие корабля");
        assertEquals(TechnologySection.SHIP.name(), sectionOf("reinforced-hull"),
                "усиленный корпус — оснащение корабля");
        assertEquals(TechnologySection.SHIP.name(), sectionOf("fusion-drive"),
                "двигатель — оснащение корабля");
    }

    @Test
    @DisplayName("Что не встало ни зданием, ни частью корабля, — общее достижение")
    void therestAreGeneralAchievements() {
        assertEquals(TechnologySection.GENERAL.name(), sectionOf("space-academy"),
                "космическая академия ничего не строит и ничем не стреляет");
    }

    @Test
    @DisplayName("В дереве есть все четыре раздела оригинала")
    void allFourSectionsAreUsed() {
        Set<String> used = tree.categories().stream()
                .flatMap(category -> category.levels().stream())
                .flatMap(level -> level.options().stream())
                .map(ResearchOptionDto::section)
                .collect(Collectors.toSet());
        for (TechnologySection section : TechnologySection.values()) {
            assertTrue(used.contains(section.name()), "в дереве нет раздела " + section);
        }
    }
}
