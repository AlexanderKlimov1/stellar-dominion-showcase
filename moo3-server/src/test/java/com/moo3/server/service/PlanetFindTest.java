package com.moo3.server.service;

import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetFind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Находки на планетах — п. 4.1: числа сверены с первоисточником.
 * <p>
 * Проверяется не то, что эффект «применился», а сами числа оригинала (StrategyWiki,
 * «Which planets to colonize»): пять кредитов у золота, десять у самоцветов, два очка
 * науки с учёного у артефактов и шесть единиц еды у туземцев. Опечатка в любом из них не
 * падает и не видна на экране — колония просто зарабатывает не столько, сколько должна.
 */
class PlanetFindTest {

    private final PopulationCalculator calculator = new PopulationCalculator();

    /** Находка как набор эффектов — ровно так её видит колония. */
    private BuildingEffects effects(PlanetFind find) {
        return new BuildingEffects(find.getEffects(), 0);
    }

    @Test
    @DisplayName("золото даёт ровно 5 кредитов за ход, самоцветы — 10")
    void specialIncome() {
        assertEquals(5, effects(PlanetFind.GOLD_DEPOSITS).incomeFlat());
        assertEquals(10, effects(PlanetFind.GEM_DEPOSITS).incomeFlat());
        // И это ПЛОСКИЕ кредиты, а не процент: на колонии в одного жителя процент от
        // налога был бы нулём, а жила приносит свои пять при любом населении.
        assertEquals(0, effects(PlanetFind.GOLD_DEPOSITS).incomePercent());
    }

    @Test
    @DisplayName("артефакты дают по два лишних очка науки с каждого учёного")
    void artifactsBoostResearch() {
        BuildingEffects artifacts = effects(PlanetFind.ARTIFACTS);

        Integer plain = calculator.research(4);
        Integer withArtifacts = calculator.research(4, artifacts);

        assertEquals(plain + 2 * 4, withArtifacts, "по два очка на каждого из четверых");
        assertTrue(PlanetFind.ARTIFACTS.rewardsExplorer(),
                "и это единственная находка, которая платит ещё и за разведку");
        assertFalse(PlanetFind.GOLD_DEPOSITS.rewardsExplorer());
    }

    @Test
    @DisplayName("туземцы приносят 6 единиц еды и не пашут вместо колонистов")
    void nativesFarm() {
        BuildingEffects natives = effects(PlanetFind.NATIVES);

        // Пустыня — худший из пригодных миров (1 еда с фермера), и на нём туземцы дают
        // ровно тот излишек, который называет первоисточник: «at least 6».
        Integer withoutFarmers = calculator.food(PlanetClimate.DESERT, 0, natives);
        assertEquals(6, withoutFarmers, "еда туземцев не зависит от того, пашет ли колония");

        Integer withFarmers = calculator.food(PlanetClimate.DESERT, 3, natives);
        assertEquals(3 + 6, withFarmers, "и складывается с работой своих фермеров");
    }

    @Test
    @DisplayName("туземцы занимают три места колонии — и потому требуют средней планеты")
    void nativesTakeRoom() {
        assertEquals(-3, effects(PlanetFind.NATIVES).maxPopulationBonus());

        // «Natives come in sets of 3, so planets with Natives have to be at least of
        // Medium size»: на планете, где помещается трое, для хозяев не осталось бы места.
        assertFalse(PlanetFind.NATIVES.fits(3));
        assertTrue(PlanetFind.NATIVES.fits(4));

        // Прочим находкам довольно того, что на планете вообще можно жить: жила без
        // колонии не даёт ничего, но колония на ней бывает и в одного жителя.
        assertTrue(PlanetFind.GOLD_DEPOSITS.fits(1));
        assertFalse(PlanetFind.GOLD_DEPOSITS.fits(0), "на газовом гиганте находка мертва");
    }

    @Test
    @DisplayName("находка не требует содержания: платить за жилу некому")
    void findsCostNothing() {
        for (PlanetFind find : PlanetFind.all()) {
            assertEquals(0, effects(find).upkeep(), find.name());
        }
    }
}
