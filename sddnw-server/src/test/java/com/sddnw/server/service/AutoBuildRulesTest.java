package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import com.sddnw.server.domain.PopulationJobs;
import com.sddnw.server.dto.ColonyProjectDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Что возьмёт колония с автостроем — п. 10, кнопка {@code AUTO BUILD} оригинала.
 * <p>
 * Правило одно на игрока и на ИИ ({@link AutoBuildRules}), и проверяется оно здесь без базы
 * и без сервера. Ошибка тут не падает и на экране не видна: колония просто начнёт брать не
 * то, и заметить это можно будет разве что через полсотни ходов.
 * <p>
 * Справочник читается тот самый, который уходит в игру: правка цены здания должна ронять
 * проверку, а не тихо менять поведение автостроя.
 */
class AutoBuildRulesTest {

    private final BuildingCatalog catalog = new BuildingCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    /** Строка списка стройки: автострою из неё нужен только код. */
    private ColonyProjectDto project(String code) {
        var building = catalog.byCode().get(code);
        return new ColonyProjectDto(code, code, code,
                building == null ? null : building.cost(),
                building == null ? 0 : building.upkeep(),
                Boolean.FALSE, null, Boolean.FALSE);
    }

    @Test
    @DisplayName("Из двух зданий берётся то, что окупается быстрее")
    void picksFastestPayback() {
        // Завод окупается с рабочих, лаборатория — с учёных: на колонии рабочих больше,
        // и выбор обязан зависеть от ЭТОЙ колонии, а не от порядка в справочнике.
        String chosen = AutoBuildRules.next(catalog.byCode(), new PopulationJobs(1, 6, 1),
                List.of(project("research-laboratory"), project("automated-factory")));
        assertEquals("automated-factory", chosen);
    }

    @Test
    @DisplayName("На колонии учёных выбор меняется на лабораторию")
    void picksResearchWhereScientistsAre() {
        String chosen = AutoBuildRules.next(catalog.byCode(), new PopulationJobs(1, 0, 6),
                List.of(project("research-laboratory"), project("automated-factory")));
        assertEquals("research-laboratory", chosen);
    }

    @Test
    @DisplayName("Корабли и особые проекты автострой не берёт")
    void ignoresShipsAndSpecials() {
        // В списке стройки их полно, но это решения о флоте и разведке, а не о хозяйстве
        // колонии: автострой отвечает, чем занять колонию, а не какой у империи план.
        assertNull(AutoBuildRules.next(catalog.byCode(), new PopulationJobs(1, 5, 1),
                List.of(project("SPY"), project("FREIGHTER"), project("COLONY_BASE"),
                        project("HOUSING"), project("TRADE_GOODS"))));
    }

    @Test
    @DisplayName("Не окупается ничто — берётся самое дешёвое здание")
    void fallsBackToCheapest() {
        // Колония без рабочих и учёных: окупаемости нет ни у чего, но простаивать ей
        // незачем — она растёт, и завтра то же здание окупится.
        String chosen = AutoBuildRules.next(catalog.byCode(), new PopulationJobs(1, 0, 0),
                List.of(project("astro-university"), project("marine-barracks")));
        assertEquals("marine-barracks", chosen);
    }

    @Test
    @DisplayName("Брать нечего — автострой молчит, и колония берёт товары")
    void nothingToBuild() {
        assertNull(AutoBuildRules.next(catalog.byCode(), new PopulationJobs(1, 5, 1), List.of()));
    }
}
