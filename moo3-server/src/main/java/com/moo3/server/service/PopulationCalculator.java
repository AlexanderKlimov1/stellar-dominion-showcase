package com.moo3.server.service;

import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetSize;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Расчёт производных характеристик планеты — п. 4.1.1.1, п. 4.1.2, п. 4.1.3, п. 9.
 */
@Service
public class PopulationCalculator {

    /**
     * Очков исследований с одного учёного за ход — базовая выработка MOO II без
     * расовых бонусов, зданий и правительства.
     */
    private static final int RESEARCH_POINTS_PER_SCIENTIST = 3;

    /** Единиц еды, которые съедает один житель за ход. */
    private static final int FOOD_PER_COLONIST = 1;

    /**
     * Множитель формулы прироста MOO II. Прирост считается в тысячах жителей, поэтому
     * на планете средней заселённости он выходит около сотни тысяч за ход.
     */
    private static final int GROWTH_FACTOR = 2000;

    /** Тысяч жителей, которые теряет колония за каждую недостающую единицу еды. */
    private static final int STARVATION_PENALTY_K = 50;

    /** Прибавка к приросту от медицинских технологий MOO II, в процентах. */
    private static final int UNIVERSAL_ANTIDOTE_PERCENT = 50;
    private static final int MICROBIOTICS_PERCENT = 25;

    /**
     * Множитель домов MOO II: производство колонии превращается в прибавку к рождаемости
     * по формуле {@code ROUNDDOWN(производство * 40 / жители)} процентов.
     */
    private static final int HOUSING_FACTOR = 40;

    /** Единиц производства или еды за один кредит при продаже — MOO II продаёт излишки по этому курсу. */
    private static final int UNITS_PER_CREDIT = 2;

    /** Кредитов за недостающую единицу производства, когда стройка сделана наполовину. */
    private static final int BUY_RATE_HALF_BUILT = 2;

    /** То же, пока половины нет: спешка вдвое дороже — п. 10. */
    private static final int BUY_RATE_FRESH = 4;

    /**
     * Во сколько раз продажа постройки дешевле её цены — п. 10.
     * <p>
     * Вдвое: в MOO II казармы ценой 60 единиц продаются за 30 кредитов. Курс тот же, что
     * и у излишков, но величина своя — цена здания считается в единицах производства, а
     * не в них же за ход, и путать эти два правила не стоит.
     */
    private static final int SELL_DIVISOR = 2;

    /**
     * Сколько еды увозит один грузовой корабль за ход — п. 4.1.1.
     * <p>
     * Реконструкция: в MOO II грузовик возит пять единиц еды или пять тысяч жителей.
     * Перевозку населения игра пока не считает, а еда уже возится этой цифрой.
     */
    private static final int FOOD_PER_FREIGHTER = 5;

    /**
     * Сколько грузовиков занимает единица населения в рейсе — п. 4.1.1.
     * <p>
     * Один к одному: сколько жителей везём, столько грузовиков и занято. MOO II сажает
     * в грузовик больше, но и возит дольше; здесь взято правило партии.
     */
    private static final int FREIGHTERS_PER_POPULATION = 1;

    /** Кредитов налога с одного жителя за ход. */
    private static final int TAX_PER_COLONIST = 1;

    /** Коды медицинских технологий в дереве — п. 9. */
    public static final String UNIVERSAL_ANTIDOTE = "universal-antidote";
    public static final String MICROBIOTICS = "microbiotics";

    /**
     * Максимальное население планеты: базовое население размера (для Gaya)
     * умноженное на процент плодородности климата.
     * Для необитаемых типов (пояс астероидов, газовый гигант) — 0.
     */
    public Integer maxPopulation(PlanetSize size, PlanetClimate climate) {
        return maxPopulation(size, climate, RaceEffects.NONE);
    }

    /**
     * Максимальное население планеты для этой расы — п. 7.
     * <p>
     * Раса меняет вместимость двумя способами. Неприхотливые считают <b>любой</b> мир
     * земным («максимум колонистов всегда рассчитывается как на планетах земного типа»,
     * MOO II), а водные считают мокрые миры лучшими, чем те есть; выше райской планеты
     * пригодность не поднимается, поэтому на Gaya обе стороны не дают ничего. Подземные же копают вглубь, и их прибавка от климата не
     * зависит вовсе: она растёт с размером планеты, от двух жителей на крошечной до
     * десяти на огромной.
     */
    public Integer maxPopulation(PlanetSize size, PlanetClimate climate, RaceEffects race) {
        if (!climate.getColonizable()) {
            return 0;
        }

        int habitability = climate.getPopulationMultiplierPercent();
        if (Boolean.TRUE.equals(race.aquatic())) {
            habitability = Math.max(habitability, AQUATIC_HABITABILITY.getOrDefault(climate, habitability));
        }
        if (Boolean.TRUE.equals(race.tolerant())) {
            habitability = Math.max(habitability,
                    PlanetClimate.TERRAN.getPopulationMultiplierPercent());
        }
        habitability = Math.min(MAX_HABITABILITY_PERCENT, habitability + race.habitabilityPercent());

        int scaled = Math.round(size.getBasePopulation() * habitability / 100.0f);
        return Math.max(1, scaled) + race.maxPopulationPerSize() * sizeSteps(size);
    }

    /**
     * Насколько вместимость планеты больше из-за расы её хозяина — п. 7.
     * <p>
     * Отдельно от {@link #maxPopulation(PlanetSize, PlanetClimate, RaceEffects)} потому,
     * что вместимость планеты записана на самой планете и меняется терраформированием,
     * а раса — свойство хозяина и уходит вместе с ним: колонию можно и захватить.
     * Поэтому расовая часть считается поверх записанной, а не вместо неё.
     */
    public Integer raceCapacityBonus(PlanetSize size, PlanetClimate climate, RaceEffects race) {
        return maxPopulation(size, climate, race) - maxPopulation(size, climate);
    }

    /** Ступень размера планеты: у крошечной 1, у огромной 5 — по ней считают подземные. */
    private Integer sizeSteps(PlanetSize size) {
        return size.ordinal() + 1;
    }

    /** Пригодность райской планеты: выше неё не поднимается ни одна раса. */
    private static final int MAX_HABITABILITY_PERCENT = 100;

    /**
     * Насколько мокрый мир пригоднее для водной расы — п. 4.1.2, в процентах вместимости.
     * <p>
     * Таблица MOO II, переложенная на здешнюю шкалу пригодности: тундра и болото
     * становятся земными мирами, океан и земной мир — райскими. Сухие миры водной расе
     * не дают ничего, и в таблице их нет.
     */
    private static final Map<PlanetClimate, Integer> AQUATIC_HABITABILITY = Map.of(
            PlanetClimate.TUNDRA, 90,
            PlanetClimate.SWAMP, 90,
            PlanetClimate.OCEAN, 100,
            PlanetClimate.TERRAN, 100);

    /** Мокрые миры, с которых водная раса снимает лишнюю единицу еды — п. 4.1.2. */
    private static final Set<PlanetClimate> AQUATIC_FOOD_CLIMATES =
            EnumSet.of(PlanetClimate.TUNDRA, PlanetClimate.OCEAN, PlanetClimate.TERRAN);

    /**
     * Производство планеты без дополнительных технологий: количество рабочих,
     * умноженное на выработку одного рабочего для данного богатства минералами.
     */
    public Integer production(MineralRichness minerals, Integer workers) {
        return production(minerals, workers, BuildingEffects.NONE);
    }

    /**
     * Производство колонии со зданиями: они прибавляют и к выработке рабочего, и сверх неё.
     * <p>
     * Населения этот счёт не знает, поэтому переработка рециклотрона (она идёт с жителя,
     * а не с рабочего) в него не входит: колонии считаются соседним методом, а этот
     * остаётся для тех, у кого населения под рукой нет.
     */
    public Integer production(MineralRichness minerals, Integer workers, BuildingEffects effects) {
        return production(minerals, workers, 0, effects);
    }

    /**
     * Производство колонии со зданиями и её населением — п. 10.
     * <p>
     * Население нужно рециклотрону: он поднимает переработкой мусора по единице
     * производства <b>с каждого жителя</b>, а не с рабочего, — единственное здание
     * MOO II, которому не важно, чем житель занят. Идёт оно мимо процента строя, как и
     * прочие прибавки зданий: процент в MOO II достаётся работникам, а не постройкам.
     */
    public Integer production(MineralRichness minerals, Integer workers, Integer population,
                              BuildingEffects effects) {
        return percent((minerals.getProductionPerWorker() + effects.productionPerWorker()) * workers,
                effects.productionPercent())
                + cleanProduction(population, effects);
    }

    /**
     * Производство, которое не пачкает планету, — п. 10: твёрдые прибавки зданий (завод,
     * робошахтёры, глубинный рудник) и переработка рециклотрона.
     * <p>
     * Правило оригинала: чадят работники, а не механизмы. Из грязного производства эта
     * часть вычитается до всякого счёта — и у рециклотрона это половина смысла: «this
     * production does not count against pollution levels».
     */
    public Integer cleanProduction(Integer population, BuildingEffects effects) {
        return effects.productionFlat()
                + effects.productionPerColonist() * Math.max(0, population);
    }

    /**
     * Процент правительства и расы — п. 7: он берётся от выработки всех работников разом,
     * а не от выработки одного.
     * <p>
     * Так приходится считать потому, что выработка целая: «плохие фермеры» MOO II роняют
     * фермера с двух единиц до полутора, и на одном фермере полторы единицы записать
     * нечем. От суммы же четверо фермеров дают ровно шесть — как в оригинале. Постройки
     * под процент не попадают: и в MOO II гидропонная ферма приносит своё независимо
     * от строя, а штраф феодализма не трогает науку лабораторий.
     */
    private Integer percent(Integer amount, Integer bonusPercent) {
        return bonusPercent == 0 ? amount : (int) ((long) amount * (100 + bonusPercent) / 100);
    }

    /**
     * Очки исследований планеты за ход — п. 9: число учёных на выработку одного учёного.
     * <p>
     * Богатство минералами и климат на исследования не влияют, в отличие от производства
     * и еды: в MOO II учёный даёт одни и те же очки на любой планете.
     */
    public Integer research(Integer scientists) {
        return research(scientists, BuildingEffects.NONE);
    }

    /** Очки исследований колонии со зданиями — лаборатории дают очки и сами по себе. */
    public Integer research(Integer scientists, BuildingEffects effects) {
        return percent((RESEARCH_POINTS_PER_SCIENTIST + effects.researchPerScientist()) * scientists,
                effects.researchPercent())
                + effects.researchFlat();
    }

    /** Выработка одного учёного — она одна на всю галактику, планета на неё не влияет. */
    public Integer researchPerScientist() {
        return RESEARCH_POINTS_PER_SCIENTIST;
    }

    /** Еда планеты за ход: число фермеров на выработку фермера для этого климата. */
    public Integer food(PlanetClimate climate, Integer farmers) {
        return food(climate, farmers, BuildingEffects.NONE);
    }

    /** Еда колонии со зданиями — фермы дают еду и без единого фермера. */
    public Integer food(PlanetClimate climate, Integer farmers, BuildingEffects effects) {
        return food(climate, farmers, effects, RaceEffects.NONE);
    }

    /**
     * Еда колонии со зданиями и расой — п. 7: водная раса кормится с мокрых миров лучше
     * прочих, и это прибавка к климату, а не к постройкам.
     */
    public Integer food(PlanetClimate climate, Integer farmers, BuildingEffects effects, RaceEffects race) {
        return percent((foodPerFarmer(climate, race) + effects.foodPerFarmer()) * farmers,
                effects.foodPercent())
                + effects.foodFlat();
    }

    /**
     * Еда с фермера на этом климате для этой расы — п. 4.1.2.
     * <p>
     * Водная раса снимает с мокрого мира лишнюю единицу: в MOO II океан для неё райская
     * планета, а тундра земная. Болоту прибавки нет и там: водной расе оно добавляет
     * места, а не еды.
     */
    public Integer foodPerFarmer(PlanetClimate climate, RaceEffects race) {
        Integer base = climate.getFoodPerFarmer();
        return Boolean.TRUE.equals(race.aquatic()) && AQUATIC_FOOD_CLIMATES.contains(climate)
                ? base + 1
                : base;
    }

    /** Сколько еды съедает колония за ход: по единице на жителя, как в MOO II. */
    public Integer foodConsumption(Integer population) {
        return foodConsumption(population, RaceEffects.NONE);
    }

    /**
     * Сколько еды съедает колония за ход с оглядкой на расу — п. 7.
     * <p>
     * Киборг съедает половину порции, литовор не ест вовсе. Остаток округляется вверх,
     * как в MOO II: пятеро киборгов съедают три единицы, а не две с половиной.
     */
    public Integer foodConsumption(Integer population, RaceEffects race) {
        int normal = FOOD_PER_COLONIST * population;
        int share = 100 + race.foodConsumptionPercent();
        if (share <= 0) {
            return 0;
        }
        return (normal * share + 99) / 100;
    }

    /**
     * Сколько производства проедает колония за ход — п. 7: киборги питаются рудой наравне
     * с едой, и эта доля уходит до стройки. Округление вверх то же, что у еды.
     */
    public Integer productionConsumption(Integer population, RaceEffects race) {
        int share = race.productionConsumptionPercent();
        return share == 0 ? 0 : (population * share + 99) / 100;
    }

    /**
     * Распределение населения по умолчанию: сначала колония кормит себя, остаток делится
     * между производством и наукой поровну, с перевесом в пользу производства.
     * <p>
     * Так встаёт только что заселённая колония и так же выправляется распределение, если
     * население изменилось. Дальше распределение — дело игрока.
     */
    public PopulationJobs defaultJobs(PlanetClimate climate, Integer population) {
        if (population <= 0) {
            return PopulationJobs.NONE;
        }

        // На безжизненном климате фермер бесполезен: еду такая колония не производит вовсе.
        int farmers = farmersToFeed(climate, population);
        int rest = population - farmers;
        int workers = (rest + 1) / 2;
        return new PopulationJobs(farmers, workers, rest - workers);
    }

    /**
     * Приводит распределение к населению планеты.
     * <p>
     * Пустое распределение при живой колонии — только что заселённая планета или слепок,
     * снятый до появления занятий: такая колония встаёт по умолчанию. Во всех остальных
     * случаях распределение уже расставил игрок, и население просто изменилось, поэтому
     * оно не пересобирается заново, а подправляется — см. {@link #absorb}.
     */
    public PopulationJobs normalize(PlanetClimate climate, Integer population, PopulationJobs jobs) {
        if (jobs.total().equals(population)) {
            return jobs;
        }
        return jobs.total() == 0 ? defaultJobs(climate, population) : absorb(climate, population, jobs);
    }

    /**
     * Подгоняет распределение под изменившееся население, сохраняя расстановку игрока.
     * <p>
     * Новые жители сначала идут в фермеры, пока колония не кормит себя, остальные —
     * в рабочие. Убыль снимается с самого многочисленного занятия, чтобы просадка
     * распределялась равномерно.
     */
    public PopulationJobs absorb(PlanetClimate climate, Integer population, PopulationJobs jobs) {
        int delta = population - jobs.total();
        if (delta == 0) {
            return jobs;
        }
        if (delta > 0) {
            int missingFarmers = Math.max(0, farmersToFeed(climate, population) - jobs.farmers());
            int addedFarmers = Math.min(delta, missingFarmers);
            return new PopulationJobs(
                    jobs.farmers() + addedFarmers,
                    jobs.workers() + delta - addedFarmers,
                    jobs.scientists());
        }

        int[] counts = {jobs.farmers(), jobs.workers(), jobs.scientists()};
        for (int i = 0; i < -delta; i++) {
            int largest = 0;
            for (int job = 1; job < counts.length; job++) {
                if (counts[job] > counts[largest]) {
                    largest = job;
                }
            }
            counts[largest] = Math.max(0, counts[largest] - 1);
        }
        return new PopulationJobs(counts[0], counts[1], counts[2]);
    }

    /**
     * Базовый прирост населения за ход в тысячах жителей — формула MOO II:
     * {@code ROUNDDOWN(SQRT(2000 * жители * свободное_место / вместимость))}.
     * <p>
     * Прирост наибольший на половине вместимости и одинаково мал у только что заселённой
     * и у почти полной колонии: в первом случае мало кому размножаться, во втором некуда.
     * У заполненной колонии прирост нулевой.
     */
    public Integer basicIncrementK(Integer population, Integer maxPopulation) {
        if (maxPopulation <= 0 || population <= 0) {
            return 0;
        }
        int freeSpace = Math.max(0, maxPopulation - population);
        return (int) Math.sqrt((double) GROWTH_FACTOR * population * freeSpace / maxPopulation);
    }

    /**
     * Прирост населения колонии за ход в тысячах жителей — формула MOO II целиком.
     * <p>
     * Базовый прирост умножается на бонусы к рождаемости, а нехватка еды вычитается
     * уже из результата: голодная колония теряет жителей, даже если растёт быстро.
     *
     * @param bonusPercent прибавка к рождаемости в процентах: медицинские технологии,
     *                     расовые особенности, жильё в очереди построек
     * @param foodLack     сколько единиц еды колонии не хватило в этот ход
     */
    public Integer growthK(Integer population, Integer maxPopulation, Integer bonusPercent, Integer foodLack) {
        return growthK(population, maxPopulation, bonusPercent, 0, foodLack);
    }

    /**
     * Прирост с постоянной прибавкой от зданий: центр клонирования добавляет свои тысячи
     * жителей после умножения на проценты, как в MOO II.
     */
    public Integer growthK(Integer population,
                           Integer maxPopulation,
                           Integer bonusPercent,
                           Integer flatGrowthK,
                           Integer foodLack) {
        int increment = (int) ((long) basicIncrementK(population, maxPopulation) * (100 + bonusPercent) / 100);
        return increment + flatGrowthK - STARVATION_PENALTY_K * foodLack;
    }

    /**
     * Прибавка к рождаемости от домов — п. 10: всё производство колонии уходит в жильё,
     * и чем его больше на одного жителя, тем быстрее колония растёт.
     * Формула MOO II: {@code ROUNDDOWN(производство * 40 / жители)} процентов.
     */
    public Integer housingBonusPercent(Integer production, Integer population) {
        return population <= 0 ? 0 : production * HOUSING_FACTOR / population;
    }

    /**
     * Сколько грузовиков занимает перевозка жителей — п. 4.1.1: по одному на единицу
     * населения. Занятые рейсом грузовики еду не возят, пока не вернутся.
     */
    public Integer freightersForTransfer(Integer population) {
        return Math.max(0, population) * FREIGHTERS_PER_POPULATION;
    }

    /** Сколько еды империя способна развезти за ход своим грузовым флотом — п. 4.1.1. */
    public Integer freightCapacity(Integer freighters) {
        return Math.max(0, freighters) * FOOD_PER_FREIGHTER;
    }

    /**
     * Делит подвоз между голодающими колониями — п. 4.1.1.
     * <p>
     * Возится излишек соседей, и не больше, чем поднимает грузовой флот. Сперва кормят
     * тех, кому не хватает меньше всего: так спасённых колоний выходит больше, а
     * оставшийся голод — только там, где еды не хватило бы всё равно.
     *
     * @param pool     сколько еды удастся развезти: меньшее из излишка и вместимости флота
     * @param deficits нехватка по колониям в том же порядке, в каком нужен ответ
     * @return сколько еды достанется каждой колонии
     */
    public List<Integer> deliverFood(Integer pool, List<Integer> deficits) {
        List<Integer> delivered = new ArrayList<>(Collections.nCopies(deficits.size(), 0));
        int left = Math.max(0, pool);
        if (left == 0) {
            return delivered;
        }

        List<Integer> order = IntStream.range(0, deficits.size())
                .boxed()
                .sorted(Comparator.comparing(deficits::get))
                .toList();
        for (Integer index : order) {
            int need = Math.max(0, deficits.get(index));
            if (need == 0 || left == 0) {
                continue;
            }
            int give = Math.min(need, left);
            delivered.set(index, give);
            left -= give;
        }
        return delivered;
    }

    /**
     * Кредиты от продажи произведённого — п. 10: два кредита за единицу, как в MOO II
     * продаются и товары, и излишки еды.
     */
    public Integer creditsFor(Integer units) {
        return Math.max(0, units) / UNITS_PER_CREDIT;
    }

    /**
     * Во что обойдётся выкуп недостроенного — п. 10.
     * <p>
     * Платят за <b>недостающие</b> единицы производства, и цена зависит от того, много ли
     * уже вложено: половина стройки и больше — два кредита за единицу, меньше половины —
     * четыре. Числа из оригинала: «buying something that is at least 50% complete costs
     * 2 BC per outstanding PP... buying things you've just ordered costs 4 BC per PP»
     * (StrategyWiki, Speeding up production).
     * <p>
     * <b>Ступенька, а не наклон, — реконструкция.</b> Оригинал говорит, что до половины
     * «ratio much worse», не называя промежуточных значений, зато все четыре его примера
     * с числами сходятся именно на ступеньке: автоматический завод (60) после первого
     * хода стройки — «около 220 кредитов» (4 × 55), он же парой ходов позже — «меньше
     * 200» (4 × 50), колониальный корабль (500) и колониальная база (200) на половине —
     * 500 и 200 кредитов (2 × 250 и 2 × 100). Появится точная кривая — менять здесь
     * одну строку.
     *
     * @param cost     полная цена проекта в единицах производства
     * @param invested сколько единиц уже вложено
     */
    public Integer buyCost(Integer cost, Integer invested) {
        int remaining = Math.max(0, cost - Math.max(0, invested));
        int rate = invested * 2 >= cost ? BUY_RATE_HALF_BUILT : BUY_RATE_FRESH;
        return remaining * rate;
    }

    /**
     * Сколько производства колония тратит на уборку за собой — п. 10.
     * <p>
     * Правило MOO II и его же порядок действий (StrategyWiki, Calculations): из добытого
     * вычитается чистая его часть — заводы, рудники и рециклотрон чадом не считаются, —
     * остаток
     * делится очистными сооружениями, из него вычитается терпимость планеты, и половина
     * того, что осталось, уходит на уборку; дробь округляется вверх, отрицательная грязь
     * не считается вовсе. Пример оригинала: производство 100 при заводе (+5) и
     * робошахтёрах (+10) на средней планете (терпимость 6) с переработчиком отходов
     * (делитель 2) даёт 19 единиц уборки и 81 полезного производства.
     * <p>
     * Считается всё в целых числах одной дробью: {@code (грязь − терпимость × делитель) /
     * (2 × делитель)}. Иначе округлять пришлось бы дважды, и уже пример оригинала сошёлся
     * бы не в 19, а в 18 — в MOO II дробь живёт до самого конца.
     *
     * @param production  добыча колонии за ход, до уборки
     * @param clean       та её часть, что не пачкает: см. {@link #cleanProduction}
     * @param tolerance   сколько единиц планета терпит без грязи (с учётом зданий)
     * @param divisor     во сколько раз чище считается производство: 1, 2, 4 или 8
     */
    public Integer pollution(Integer production, Integer clean, Integer tolerance, Integer divisor) {
        int dirty = Math.max(0, production - Math.max(0, clean));
        int over = dirty - Math.max(0, tolerance) * divisor;
        if (over <= 0) {
            return 0;
        }
        int scale = 2 * divisor;
        // Уборка не бывает дороже самой добычи: убирать нечего, если добывать нечего.
        return Math.min(production, (over + scale - 1) / scale);
    }

    /**
     * Сколько казна получит за проданную постройку — п. 10.
     * <p>
     * Половина цены здания в кредитах. Число сверено с оригиналом: в MOO II казармы стоят
     * 60 единиц производства, а проданные на первом ходу «дают 30 кредитов» — ровно
     * половину (StrategyWiki, Money matters, «Scrapping unwanted buildings»).
     */
    public Integer sellValue(Integer buildingCost) {
        return Math.max(0, buildingCost) / SELL_DIVISOR;
    }

    /**
     * Налоговый доход колонии: по кредиту с жителя, поднятый зданиями вроде космопорта
     * и фондовой биржи. Проценты считаются от налога, а не от всего дохода колонии.
     */
    public Integer taxIncome(Integer population, Integer incomePercent) {
        Integer tax = TAX_PER_COLONIST * population;
        return tax + tax * incomePercent / 100;
    }

    /**
     * Прибавка к рождаемости от медицинских технологий MOO II — п. 9: Universal Antidote
     * даёт 50%, Microbiotics 25%, и вместе они не складываются — берётся лучшая.
     */
    public Integer medicineBonusPercent(Collection<String> technologyCodes) {
        if (technologyCodes.contains(UNIVERSAL_ANTIDOTE)) {
            return UNIVERSAL_ANTIDOTE_PERCENT;
        }
        return technologyCodes.contains(MICROBIOTICS) ? MICROBIOTICS_PERCENT : 0;
    }

    /** Сколько фермеров нужно, чтобы прокормить колонию; на безжизненном климате — ни одного. */
    private Integer farmersToFeed(PlanetClimate climate, Integer population) {
        Integer foodPerFarmer = climate.getFoodPerFarmer();
        if (foodPerFarmer == 0) {
            return 0;
        }
        return Math.min(population, (foodConsumption(population) + foodPerFarmer - 1) / foodPerFarmer);
    }

    /**
     * Расстановка по умолчанию <b>с оглядкой на расу и здания</b> — п. 4.1, п. 7.
     * <p>
     * <b>Зачем понадобилась.</b> Империя ИИ расставляла жителей так, будто раса у неё
     * средняя: фермеров считал {@link #defaultJobs(PlanetClimate, Integer)}, который знает
     * только климат. Раса с плохими фермерами получала на четверть меньше нужного и
     * голодала ВЕЧНО — добавить фермера было некому. Измерено подсадкой (журнал, п. 3.66):
     * сторона за −8 очков мерилась силой −18,61, то есть справедливой ценой около −40 при
     * бюджете расы 20, и слагаемые были не про еду вовсе — деньги −8,76, наука −6,77.
     * Зеркально недодавали и сильные ступени: при лишней единице с фермера ИИ держал то же
     * их число и копил излишек, вместо того чтобы отпустить людей к станку, — «хорошие
     * фермеры» за пять очков мерились силой +0,49.
     * <p>
     * <b>Считается ПОДБОРОМ, а не делением.</b> Еду колонии считает
     * {@link #food(PlanetClimate, Integer, BuildingEffects, RaceEffects)} — с процентами
     * зданий и надбавкой ферм, — и обращать эту формулу значило бы завести второе правило,
     * которое однажды разъедется с первым. Население колонии измеряется десятками, поэтому
     * перебор по числу фермеров и дешевле, и точнее.
     */
    public PopulationJobs defaultJobs(PlanetClimate climate, Integer population,
                                      BuildingEffects effects, RaceEffects race) {
        if (population <= 0) {
            return PopulationJobs.NONE;
        }
        int farmers = farmersToFeed(climate, population, effects, race);
        int rest = population - farmers;
        int workers = (rest + 1) / 2;
        return new PopulationJobs(farmers, workers, rest - workers);
    }

    /**
     * Сколько фермеров прокормят колонию этой расы на этом климате и с этими зданиями.
     * <p>
     * Ноль у литовора (он не ест вовсе) и на безжизненном климате, где фермер не даёт
     * ничего. Если не хватает и всех жителей разом — к грядкам идут все: остальное привезёт
     * грузовой флот (п. 4.1.1), и это то же поведение, что было у расчёта по климату.
     */
    private Integer farmersToFeed(PlanetClimate climate, Integer population,
                                  BuildingEffects effects, RaceEffects race) {
        if (foodPerFarmer(climate, race) + effects.foodPerFarmer() <= 0) {
            return 0;
        }
        Integer needed = foodConsumption(population, race);
        for (int farmers = 0; farmers < population; farmers++) {
            if (food(climate, farmers, effects, race) >= needed) {
                return farmers;
            }
        }
        return population;
    }

    /**
     * Сколько фермеров держать на каждой колонии ИМПЕРИИ — п. 4.1.1 (журнал, п. 3.100).
     * <p>
     * <b>Зачем понадобилось.</b> До этого каждая колония кормила себя сама
     * ({@link #farmersToFeed}), то есть империя не специализировалась никогда: хороший
     * кормовой мир не кормил плохой, хотя игра это умеет — излишек развозится грузовым
     * флотом ({@link #deliverFood}), и человек так и играет. Из-за самопрокорма кормовая
     * сторона расы прибавляла еду НА КАЖДОЙ колонии сразу, и вся кормовая ось оказалась
     * переоценена: «хорошие фермеры» за семь очков мерились восемнадцатью, литоворы за
     * тринадцать — пятьюдесятью.
     * <p>
     * <b>Правило.</b> В поле идут те, у кого еда с фермера выше, — по убыванию отдачи,
     * пока империя не накормлена; остальные к станку, а едят они с подвоза. Ограничение
     * одно, и оно настоящее: развезти можно не больше, чем поднимает грузовой флот.
     * <p>
     * <b>Как ищется ответ.</b> Перебором по числу колоний, которым велено кормиться
     * самостоятельно, от нуля (полная специализация) до всех (прежнее поведение). Чем
     * больше таких колоний, тем больше фермеров и тем меньше возить, поэтому ПЕРВЫЙ
     * выполнимый план и есть лучший — искать дальше незачем. Самопрокорм выполним всегда:
     * он и есть то, что было до правки, — поэтому империя без единого грузовика получает
     * ровно прежнюю расстановку, и правка эта включается сама, как только флот появится.
     *
     * @param foodByFarmers еда каждой колонии при 0, 1, 2… фермерах — таблицей, а не
     *                      формулой: обращать {@link #food} значило бы завести второе
     *                      правило, которое однажды разъедется с первым
     * @param needs         сколько съедает каждая колония
     * @param freightCapacity сколько еды поднимает грузовой флот империи за ход
     * @return число фермеров на каждой колонии, в том же порядке
     */
    public List<Integer> farmersAcrossEmpire(List<List<Integer>> foodByFarmers,
                                             List<Integer> needs,
                                             Integer freightCapacity) {
        if (needs.isEmpty()) {
            return List.of();
        }
        List<Integer> order = feedersFirst(foodByFarmers);
        List<Integer> selfFedPlan = farmPlan(foodByFarmers, needs, order, needs.size());
        // Столько не развезти и сегодня: колония на безжизненном климате фермера не
        // прокормит ни одного. Ниже этого опускать нельзя — иначе прежнее поведение
        // объявлялось бы невыполнимым, и ответа не нашлось бы вовсе.
        Integer floor = freightNeeded(foodByFarmers, needs, selfFedPlan);
        Integer budget = Math.max(freightCapacity, floor);

        for (int selfFed = 0; selfFed < needs.size(); selfFed++) {
            List<Integer> plan = farmPlan(foodByFarmers, needs, order, selfFed);
            if (freightNeeded(foodByFarmers, needs, plan) <= budget) {
                return plan;
            }
        }
        return selfFedPlan;
    }

    /**
     * Сколько еды пришлось бы развозить при ПОЛНОЙ специализации — п. 4.1.1.
     * <p>
     * Столько грузового флота империи и нужно: по этому числу ИИ решает, строить ли
     * грузовик (журнал, п. 3.100). Считается тем же планом, что и расстановка, —
     * второго свода правил у подвоза нет.
     */
    public Integer freightForSpecialisation(List<List<Integer>> foodByFarmers,
                                            List<Integer> needs) {
        if (needs.isEmpty()) {
            return 0;
        }
        List<Integer> order = feedersFirst(foodByFarmers);
        return freightNeeded(foodByFarmers, needs, farmPlan(foodByFarmers, needs, order, 0));
    }

    /** Сколько грузовиков поднимут столько еды — п. 4.1.1, с округлением вверх. */
    public Integer freightersForFood(Integer food) {
        return Math.max(0, food + FOOD_PER_FREIGHTER - 1) / FOOD_PER_FREIGHTER;
    }

    /**
     * Порядок колоний по отдаче первого фермера, от лучшей к худшей.
     * <p>
     * При равной отдаче — по месту в списке: партия обязана повторяться, а «первый
     * подходящий из равных» без явного порядка расходится от прогона к прогону.
     */
    private List<Integer> feedersFirst(List<List<Integer>> foodByFarmers) {
        return IntStream.range(0, foodByFarmers.size())
                .boxed()
                .sorted(Comparator.<Integer, Integer>comparing(index -> -firstFarmer(foodByFarmers.get(index)))
                        .thenComparing(index -> index))
                .toList();
    }

    /** Прибавка первого фермера: ею и меряется, кто в империи кормилица. */
    private Integer firstFarmer(List<Integer> table) {
        return table.size() > 1 ? table.get(1) - table.get(0) : 0;
    }

    /**
     * План расстановки: {@code selfFed} худших колоний кормятся сами, остальные набираются
     * фермерами по убыванию отдачи, пока империя не накормлена.
     */
    private List<Integer> farmPlan(List<List<Integer>> foodByFarmers, List<Integer> needs,
                                   List<Integer> order, Integer selfFed) {
        int count = needs.size();
        Integer[] farmers = new Integer[count];
        int have = 0;
        int total = 0;
        for (int index = 0; index < count; index++) {
            total += needs.get(index);
        }
        for (int rank = 0; rank < count; rank++) {
            int index = order.get(rank);
            farmers[index] = rank >= count - selfFed
                    ? feedSelf(foodByFarmers.get(index), needs.get(index))
                    : 0;
            have += foodByFarmers.get(index).get(farmers[index]);
        }
        for (int rank = 0; rank < count - selfFed && have < total; rank++) {
            int index = order.get(rank);
            List<Integer> table = foodByFarmers.get(index);
            while (have < total && farmers[index] + 1 < table.size()) {
                have += table.get(farmers[index] + 1) - table.get(farmers[index]);
                farmers[index] = farmers[index] + 1;
            }
        }
        return List.of(farmers);
    }

    /** Наименьшее число фермеров, которым колония прокормит себя; не хватит всех — все. */
    private Integer feedSelf(List<Integer> table, Integer need) {
        for (int farmers = 0; farmers < table.size(); farmers++) {
            if (table.get(farmers) >= need) {
                return farmers;
            }
        }
        return table.size() - 1;
    }

    /** Сколько еды этот план просит развезти: сумма нехваток по колониям. */
    private Integer freightNeeded(List<List<Integer>> foodByFarmers, List<Integer> needs,
                                  List<Integer> plan) {
        int moved = 0;
        for (int index = 0; index < needs.size(); index++) {
            moved += Math.max(0, needs.get(index) - foodByFarmers.get(index).get(plan.get(index)));
        }
        return moved;
    }
}
