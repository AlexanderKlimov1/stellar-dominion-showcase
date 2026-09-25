package com.moo3.server.service;

import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.Building;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetSize;
import com.moo3.server.domain.enums.RaceEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Занятия жителей колонии и их выработка — п. 4.1. */
class PopulationCalculatorTest {

    private final PopulationCalculator calculator = new PopulationCalculator();

    /** Здание справочника с заданным действием и без стоимости: она в этих правилах не нужна. */
    private static Building building(Map<BuildingEffectType, Integer> effects) {
        return new Building("test", LocalizedText.of("Тест"), LocalizedText.of(""), 0, 0, null, effects);
    }

    @Test
    @DisplayName("Еда с фермера зависит от климата: Gaya 3, влажные 2, сухие 1, безжизненные 0")
    void foodDependsOnClimate() {
        assertEquals(3, calculator.food(PlanetClimate.GAIA, 1));
        assertEquals(2, calculator.food(PlanetClimate.TERRAN, 1));
        assertEquals(2, calculator.food(PlanetClimate.OCEAN, 1));
        assertEquals(1, calculator.food(PlanetClimate.TUNDRA, 1));
        assertEquals(0, calculator.food(PlanetClimate.BARREN, 1));
        assertEquals(8, calculator.food(PlanetClimate.TERRAN, 4));
    }

    @Test
    @DisplayName("Житель съедает единицу еды за ход")
    void colonistEatsOne() {
        assertEquals(8, calculator.foodConsumption(8));
    }

    @Test
    @DisplayName("Производство и наука считаются от рабочих и учёных, а не от населения")
    void outputComesFromJobs() {
        assertEquals(8, calculator.production(MineralRichness.AVERAGE, 2));
        assertEquals(6, calculator.research(2));
        assertEquals(3, calculator.researchPerScientist());
    }

    @Test
    @DisplayName("По умолчанию колония сначала кормит себя, остаток делит с перевесом в производство")
    void defaultJobsFeedTheColonyFirst() {
        // Terran: 2 еды с фермера, 8 жителей съедают 8 — хватает четырёх фермеров.
        PopulationJobs terran = calculator.defaultJobs(PlanetClimate.TERRAN, 8);
        assertEquals(new PopulationJobs(4, 2, 2), terran);
        assertEquals(8, terran.total());

        // Gaya: 3 еды с фермера, восьмерых кормят трое (округление вверх).
        assertEquals(new PopulationJobs(3, 3, 2), calculator.defaultJobs(PlanetClimate.GAIA, 8));

        // Tundra: 1 еда с фермера — на еду уходит вся колония.
        assertEquals(new PopulationJobs(8, 0, 0), calculator.defaultJobs(PlanetClimate.TUNDRA, 8));
    }

    @Test
    @DisplayName("Расстановка считает фермеров ПО СВОЕЙ РАСЕ, а не по одному климату")
    void defaultJobsCountFarmersByRace() {
        // Ровно то, чего не делала империя ИИ: она ставила фермеров по климату, и раса с
        // плохими фермерами голодала вечно — добавить фермера было некому (журнал, п. 3.66).
        // Terran, восемь жителей, съедают восемь.
        PopulationJobs average = calculator.defaultJobs(PlanetClimate.TERRAN, 8,
                BuildingEffects.NONE, RaceEffects.NONE);
        assertEquals(new PopulationJobs(4, 2, 2), average);

        // Колониальные стороны расы приходят СЛИТЫМИ в эффекты колонии: «раса работает как
        // вечная постройка, поэтому расчёты колонии их не различают» (ColonyContext.effects).
        // Поэтому здесь они стоят аргументом эффектов, а не расы.
        //
        // Плохие фермеры: четверть еды долой — четверо дают шесть единиц вместо восьми, и
        // прокормить восьмерых ими больше нельзя: нужно ШЕСТЬ.
        BuildingEffects poor = new BuildingEffects(Map.of(BuildingEffectType.FOOD_PERCENT, -25), 0);
        assertEquals(6, calculator.defaultJobs(PlanetClimate.TERRAN, 8,
                poor, RaceEffects.NONE).farmers());

        // Великие фермеры: по две лишние единицы с каждого — хватает ДВОИХ, и шестеро
        // уходят к станку и в лабораторию. Прежде ИИ держал бы у грядок всех четверых.
        BuildingEffects great = new BuildingEffects(Map.of(BuildingEffectType.FOOD_PER_FARMER, 2), 0);
        assertEquals(2, calculator.defaultJobs(PlanetClimate.TERRAN, 8,
                great, RaceEffects.NONE).farmers());

        // Литовор не ест вовсе — к грядкам не идёт никто, вся колония работает.
        RaceEffects lithovore = new RaceEffects(BuildingEffects.NONE,
                Map.of(RaceEffectType.FOOD_CONSUMPTION_PERCENT, -100));
        assertEquals(new PopulationJobs(0, 4, 4), calculator.defaultJobs(
                PlanetClimate.TERRAN, 8, BuildingEffects.NONE, lithovore));

        // Здания считаются тем же счётом: ферма кормит и без единого фермера.
        BuildingEffects farm = new BuildingEffects(Map.of(BuildingEffectType.FOOD_FLAT, 8), 0);
        assertEquals(0, calculator.defaultJobs(PlanetClimate.TERRAN, 8, farm,
                RaceEffects.NONE).farmers());
    }

    @Test
    @DisplayName("На безжизненном климате фермеров нет: еду там не вырастить")
    void noFarmersWithoutFertility() {
        assertEquals(new PopulationJobs(0, 4, 4), calculator.defaultJobs(PlanetClimate.BARREN, 8));
        assertEquals(PopulationJobs.NONE, calculator.defaultJobs(PlanetClimate.TERRAN, 0));
    }

    @Test
    @DisplayName("Сошедшееся распределение не трогается, пустое заменяется распределением по умолчанию")
    void normalizeFixesMismatch() {
        PopulationJobs stored = new PopulationJobs(1, 5, 2);
        assertEquals(stored, calculator.normalize(PlanetClimate.TERRAN, 8, stored));

        // Слепок до появления занятий: нули при живой колонии.
        assertEquals(new PopulationJobs(4, 2, 2),
                calculator.normalize(PlanetClimate.TERRAN, 8, PopulationJobs.NONE));
    }

    @Test
    @DisplayName("Новые жители встают на работу сами, не сбивая расстановку игрока")
    void absorbKeepsPlayerAllocation() {
        // 8 жителей, 1 фермер: колония не кормит себя, поэтому прибыль идёт в фермеры.
        assertEquals(new PopulationJobs(3, 5, 2),
                calculator.normalize(PlanetClimate.TERRAN, 10, new PopulationJobs(1, 5, 2)));

        // Колония кормит себя — прибыль идёт в рабочие, наука игрока не трогается.
        assertEquals(new PopulationJobs(5, 3, 2),
                calculator.normalize(PlanetClimate.TERRAN, 10, new PopulationJobs(5, 1, 2)));

        // Убыль снимается с самого многочисленного занятия.
        assertEquals(new PopulationJobs(4, 2, 2),
                calculator.normalize(PlanetClimate.TERRAN, 8, new PopulationJobs(5, 2, 2)));
    }

    @Test
    @DisplayName("Прирост наибольший на половине вместимости и нулевой у заполненной колонии")
    void basicIncrementPeaksAtHalfCapacity() {
        // ROUNDDOWN(SQRT(2000 * жители * свободно / вместимость)) — формула MOO II.
        assertEquals(104, calculator.basicIncrementK(11, 22));
        assertEquals(100, calculator.basicIncrementK(8, 22));
        assertEquals(0, calculator.basicIncrementK(22, 22));
        assertEquals(0, calculator.basicIncrementK(0, 22));

        // Только что заселённая и почти полная колония растут одинаково медленно.
        assertEquals(calculator.basicIncrementK(1, 22), calculator.basicIncrementK(21, 22));

        // На большой планете разрыв между самой медленной и самой быстрой стадией — вдвое.
        assertEquals(43, calculator.basicIncrementK(1, 16));
        assertEquals(89, calculator.basicIncrementK(8, 16));
    }

    @Test
    @DisplayName("Медицинские технологии ускоряют рост, голод его отнимает")
    void bonusesAndStarvation() {
        assertEquals(100, calculator.growthK(8, 22, 0, 0));
        assertEquals(125, calculator.growthK(8, 22, 25, 0));
        assertEquals(150, calculator.growthK(8, 22, 50, 0));

        // Каждая недостающая единица еды забирает 50 тысяч жителей.
        assertEquals(0, calculator.growthK(8, 22, 0, 2));
        assertEquals(-100, calculator.growthK(8, 22, 0, 4));
    }

    @Test
    @DisplayName("Здания прибавляют и к выработке жителя, и сверх неё")
    void buildingsAddToOutput() {
        // Автоматический завод: 5 единиц производства и ещё по одной с рабочего.
        BuildingEffects factory = BuildingEffects.sum(List.of(building(Map.of(
                BuildingEffectType.PRODUCTION_FLAT, 5,
                BuildingEffectType.PRODUCTION_PER_WORKER, 1))));
        assertEquals(15, calculator.production(MineralRichness.AVERAGE, 2, factory));

        // Исследовательская лаборатория: 5 очков и ещё по одному с учёного.
        BuildingEffects lab = BuildingEffects.sum(List.of(building(Map.of(
                BuildingEffectType.RESEARCH_FLAT, 5,
                BuildingEffectType.RESEARCH_PER_SCIENTIST, 1))));
        assertEquals(13, calculator.research(2, lab));

        // Гидропонная ферма даёт еду и без единого фермера.
        BuildingEffects farm = BuildingEffects.sum(List.of(building(Map.of(BuildingEffectType.FOOD_FLAT, 2))));
        assertEquals(2, calculator.food(PlanetClimate.TERRAN, 0, farm));
        assertEquals(6, calculator.food(PlanetClimate.TERRAN, 2, farm));
    }

    @Test
    @DisplayName("Дома превращают производство в прибавку к рождаемости")
    void housingTurnsProductionIntoGrowth() {
        // ROUNDDOWN(производство * 40 / жители) — формула MOO II.
        assertEquals(40, calculator.housingBonusPercent(8, 8));
        assertEquals(80, calculator.housingBonusPercent(16, 8));
        assertEquals(0, calculator.housingBonusPercent(8, 0));

        // Родной мир: 100 тысяч прироста плюс 40% от домов.
        assertEquals(140, calculator.growthK(8, 22, 40, 0, 0));
    }

    @Test
    @DisplayName("Центр клонирования прибавляет свои тысячи после процентов")
    void cloningCenterAddsFlatGrowth() {
        assertEquals(200, calculator.growthK(8, 22, 0, 100, 0));
        assertEquals(225, calculator.growthK(8, 22, 25, 100, 0));
    }

    @Test
    @DisplayName("Товары и излишки еды продаются по кредиту за две единицы")
    void goodsAndFoodSellAtTwoPerCredit() {
        assertEquals(4, calculator.creditsFor(8));
        assertEquals(4, calculator.creditsFor(9));
        assertEquals(0, calculator.creditsFor(-5));
    }

    @Test
    @DisplayName("Рейс между системами держит по грузовику на жителя — п. 4.1.1")
    void transferTakesFreighterPerColonist() {
        assertEquals(3, calculator.freightersForTransfer(3));
        assertEquals(0, calculator.freightersForTransfer(0));
        assertEquals(0, calculator.freightersForTransfer(-2));
    }

    @Test
    @DisplayName("Рециклотрон даёт единицу с жителя и грязи не прибавляет — п. 10")
    void recyclotronPaysPerColonistAndDoesNotPollute() {
        BuildingEffects recyclotron = BuildingEffects.sum(List.of(building(Map.of(
                BuildingEffectType.PRODUCTION_PER_COLONIST, 1))));
        // Колония из восьми жителей, двое из них рабочие: 2 × 4 с рабочего плюс 8 с
        // переработки мусора — мусорят все, а не одни рабочие.
        assertEquals(16, calculator.production(MineralRichness.AVERAGE, 2, 8, recyclotron));
        // Та же колония без рециклотрона добыла бы восемь единиц.
        assertEquals(8, calculator.production(MineralRichness.AVERAGE, 2, 8, BuildingEffects.NONE));

        // Грязным считается только добытое рабочими: на большой планете (терпимость 8)
        // восемь единиц рабочих грязи не дают вовсе, хотя добыто вдвое больше.
        Integer clean = calculator.cleanProduction(8, recyclotron);
        assertEquals(8, clean);
        assertEquals(0, calculator.pollution(16, clean, 8, 1));
        // Без рециклотрона те же 16 единиц были бы грязными целиком: (16 − 8) / 2.
        assertEquals(4, calculator.pollution(16, 0, 8, 1));
    }

    @Test
    @DisplayName("Уборка съедает половину производства сверх терпимости планеты — п. 10")
    void pollutionEatsHalfOfTheExcess() {
        // Пример оригинала: 100 производства при заводе (+5) и робошахтёрах (+10) на
        // средней планете (терпимость 6) с переработчиком отходов (делитель 2) —
        // 19 единиц уборки, 81 полезного производства.
        assertEquals(19, calculator.pollution(100, 15, 6, 2));
        // Он же без очистных сооружений: (100 − 15 − 6) / 2, округляя вверх.
        assertEquals(40, calculator.pollution(100, 15, 6, 1));
        // И он же с обоими сооружениями: делитель 8, (85 − 48) / 16.
        assertEquals(3, calculator.pollution(100, 15, 6, 8));
        // Пока промышленность в пределах терпимости, убирать нечего.
        assertEquals(0, calculator.pollution(8, 0, 8, 1));
        assertEquals(0, calculator.pollution(20, 15, 6, 1));
        // Твёрдая прибавка зданий не пачкает вовсе: завод на крошечной планете чист.
        assertEquals(0, calculator.pollution(5, 5, 2, 1));
    }

    @Test
    @DisplayName("Выкуп: два кредита за единицу с половины, четыре — до неё — п. 10")
    void buyingCostsTwiceAsMuchBeforeHalf() {
        // Числа оригинала: завод ценой 60 с одним ходом стройки — 220 кредитов.
        assertEquals(220, calculator.buyCost(60, 5));
        // Колониальный корабль (500) и база (200) ровно на половине — 500 и 200 кредитов.
        assertEquals(500, calculator.buyCost(500, 250));
        assertEquals(200, calculator.buyCost(200, 100));
        // Цена меняется скачком на половине: единицей раньше неё — вдвое дороже.
        assertEquals(198, calculator.buyCost(200, 101));
        assertEquals(404, calculator.buyCost(200, 99));
        // Оплаченному докупать нечего.
        assertEquals(0, calculator.buyCost(60, 60));
        assertEquals(0, calculator.buyCost(60, 99));
    }

    @Test
    @DisplayName("За проданную постройку дают половину её цены — п. 10")
    void buildingSellsForHalfItsCost() {
        // Число оригинала: казармы ценой 60 продаются за 30 кредитов.
        assertEquals(30, calculator.sellValue(60));
        assertEquals(45, calculator.sellValue(90));
        // Нечётная цена округляется вниз: половинок кредита в игре нет.
        assertEquals(37, calculator.sellValue(75));
        assertEquals(0, calculator.sellValue(0));
    }

    @Test
    @DisplayName("Налог — кредит с жителя, космопорт и биржа поднимают его процентами")
    void taxIncomeGrowsWithBuildings() {
        assertEquals(8, calculator.taxIncome(8, 0));
        assertEquals(12, calculator.taxIncome(8, 50));
        assertEquals(16, calculator.taxIncome(8, 100));
        assertEquals(20, calculator.taxIncome(8, 150));
    }

    @Test
    @DisplayName("Universal Antidote перекрывает Microbiotics, а не складывается с ним")
    void medicineBonusTakesTheBest() {
        assertEquals(0, calculator.medicineBonusPercent(List.of()));
        assertEquals(25, calculator.medicineBonusPercent(List.of("microbiotics")));
        assertEquals(50, calculator.medicineBonusPercent(List.of("universal-antidote")));
        assertEquals(50, calculator.medicineBonusPercent(List.of("microbiotics", "universal-antidote")));
    }

    @Test
    @DisplayName("Процент правительства берётся от выработки всех работников разом")
    void governmentPercentAppliesToTheWholeCrew() {
        // Объединение MOO II: половина сверху к рабочему и фермеру. Четверо рабочих
        // по 4 единицы дают 16, с объединением — 24.
        BuildingEffects unification = new BuildingEffects(
                Map.of(BuildingEffectType.PRODUCTION_PERCENT, 50, BuildingEffectType.FOOD_PERCENT, 50), 0);
        assertEquals(24, calculator.production(MineralRichness.AVERAGE, 4, unification));
        assertEquals(12, calculator.food(PlanetClimate.TERRAN, 4, unification));

        // Плохие фермеры MOO II роняют фермера с двух единиц до полутора. На одном
        // фермере полторы записать нечем, а вчетвером они дают ровно шесть.
        BuildingEffects poorFarmers = new BuildingEffects(Map.of(BuildingEffectType.FOOD_PERCENT, -25), 0);
        assertEquals(6, calculator.food(PlanetClimate.TERRAN, 4, poorFarmers));

        // Постройки под процент не попадают: феодализм не трогает науку лабораторий.
        BuildingEffects feudalWithLab = new BuildingEffects(
                Map.of(BuildingEffectType.RESEARCH_PERCENT, -50, BuildingEffectType.RESEARCH_FLAT, 5), 0);
        assertEquals(11, calculator.research(4, feudalWithLab));
    }

    @Test
    @DisplayName("Киборг ест вполовину меньше и проедает руду, литовор не ест вовсе")
    void raceChangesWhatColonyEats() {
        RaceEffects cybernetic = new RaceEffects(BuildingEffects.NONE, Map.of(
                RaceEffectType.FOOD_CONSUMPTION_PERCENT, -50,
                RaceEffectType.PRODUCTION_CONSUMPTION_PERCENT, 50));
        // Округление вверх, как в MOO II: пятеро киборгов съедают три, а не две с половиной.
        assertEquals(3, calculator.foodConsumption(5, cybernetic));
        assertEquals(4, calculator.foodConsumption(8, cybernetic));
        assertEquals(3, calculator.productionConsumption(5, cybernetic));

        RaceEffects lithovore = new RaceEffects(BuildingEffects.NONE,
                Map.of(RaceEffectType.FOOD_CONSUMPTION_PERCENT, -100));
        assertEquals(0, calculator.foodConsumption(8, lithovore));
        assertEquals(0, calculator.productionConsumption(8, lithovore));
    }

    @Test
    @DisplayName("Вместимость планеты меняют неприхотливые, водные и подземные")
    void raceChangesHowManyFit() {
        // Обычная раса на тундре среднего размера: 18 × 50% = 9.
        assertEquals(9, calculator.maxPopulation(PlanetSize.MEDIUM, PlanetClimate.TUNDRA));

        // Неприхотливым тундра пригодна на четверть больше: 18 × 75% = 13.5 → 14.
        RaceEffects tolerant = new RaceEffects(BuildingEffects.NONE,
                Map.of(RaceEffectType.HABITABILITY_PERCENT, 25));
        assertEquals(14, calculator.maxPopulation(PlanetSize.MEDIUM, PlanetClimate.TUNDRA, tolerant));
        // На райской планете прибавки нет: выше сотни пригодность не поднимается.
        assertEquals(18, calculator.maxPopulation(PlanetSize.MEDIUM, PlanetClimate.GAIA, tolerant));

        // Водной расе тундра — что земной мир: 18 × 90% = 16.2 → 16, и фермер даёт лишнее.
        RaceEffects aquatic = new RaceEffects(BuildingEffects.NONE, Map.of(RaceEffectType.AQUATIC, 1));
        assertEquals(16, calculator.maxPopulation(PlanetSize.MEDIUM, PlanetClimate.TUNDRA, aquatic));
        assertEquals(2, calculator.foodPerFarmer(PlanetClimate.TUNDRA, aquatic));
        assertEquals(1, calculator.foodPerFarmer(PlanetClimate.TUNDRA, RaceEffects.NONE));
        // Сухие миры водной расе не дают ничего.
        assertEquals(calculator.maxPopulation(PlanetSize.MEDIUM, PlanetClimate.DESERT),
                calculator.maxPopulation(PlanetSize.MEDIUM, PlanetClimate.DESERT, aquatic));

        // Подземные копают вглубь: два жителя на крошечной планете, десять на огромной.
        RaceEffects subterranean = new RaceEffects(BuildingEffects.NONE,
                Map.of(RaceEffectType.MAX_POPULATION_PER_SIZE, 2));
        assertEquals(2, calculator.raceCapacityBonus(PlanetSize.TINY, PlanetClimate.TERRAN, subterranean));
        assertEquals(6, calculator.raceCapacityBonus(PlanetSize.MEDIUM, PlanetClimate.TERRAN, subterranean));
        assertEquals(10, calculator.raceCapacityBonus(PlanetSize.HUGE, PlanetClimate.TERRAN, subterranean));
    }
}
