package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Оценка цен сторон расы — п. 2.2 этапа 2 (`balance-metrics-works.txt`).
 * <p>
 * Проверяется на выдуманных партиях с ЗАРАНЕЕ ИЗВЕСТНЫМИ весами: если регрессия не
 * находит того, что в данные заложено, ей нельзя верить и на настоящих. Настоящий прогон
 * такой проверки не даёт — там правильного ответа никто не знает.
 */
class BalanceVerdictRulesTest {

    private final BalanceVerdictRules rules = new BalanceVerdictRules();

    /** Цены: две стороны по три очка, одна за шесть и бесплатная опора. */
    private Map<String, Integer> prices() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("сильная", 6);
        prices.put("средняя", 3);
        prices.put("слабая", 3);
        // Бесплатная сторона в каждой сборке — та самая опора, относительно которой
        // только и определены веса остальных.
        prices.put("опора", 0);
        return prices;
    }

    /**
     * Партии, в которых сила сторон известна: сильная даёт +6 п.п., средняя +3, слабая 0.
     * Долю добираем до ста процентов шумом, как в настоящей партии.
     */
    private List<BalanceVerdictRules.Empire> world(long seed) {
        Map<String, Double> truth = Map.of("сильная", 6.0, "средняя", 3.0, "слабая", 0.0,
                "опора", 0.0);
        List<String> codes = List.of("сильная", "средняя", "слабая");
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();

        int empiresInGame = 4;
        for (int game = 0; game < 200; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                // Четверть сборок — одна опора и ничего больше: без них «сторона взята»
                // не с чем сравнивать, и уровень весов остаётся неопределённым.
                Set<String> traits = random.nextInt(4) == 0
                        ? Set.of("опора")
                        : Set.of("опора", codes.get(random.nextInt(codes.size())));
                double gain = traits.stream().mapToDouble(truth::get).sum();
                double share = 100.0 / empiresInGame + gain + random.nextGaussian() * 2.0;
                // Науки и разведки в этом мире нет вовсе — так выглядят и замеры прогонов,
                // сыгранных до того, как прибор получил второе и третье мерило.
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, share, null, null, null, null, null, null, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    @Test
    @DisplayName("Регрессия находит заложенные веса сторон")
    void findsTheWeightsPutIntoTheData() {
        BalanceVerdictRules.Assessment assessment = rules.assess(world(42), prices());

        Map<String, BalanceVerdictRules.Judgement> byCode = new LinkedHashMap<>();
        assessment.judgements().forEach(one -> byCode.put(one.code(), one));

        // Веса восстанавливаются с точностью до общего сдвига: доля — величина
        // относительная, и регрессия знает только разницы между сторонами.
        double strong = byCode.get("сильная").strength();
        double middle = byCode.get("средняя").strength();
        double weak = byCode.get("слабая").strength();

        assertThat(strong - weak).isCloseTo(6.0, org.assertj.core.data.Offset.offset(1.0));
        assertThat(middle - weak).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("Приговор выносится цене, а не силе")
    void judgesThePriceAndNotTheStrength() {
        BalanceVerdictRules.Assessment assessment = rules.assess(world(7), prices());
        Map<String, BalanceVerdictRules.Judgement> byCode = new LinkedHashMap<>();
        assessment.judgements().forEach(one -> byCode.put(one.code(), one));

        // «Слабая» и «средняя» стоят одинаково — три очка, — но дают разное. Значит,
        // одинаковыми их цены быть не могут, и приговоры обязаны разойтись.
        assertThat(byCode.get("слабая").verdict())
                .isNotEqualTo(byCode.get("средняя").verdict());
        assertThat(byCode.get("слабая").verdict())
                .isEqualTo(BalanceVerdictRules.Verdict.TOO_EXPENSIVE);
    }

    @Test
    @DisplayName("Цена двигается долей пути, а не в измеренную точку")
    void recommendationIsDamped() {
        BalanceVerdictRules.Assessment assessment = rules.assess(world(1234), prices());
        BalanceVerdictRules.Judgement weak = assessment.judgements().stream()
                .filter(one -> one.code().equals("слабая"))
                .findFirst()
                .orElseThrow();

        if (weak.recommendedPrice() == null) {
            return;
        }
        // Шаг идёт в сторону справедливой цены, но не доходит до неё: ценность зависит от
        // цены, и прыжок в точку раскачивал бы цены вместо того, чтобы их успокаивать.
        assertThat(weak.recommendedPrice()).isLessThan(weak.price());
        assertThat((double) weak.recommendedPrice()).isGreaterThanOrEqualTo(weak.fairPrice());
    }

    /**
     * Мир, в котором одна пара сторон стоит больше суммы своих половин — п. 2.5 этапа 2.
     * <p>
     * «Голод» сам по себе вреден, «завод» сам по себе полезен, а вместе они дают ещё
     * шесть пунктов сверх того: это и есть та связка, ради которой игрок покупает вредную
     * сторону. «Прочее» полезно всегда и ни с кем не связано — на нём видно, что прибор
     * не находит связок там, где их нет. Стороны берутся независимо друг от друга, иначе
     * связку не отличить от того, что они просто ходят парой.
     */
    private List<BalanceVerdictRules.Empire> pairedWorld(long seed) {
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();
        int empiresInGame = 4;
        for (int game = 0; game < 300; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                Set<String> traits = new java.util.LinkedHashSet<>();
                traits.add("опора");
                if (random.nextDouble() < 0.4) {
                    traits.add("голод");
                }
                if (random.nextDouble() < 0.4) {
                    traits.add("завод");
                }
                if (random.nextDouble() < 0.4) {
                    traits.add("прочее");
                }
                double gain = (traits.contains("голод") ? -3 : 0)
                        + (traits.contains("завод") ? 3 : 0)
                        + (traits.contains("прочее") ? 2 : 0)
                        + (traits.contains("голод") && traits.contains("завод") ? 6 : 0);
                double share = 100.0 / empiresInGame + gain + random.nextGaussian() * 2.0;
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, share, null, null, null, null, null, null, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    private Map<String, Integer> pairedPrices() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("голод", -3);
        prices.put("завод", 4);
        prices.put("прочее", 3);
        prices.put("опора", 0);
        return prices;
    }

    @Test
    @DisplayName("Связка находится там, где она есть, и не находится там, где её нет")
    void findsTheSynergyPutIntoTheData() {
        BalanceVerdictRules.Assessment assessment =
                rules.assess(pairedWorld(21), pairedPrices());

        BalanceVerdictRules.Synergy pair = assessment.synergies().stream()
                .filter(one -> one.members().containsAll(List.of("голод", "завод")))
                .findFirst()
                .orElse(null);

        assertThat(pair).isNotNull();
        // Заложено шесть, а прибор называет около четырёх: гребневая поправка тянет веса
        // к нулю, и столбец связки, у которого носителей меньше всех, сжимается сильнее
        // прочих. Это осознанная осторожность прибора, а не промах: связка объявляется
        // слабее, чем она есть, и никогда наоборот.
        assertThat(pair.extra()).isCloseTo(6.0, org.assertj.core.data.Offset.offset(2.0));
        assertThat(pair.extra()).isLessThan(6.0);
        assertThat(pair.verdict()).isEqualTo(BalanceVerdictRules.Pairing.SYNERGY);
        // Сама по себе вредная сторона остаётся вредной, а в связке — сильнее суммы:
        // ровно то, чего нельзя увидеть, взвешивая стороны поодиночке.
        assertThat(pair.together()).isGreaterThan(0.0);

        // «Прочее» ни с кем не связано — прибор обязан это сказать.
        assessment.synergies().stream()
                .filter(one -> one.members().contains("прочее"))
                .forEach(one -> assertThat(one.verdict())
                        .isEqualTo(BalanceVerdictRules.Pairing.PLAIN));
    }

    @Test
    @DisplayName("Связка не крадёт вес у самих сторон: приговор цене выносится без неё")
    void judgementsStayFreeOfTheInteraction() {
        BalanceVerdictRules.Assessment assessment =
                rules.assess(pairedWorld(99), pairedPrices());
        Map<String, BalanceVerdictRules.Judgement> byCode = new LinkedHashMap<>();
        assessment.judgements().forEach(one -> byCode.put(one.code(), one));

        // Цену игрок платит за сторону, в какой бы сборке она ни оказалась, поэтому в
        // приговоре «голод» стоит со средней своей силой по всем сборкам — а она у него,
        // с учётом связки, уже положительная (-3 + 0,4 * 6).
        assertThat(byCode.get("голод").strength())
                .isCloseTo(-0.6, org.assertj.core.data.Offset.offset(1.0));
    }

    /**
     * Мир, где одна сторона работает ТОЛЬКО в науке — п. 2.12 этапа 2.
     * <p>
     * «Учёные» не двигают выработку ни на пункт и дают +6 п.п. доли науки; «станок» —
     * наоборот. Пока мерило было одно, первая сторона мерилась нулём, и цена ей выходила
     * любая. Разведки в этом мире нет вовсе — заодно видно, что пустое мерило прибор не
     * ломает.
     */
    private List<BalanceVerdictRules.Empire> twoYardsticksWorld(long seed) {
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();
        int empiresInGame = 4;
        for (int game = 0; game < 200; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                Set<String> traits = new java.util.LinkedHashSet<>();
                traits.add("опора");
                if (random.nextDouble() < 0.4) {
                    traits.add("учёные");
                }
                if (random.nextDouble() < 0.4) {
                    traits.add("станок");
                }
                double middle = 100.0 / empiresInGame;
                double made = middle + (traits.contains("станок") ? 6 : 0)
                        + random.nextGaussian() * 2.0;
                double learnt = middle + (traits.contains("учёные") ? 6 : 0)
                        + random.nextGaussian() * 2.0;
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, made, learnt, null, null, null, null, null, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    @Test
    @DisplayName("Сторона, работающая только в науке, мерилу выработки не видна — а прибору видна")
    void scienceOnlyTraitIsMeasured() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("учёные", 6);
        prices.put("станок", 6);
        prices.put("опора", 0);

        BalanceVerdictRules.Assessment assessment =
                rules.assess(twoYardsticksWorld(5), prices);
        Map<String, BalanceVerdictRules.Judgement> byCode = new LinkedHashMap<>();
        assessment.judgements().forEach(one -> byCode.put(one.code(), one));

        BalanceVerdictRules.Judgement scholars = byCode.get("учёные");
        BalanceVerdictRules.Judgement works = byCode.get("станок");

        // Каждая сторона сильна в СВОЁМ мериле и пуста в чужом.
        assertThat(scholars.research()).isGreaterThan(3.0);
        assertThat(scholars.production()).isLessThan(1.5);
        assertThat(works.production()).isGreaterThan(3.0);
        assertThat(works.research()).isLessThan(1.5);

        // А общая сила у них сходится: стоят они одинаково, и приговор обязан быть один.
        assertThat(scholars.strength()).isCloseTo(works.strength(),
                org.assertj.core.data.Offset.offset(1.5));
        assertThat(scholars.verdict()).isEqualTo(works.verdict());
    }

    @Test
    @DisplayName("Без наблюдений приговора нет — и это не отказ")
    void withoutDataThereIsNoVerdict() {
        BalanceVerdictRules.Assessment assessment = rules.assess(List.of(), prices());

        assertThat(assessment.measured()).isZero();
        assertThat(assessment.synergies()).isEmpty();
        assertThat(assessment.judgements())
                .allMatch(one -> one.verdict() == BalanceVerdictRules.Verdict.NOT_MEASURED);
    }

    /**
     * Мир, где расклад меняет ТРОЙКА, а не пара — п. 2.14 этапа 2.
     * <p>
     * Ни одна пара из трёх сторон не даёт ничего сверх своих половин, а все три вместе
     * дают +6. Это и есть замысел хозяина проекта про «киборги + антибонус к еде +
     * промышленники»: связка, которую парами не разглядеть. Стороны подсаживаются жребием
     * на каждую — восемь восьмых, — иначе тройку не отделить от её частей.
     */
    private List<BalanceVerdictRules.Empire> tripleWorld(long seed) {
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();
        int empiresInGame = 4;
        for (int game = 0; game < 400; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                Set<String> traits = new java.util.LinkedHashSet<>();
                traits.add("опора");
                for (String side : List.of("голод", "завод", "склад")) {
                    if (random.nextBoolean()) {
                        traits.add(side);
                    }
                }
                double gain = traits.containsAll(List.of("голод", "завод", "склад")) ? 6 : 0;
                double share = 100.0 / empiresInGame + gain + random.nextGaussian() * 2.0;
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, share, null, null, null, null, null, null, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    @Test
    @DisplayName("Тройка находится там, где её пары ничего не дают")
    void findsTheTriplePutIntoTheData() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("голод", -3);
        prices.put("завод", 4);
        prices.put("склад", 3);
        prices.put("опора", 0);
        List<String> planted = List.of("голод", "завод", "склад");

        BalanceVerdictRules.Assessment assessment =
                rules.assess(tripleWorld(77), prices, planted);

        BalanceVerdictRules.Synergy triple = assessment.synergies().stream()
                .filter(one -> one.members().size() == 3)
                .findFirst()
                .orElse(null);
        assertThat(triple).isNotNull();
        assertThat(triple.verdict()).isEqualTo(BalanceVerdictRules.Pairing.SYNERGY);
        // Гребень занижает прибавку, как и у пары: связка объявляется слабее, чем она есть.
        assertThat(triple.extra()).isBetween(2.0, 6.0);

        // А пары внутри тройки обязаны остаться пустыми: весь вклад ушёл в тройку, и если
        // он «нашёлся» ещё и у пары — прибор приписывает связку не тому, кому надо.
        assessment.synergies().stream()
                .filter(one -> one.members().size() == 2 && planted.containsAll(one.members()))
                .forEach(one -> assertThat(one.verdict())
                        .isEqualTo(BalanceVerdictRules.Pairing.PLAIN));
    }

    @Test
    @DisplayName("Подсаженная связка не удваивается от того, что её назвали задом наперёд")
    void plantedComboIsNotCountedTwice() {
        // Стороны названы в обратном порядке к тому, в каком прибор перебирает пары. Пока
        // связка была списком, а не набором, та же пара попадала в модель ДВАЖДЫ, а два
        // одинаковых столбца гребень делит между собой пополам: связка вдвое сильнее
        // объявлялась вдвое слабее. Ровно это и случилось на первом прогоне с тройкой.
        BalanceVerdictRules.Assessment assessment =
                rules.assess(pairedWorld(21), pairedPrices(), List.of("завод", "голод"));

        List<BalanceVerdictRules.Synergy> both = assessment.synergies().stream()
                .filter(one -> one.members().containsAll(List.of("голод", "завод")))
                .toList();
        assertThat(both).hasSize(1);
        assertThat(both.get(0).extra()).isCloseTo(6.0, org.assertj.core.data.Offset.offset(2.0));

        // И ни одна связка не повторяется по составу — ни подсаженная, ни сложившаяся сама.
        assertThat(assessment.synergies().stream()
                .map(one -> Set.copyOf(one.members()))
                .distinct()
                .count())
                .isEqualTo(assessment.synergies().size());
    }

    /**
     * Мир, где сторона работает ТОЛЬКО в деньгах, и другая — только во флоте (этап 3).
     * <p>
     * Пока мерил было три, обе мерились нулём, и оракул сборок тут же собрал верхушку из
     * «продать всё, чего прибор не видит»: бедных, плохих канониров, плохих пилотов.
     */
    private List<BalanceVerdictRules.Empire> moneyAndFleetWorld(long seed) {
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();
        int empiresInGame = 4;
        for (int game = 0; game < 200; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                Set<String> traits = new java.util.LinkedHashSet<>();
                traits.add("опора");
                if (random.nextDouble() < 0.4) {
                    traits.add("казна");
                }
                if (random.nextDouble() < 0.4) {
                    traits.add("флот");
                }
                double middle = 100.0 / empiresInGame;
                double made = middle + random.nextGaussian() * 2.0;
                double earned = middle + (traits.contains("казна") ? 8 : 0)
                        + random.nextGaussian() * 2.0;
                double fleet = middle + (traits.contains("флот") ? 8 : 0)
                        + random.nextGaussian() * 2.0;
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, made, null, null, earned, fleet, null, null, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    @Test
    @DisplayName("Деньги и военная сила — мерила: сторона, работающая только в них, видна")
    void moneyAndFleetAreMeasured() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("казна", 5);
        prices.put("флот", 5);
        prices.put("опора", 0);

        BalanceVerdictRules.Assessment assessment =
                rules.assess(moneyAndFleetWorld(13), prices);
        Map<String, BalanceVerdictRules.Judgement> byCode = new LinkedHashMap<>();
        assessment.judgements().forEach(one -> byCode.put(one.code(), one));

        BalanceVerdictRules.Judgement money = byCode.get("казна");
        BalanceVerdictRules.Judgement fleet = byCode.get("флот");

        // Каждая сильна в СВОЁМ мериле и пуста в чужом и в выработке.
        assertThat(money.money()).isGreaterThan(4.0);
        assertThat(money.military()).isLessThan(1.5);
        assertThat(money.production()).isLessThan(1.5);
        assertThat(fleet.military()).isGreaterThan(4.0);
        assertThat(fleet.money()).isLessThan(1.5);

        // И обе попали в общую силу: весят они вполовину, но не нулём.
        assertThat(money.strength()).isGreaterThan(1.0);
        assertThat(fleet.strength()).isGreaterThan(1.0);
    }

    /**
     * Мир, где сторона даёт ШИРОТУ науки, не меняя её скорости, — этап 3.
     * <p>
     * Это и есть изобретательность MOO II: очки исследований те же, а изучено вдвое больше,
     * потому что уровень выдаётся весь. Пока мерила технологий не было, такая сторона
     * мерилась нулём — и мерилась им на настоящих данных: +0,13 при цене десять.
     */
    private List<BalanceVerdictRules.Empire> breadthWorld(long seed) {
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();
        int empiresInGame = 4;
        for (int game = 0; game < 200; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                Set<String> traits = new java.util.LinkedHashSet<>();
                traits.add("опора");
                if (random.nextDouble() < 0.4) {
                    traits.add("широта");
                }
                double middle = 100.0 / empiresInGame;
                // Очки науки у всех одинаковы — сторона на них не влияет вовсе.
                double learnt = middle + random.nextGaussian() * 2.0;
                double known = middle + (traits.contains("широта") ? 8 : 0)
                        + random.nextGaussian() * 2.0;
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, middle + random.nextGaussian() * 2.0, learnt, null,
                        null, null, null, known, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    @Test
    @DisplayName("Широта науки — мерило: сторона, дающая технологии без очков, видна")
    void breadthOfScienceIsMeasured() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("широта", 6);
        prices.put("опора", 0);

        BalanceVerdictRules.Assessment assessment = rules.assess(breadthWorld(17), prices);
        BalanceVerdictRules.Judgement breadth = assessment.judgements().stream()
                .filter(one -> one.code().equals("широта"))
                .findFirst()
                .orElseThrow();

        // В науке она пуста — очков не прибавляет, — а в технологиях сильна.
        assertThat(breadth.research()).isLessThan(1.5);
        assertThat(breadth.technology()).isGreaterThan(4.0);
        // И попала в общую силу: весит вполовину, но не нулём.
        assertThat(breadth.strength()).isGreaterThan(1.0);
    }

    /**
     * Наземное мерило: захваченные колонии МИНУС потерянные, доля со знаком.
     * <p>
     * Партия здесь устроена так, как она устроена в игре: сумма долей по наземному бою
     * тождественно ноль — всякий захват это чья-то потеря. Половина империй берёт колонии,
     * половина их теряет, а «бойцы» есть только у берущих.
     */
    private List<BalanceVerdictRules.Empire> groundWorld(long seed) {
        Random random = new Random(seed);
        List<BalanceVerdictRules.Empire> empires = new ArrayList<>();
        int empiresInGame = 4;
        for (int game = 0; game < 200; game++) {
            for (int slot = 0; slot < empiresInGame; slot++) {
                Set<String> traits = new HashSet<>(Set.of("опора"));
                boolean fighter = slot < 2;
                if (fighter) {
                    traits.add("бойцы");
                }
                // Берущие получают +50, теряющие -50: в сумме по партии ровно ноль.
                double ground = fighter ? 50 : -50;
                empires.add(new BalanceVerdictRules.Empire(
                        traits, 3, 25 + random.nextGaussian() * 2.0, null, null,
                        null, null, ground, null, empiresInGame, 5, 12.0));
            }
        }
        return empires;
    }

    @Test
    @DisplayName("Наземное мерило видит и отнятое, и потерянное")
    void groundCountsLossesToo() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        prices.put("бойцы", 2);
        prices.put("опора", 0);

        BalanceVerdictRules.Assessment assessment = rules.assess(groundWorld(11), prices);
        BalanceVerdictRules.Judgement fighters = assessment.judgements().stream()
                .filter(one -> one.code().equals("бойцы"))
                .findFirst()
                .orElseThrow();

        // Сторона видна именно наземным мерилом, и знак у неё верный: берущие сильнее.
        assertThat(fighters.ground()).isGreaterThan(40.0);
        assertThat(fighters.strength()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Не воевавший на земле получает ноль, а не штраф")
    void quietGameCostsNothing() {
        // Приведение к нулю у наземного мерила своё: среднее по партии у него ноль, а не
        // сто процентов на число империй. Империя, которая никого не трогала и которую
        // никто не трогал, должна получить ровно ноль — не воевать не значит быть слабым.
        BalanceVerdictRules.Empire quiet = new BalanceVerdictRules.Empire(
                Set.of("опора"), 3, 25.0, null, null, null, null, 0.0, null, 4, 5, 12.0);
        assertThat(rules.value(quiet, BalanceVerdictRules.Yardstick.GROUND)).isZero();

        // А у мерила, которое считает долю от целого, ноль — это отставание от среднего.
        BalanceVerdictRules.Empire poor = new BalanceVerdictRules.Empire(
                Set.of("опора"), 3, 0.0, null, null, null, null, null, null, 4, 5, 12.0);
        assertThat(rules.value(poor, BalanceVerdictRules.Yardstick.PRODUCTION)).isEqualTo(-25.0);
    }

    @Test
    @DisplayName("Невязка — это расхождение цены с заработанным, медианой по сторонам")
    void residualIsTheMedianMispricing() {
        // Курс очка 0,5: сторона за 4 очка должна зарабатывать 2 силы.
        // Расхождения по замыслу: 0, 2, 6 очков — медиана 2.
        List<BalanceVerdictRules.Judgement> judgements = List.of(
                judgement("ровная", 4, 2.0),     // заработала ровно свою цену
                judgement("дешёвая", 4, 3.0),    // заработала на 2 очка больше
                judgement("дорогая", 10, 2.0));  // заработала на 6 очков меньше

        assertThat(rules.residual(judgements, 0.5)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Невязку не надувает размытость прогона — в неё не входит полоска ошибки")
    void residualIgnoresTheErrorBars() {
        // Тот же замер, но прогон слеп: ошибки вдесятеро. Счёт адекватных от этого растёт —
        // «адекватно» значит и «нечем отличить», — а невязка обязана остаться прежней
        // (п. 3.59: на повторах одной таблицы адекватных вышло 20, 40 и 27, невязка
        // 6,4 / 6,4 / 6,8).
        List<BalanceVerdictRules.Judgement> sharp = List.of(
                judgement("а", 4, 2.0, 0.1), judgement("б", 4, 3.0, 0.1),
                judgement("в", 10, 2.0, 0.1));
        List<BalanceVerdictRules.Judgement> blurred = List.of(
                judgement("а", 4, 2.0, 9.9), judgement("б", 4, 3.0, 9.9),
                judgement("в", 10, 2.0, 9.9));

        assertThat(rules.residual(blurred, 0.5)).isEqualTo(rules.residual(sharp, 0.5));
    }

    @Test
    @DisplayName("Сторона без замера в невязку не идёт, и мерить бывает нечем")
    void residualSkipsWhatWasNotMeasured() {
        // Неизмеренная сторона — это пробел прогона, а не нулевое расхождение: сочтя её
        // за ноль, мы получили бы тем лучшую невязку, чем больше прибор промолчал.
        List<BalanceVerdictRules.Judgement> judgements = List.of(
                judgement("измеренная", 10, 2.0),
                new BalanceVerdictRules.Judgement("молчит", 4, 0, null, null, null, null,
                        null, null, null, null, null, null, null,
                        BalanceVerdictRules.Verdict.NOT_MEASURED));

        assertThat(rules.residual(judgements, 0.5)).isEqualTo(6.0);
        assertThat(rules.residual(judgements, null)).isNull();
        assertThat(rules.residual(List.of(), 0.5)).isNull();
    }

    private BalanceVerdictRules.Judgement judgement(String code, int price, double strength) {
        return judgement(code, price, strength, 0.5);
    }

    private BalanceVerdictRules.Judgement judgement(String code, int price, double strength,
                                                    double error) {
        return new BalanceVerdictRules.Judgement(code, price, 100, strength, error,
                null, null, null, null, null, null, null, null, null,
                BalanceVerdictRules.Verdict.FAIR);
    }
}
