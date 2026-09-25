package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.Building;
import com.moo3.server.domain.PopulationJobs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Окупаемость здания на колонии — п. 10, п. 15 (журнал, п. 3.94).
 * <p>
 * Это то самое сравнение, которого у ИИ не было: прежде порядок стройки задавался неизменным
 * списком, и колония бралась за расселение, даже когда рядом лежал завод, окупающийся за
 * шесть ходов. Ошибка здесь не падает и не видна на экране — просто империи снова перестают
 * строить, и узнать об этом можно будет лишь прогоном в пятьсот партий.
 * <p>
 * Справочник читается тот самый, который уходит в игру: правка цены здания должна ронять
 * проверку, а не тихо менять поведение ИИ.
 */
class AiPaybackTest {

    private final BuildingCatalog catalog = new BuildingCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private Building building(String code) {
        Building found = catalog.byCode().get(code);
        assertNotNull(found, "в справочнике нет здания " + code);
        return found;
    }

    private PopulationJobs jobs(Integer farmers, Integer workers, Integer scientists) {
        return new PopulationJobs(farmers, workers, scientists);
    }

    @Test
    @DisplayName("Завод на пяти рабочих окупается за шесть ходов")
    void factoryPaysBackFast() {
        // 60 единиц цены против пяти постоянных и по единице с рабочего: 60 / 10.
        assertEquals(6, AiEmpireService.paybackTurns(building("automated-factory"), jobs(2, 5, 2)));
    }

    @Test
    @DisplayName("Суперкомпьютер тем выгоднее, чем больше на колонии учёных")
    void supercomputerFollowsScientists() {
        Building supercomputer = building("supercomputer");
        // 150 / (10 + 2 * учёных): при трёх — девять ходов, при нуле — пятнадцать.
        assertEquals(9, AiEmpireService.paybackTurns(supercomputer, jobs(2, 2, 3)));
        assertEquals(15, AiEmpireService.paybackTurns(supercomputer, jobs(2, 2, 0)));
    }

    @Test
    @DisplayName("Обгонять расселение вправе только бесспорное")
    void onlyObviousBuildingsOutrankExpansion() {
        // Порог, с которым здание идёт вперёд расселения, — восемь ходов. Завод и робошахты
        // на выросшей колонии его проходят, суперкомпьютер без учёных — нет, и ждёт очереди
        // ниже расселения. Если это перестанет быть так, молодая колония засядет строить
        // полтораста единиц вместо колониальной базы.
        assertTrue(AiEmpireService.paybackTurns(building("automated-factory"), jobs(2, 5, 2)) <= 8);
        assertTrue(AiEmpireService.paybackTurns(building("robo-miners"), jobs(2, 5, 2)) <= 8);
        assertTrue(AiEmpireService.paybackTurns(building("supercomputer"), jobs(2, 2, 0)) > 8);
    }

    @Test
    @DisplayName("Здание, которое не даёт ни выработки, ни науки, окупаемости не имеет")
    void nonEconomicBuildingsHaveNoPayback() {
        // Оборона, казармы и биосферы полезны, но возвращают не то, чем платят: их очередь
        // задаётся списками, а не этим правилом.
        assertNull(AiEmpireService.paybackTurns(building("star-base"), jobs(2, 5, 2)));
        assertNull(AiEmpireService.paybackTurns(building("marine-barracks"), jobs(2, 5, 2)));
        assertNull(AiEmpireService.paybackTurns(building("biospheres"), jobs(2, 5, 2)));
    }

    @Test
    @DisplayName("Пустая колония считается как пустая, а не как ошибка")
    void emptyColonyIsCounted() {
        assertEquals(12, AiEmpireService.paybackTurns(building("automated-factory"),
                PopulationJobs.NONE));
        assertNull(AiEmpireService.paybackTurns(null, PopulationJobs.NONE));
    }

    @Test
    @DisplayName("Порядок хозяйства выводится из окупаемости, а не из списка")
    void orderComesFromPayback() {
        // На выросшей колонии (пять рабочих, три учёных) правильный порядок такой:
        // завод, лаборатория, робошахты, суперкомпьютер — и его даёт сама арифметика.
        PopulationJobs grown = jobs(3, 5, 3);
        Integer factory = AiEmpireService.paybackTurns(building("automated-factory"), grown);
        Integer lab = AiEmpireService.paybackTurns(building("research-laboratory"), grown);
        Integer miners = AiEmpireService.paybackTurns(building("robo-miners"), grown);
        Integer computer = AiEmpireService.paybackTurns(building("supercomputer"), grown);
        assertTrue(factory <= lab, "завод не позже лаборатории: " + factory + " и " + lab);
        assertTrue(lab <= miners, "лаборатория не позже робошахт: " + lab + " и " + miners);
        assertTrue(miners <= computer, "робошахты не позже суперкомпьютера: "
                + miners + " и " + computer);
    }
}
