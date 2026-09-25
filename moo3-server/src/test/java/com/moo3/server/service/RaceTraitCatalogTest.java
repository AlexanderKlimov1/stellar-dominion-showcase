package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.RaceTrait;
import com.moo3.server.domain.RaceTraitGroup;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.springframework.context.i18n.LocaleContextHolder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Конструктор расы — п. 7: разбор файла особенностей и правила выбора. */
class RaceTraitCatalogTest {

    private final RaceTraitCatalog catalog = new RaceTraitCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    @Test
    @DisplayName("Файл разбирается: оба бюджета и все группы конструктора")
    void readsDesign() {
        // ЧИСЛА ЗДЕСЬ НЕ ВПИСЫВАЮТСЯ, и это не лень. Бюджет двигается балансировкой —
        // 10 в MOO II, потом 15, потом 20, теперь 30 (журнал, п. 3.107), — и проверка,
        // знающая сегодняшнее число наизусть, падает на первой же честной правке, не найдя
        // при этом ни одной поломки. Держаться надо за ПРАВИЛО: оба бюджета прочитаны, оба
        // положительны, а вернуть слабостями больше, чем можно потратить, нельзя никогда.
        assertThat(catalog.picksBudget()).isPositive();
        assertThat(catalog.antiPicksBudget()).isPositive();
        assertThat(catalog.antiPicksBudget()).isLessThan(catalog.picksBudget());
        assertThat(catalog.groups()).extracting("code")
                .containsExactlyInAnyOrder("government",
                        "growth", "food", "industry", "science", "money",
                        "ship-defense", "ship-attack", "ground", "espionage",
                        "gravity", "population-capacity", "homeworld", "food-consumption",
                        "diplomacy", "creativity", "economics", "special");
    }

    @Test
    @DisplayName("Конструктор — ровно таблица MOO II: 53 особенности, и ничего сверх неё")
    void tableIsTheOriginalOne() {
        List<String> codes = catalog.groups().stream()
                .flatMap(group -> group.options().stream())
                .map(RaceTrait::code)
                .toList();

        // Четыре правительства, девять лестниц по три ступени и двадцать две особые
        // способности — столько строк в таблице оригинала, и ни одной своей.
        assertThat(codes).hasSize(53);
        assertThat(codes).doesNotContain("home-terran", "home-gaia");
    }

    @Test
    @DisplayName("Колониальные особенности складываются в эффекты, как здания")
    void colonyEffectsSum() {
        RaceEffects effects = catalog.effects(List.of("food-good", "industry-great"));

        assertThat(effects.colony().foodPerFarmer()).isEqualTo(1);
        assertThat(effects.colony().productionPerWorker()).isEqualTo(2);
        assertThat(effects.colony().amount(BuildingEffectType.GROWTH_PERCENT)).isZero();
    }

    @Test
    @DisplayName("Правительство меняет сразу несколько сторон империи")
    void governmentChangesSeveralThings() {
        // Демократия MOO II: половина сверху к науке и к налогу, минус к контрразведке.
        RaceEffects democracy = catalog.effects(List.of("gov-democracy"));

        assertThat(democracy.colony().incomePercent()).isEqualTo(50);
        assertThat(democracy.colony().researchPercent()).isEqualTo(50);
        assertThat(democracy.espionageDefensePoints()).isEqualTo(-1);
        // Название строя приходит на языке читателя — п. 3.5, и проверяется это обоими
        // языками, а не одним: язык вне запроса берётся из контекста, и проверка, молча
        // на него положившаяся, зависела бы от порядка тестов.
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(catalog.government(List.of("gov-democracy"))).isEqualTo("Democracy");
        LocaleContextHolder.setLocale(Locale.of("ru"));
        assertThat(catalog.government(List.of("gov-democracy"))).isEqualTo("Демократия");
        LocaleContextHolder.resetLocaleContext();

        // Объединение — половина сверху к рабочему и фермеру, но не к учёному.
        RaceEffects unification = catalog.effects(List.of("gov-unification"));
        assertThat(unification.colony().productionPercent()).isEqualTo(50);
        assertThat(unification.colony().foodPercent()).isEqualTo(50);
        assertThat(unification.colony().researchPercent()).isZero();

        // Феодализм роняет науку вдвое, зато строит корабли на треть дешевле.
        RaceEffects feudal = catalog.effects(List.of("gov-feudal"));
        assertThat(feudal.colony().researchPercent()).isEqualTo(-50);
        assertThat(feudal.shipCostPercent()).isEqualTo(-33);
    }

    @Test
    @DisplayName("Готовые расы собраны законно по составу, а стоить могут по-разному")
    void readyRacesAreStructurallyLegal() {
        // РЕШЕНИЕ ХОЗЯИНА ПРОЕКТА (журнал, п. 3.70): готовые расы НЕ ОБЯЗАНЫ быть равными
        // по силе, а цены сторон гонятся за измеренной отдачей. Здесь стояло «каждая стоит
        // ровно бюджет», и это требование держалось, пока цена была величиной договорной.
        // Теперь она измеряемая: подорожала сторона — и Булрати просто стоят дороже, то
        // есть они сильнее расы, собранной игроком. Это и есть MOO II, где Псилоны сильнее
        // Гноламов.
        //
        // Проверяется поэтому СОСТАВ, а не сумма: коды существуют, из группы-переключателя
        // взята одна сторона, несовместимые вместе не стоят. Опечатка в коде по-прежнему
        // роняет чтение файла, а не тихо отнимает у расы её сторону.
        List<String> codes = List.of("ALKARI", "BULRATHI", "DARLOKS", "ELERIAN", "GNOLAM",
                "HUMANS", "KLACKONS", "MEKLAR", "MRRSHAN", "PSILONS", "SAKKRA", "SILICOIDS",
                "TRILARIAN");

        for (String code : codes) {
            List<String> traits = catalog.raceTraits(code);
            assertThat(traits).as("стороны расы %s", code).isNotEmpty();
            // Справочник уже проверил набор при чтении файла; здесь важно, что он их отдаёт
            // целиком, а не молча теряет стороны.
            assertThat(traits).as("набор расы %s", code)
                    .allSatisfy(trait -> assertThat(catalog.require(trait)).isNotNull());
        }

        // Стоимости РАЗНЫЕ, и это больше не ошибка. Проверяется лишь то, что они осмысленны:
        // раса не может стоить ноль или уйти в минус — это значило бы потерянный набор.
        for (String code : codes) {
            int spent = catalog.raceTraits(code).stream()
                    .mapToInt(trait -> catalog.require(trait).picks()).sum();
            assertThat(spent).as("очки расы %s", code).isPositive();
        }
    }

    @Test
    @DisplayName("Связка стоит сверх своих частей, и надбавка берётся только с обеих сразу")
    void combinationCostsExtra(@TempDir Path directory) throws IOException {
        // Проверка идёт по СВОЕМУ справочнику во временном каталоге, а не по игровому.
        // Живой набор связок — величина балансировочная: его правят, отключают и возвращают
        // (журнал, п. 3.76), и проверка, спрашивающая «дай мне первую связку», падала бы от
        // самого отключения — то есть ловила бы решение хозяина проекта вместо поломки.
        // Проверяется ПРАВИЛО: надбавка берётся, только когда взяты все стороны связки.
        Path traits = directory.resolve("race-traits.json");
        Files.copy(Path.of("../resources/Races/race-traits.json"), traits);
        Files.writeString(directory.resolve("pairs-balance.json"), """
                {"combinations": [
                  {"traits": ["gov-unification", "world-large"], "picks": 8,
                   "note": "проверочная связка"}
                ]}
                """, StandardCharsets.UTF_8);
        RaceTraitCatalog own = new RaceTraitCatalog(
                new GameProperties(8, 4, 1.5, "star-names.txt",
                        "../resources/Technologies/tech.json",
                        "../resources/Buildings/buildings.json",
                        traits.toString(),
                        "../resources/Ships/ship-components.json",
                        "../resources/Leaders/leaders.json"),
                new ObjectMapper());

        List<String> pair = List.of("gov-unification", "world-large");
        assertThat(own.combinations()).hasSize(1);
        // Половина связки надбавки не берёт: платит только тот, кто взял обе стороны.
        assertThat(own.combinationExtra(List.of("gov-unification"))).isZero();
        assertThat(own.combinationExtra(pair)).isEqualTo(8);

        // И в счёт очков она входит: набор, который без надбавки уложился бы в бюджет,
        // с нею уже не укладывается.
        int parts = pair.stream().mapToInt(code -> own.require(code).picks()).sum();
        assertThat(parts).as("части связки сами по себе").isLessThanOrEqualTo(own.picksBudget());
        assertThat(parts + 8).as("вместе с надбавкой").isGreaterThan(own.picksBudget());
        assertThatThrownBy(() -> own.validate(pair))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("race.overBudget");
    }

    @Test
    @DisplayName("Без связок в справочнике надбавок нет вовсе")
    void noCombinationsMeansNoExtra() {
        // Отсутствие связок — обычное состояние таблицы, а не сбой: надбавки отключены
        // решением хозяина проекта, и игра обязана считаться как прежде.
        assertThat(catalog.combinationExtra(List.of("gov-unification", "world-large"))).isZero();
    }

    @Test
    @DisplayName("Игроку бюджет и потолок спрашиваются по-прежнему")
    void thePlayerStillPaysTheBudget() {
        // Послабление касается ТОЛЬКО готовых рас: они портрет, а не покупка. Собранная
        // игроком раса платит за стороны как платила.
        assertThatThrownBy(() -> catalog.validate(List.of("gov-unification", "food-great",
                "world-large", "growth-fast")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("race.overBudget");
    }

    @Test
    @DisplayName("Готовые расы собраны из тех же сторон, что покупает игрок")
    void readyRacesUseTheSameTraits() {
        assertThat(catalog.raceTraits("PSILONS"))
                .contains("science-great", "creative", "world-large", "grav-low");

        RaceEffects silicoid = catalog.effects(catalog.raceTraits("SILICOIDS"));
        // Литовор не ест вовсе, неприхотливые считают любой мир земным (п. 4.1.2),
        // а растут они вдвое медленнее прочих.
        assertThat(silicoid.foodConsumptionPercent()).isEqualTo(-100);
        assertThat(silicoid.tolerant()).isTrue();
        assertThat(silicoid.colony().growthPercent()).isEqualTo(-50);

        // Расы, которой в файле нет, стороны не достаются — и это не ошибка:
        // справочник рас правится миграцией, а стороны файлом.
        assertThat(catalog.raceTraits("NO_SUCH_RACE")).isEmpty();
    }

    @Test
    @DisplayName("Особые способности берутся по нескольку из одной группы")
    void severalSpecialAbilitiesTogether() {
        // Группа особых способностей MOO II — не переключатель: всевидящий бывает
        // и скрытным, и надпространственным разом.
        // 4 + 8 + 6 = 18, и это укладывается в двадцать.
        assertThat(catalog.validate(List.of("omniscient", "lucky", "stealthy-ships")))
                .hasSize(3);
    }

    @Test
    @DisplayName("Несовместимые стороны вместе не берутся")
    void incompatibleTraitsRejected() {
        // Литовор не ест, и сельские стороны ему брать не с чего — в MOO II они
        // становятся недоступны. Запрет двусторонний: порядок не важен.
        assertThatThrownBy(() -> catalog.validate(List.of("lithovore", "food-good")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> catalog.validate(List.of("food-good", "lithovore")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> catalog.validate(List.of("world-rich", "world-poor")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Особенности подсистем не попадают в эффекты колонии")
    void subsystemEffectsStayOutOfColony() {
        RaceEffects effects = catalog.effects(List.of("ground-great", "spy-great", "ship-attack-great"));

        assertThat(effects.groundCombatPercent()).isEqualTo(20);
        assertThat(effects.espionagePoints()).isEqualTo(2);
        assertThat(effects.shipAttackPercent()).isEqualTo(50);
        assertThat(effects.colony().amounts()).isEmpty();
    }

    @Test
    @DisplayName("Из группы берётся не больше одной особенности")
    void oneTraitPerGroup() {
        assertThatThrownBy(() -> catalog.validate(List.of("food-good", "food-great")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Слабые стороны возвращают очки и оплачивают сильные")
    void negativeTraitsPayForPositives() {
        // Набор СТРОИТСЯ ИЗ СПРАВОЧНИКА, а не берётся у готовой расы и не вписан кодами.
        // Здесь стояла Саккра — с припиской, что у готовой расы нужное свойство держится
        // само, «раса стоит ровно бюджет». Требования «ровно бюджет» больше нет (журнал,
        // п. 3.70), и первая же честная правка цен увела Саккру на 22 очка: проверка
        // упала на том, что готовая раса перестала быть покупкой по карману игрока. А это
        // теперь норма, а не поломка.
        //
        // Стороны берутся ПО ОДНОЙ ИЗ ГРУППЫ и без несовместимостей: так отказ придёт
        // только по бюджету, а не по другой причине.
        List<String> positives = new ArrayList<>();
        int spent = 0;
        for (RaceTraitGroup group : catalog.groups()) {
            if (spent > catalog.picksBudget()) {
                break;
            }
            RaceTrait option = group.options().stream()
                    .filter(one -> one.picks() > 0 && one.excludes().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (option != null) {
                positives.add(option.code());
                spent += option.picks();
            }
        }
        assertThat(spent).as("набрано сверх бюджета").isGreaterThan(catalog.picksBudget());

        // Отказ именно по бюджету, а не по группе или несовместимости: иначе проверка
        // проходила бы по неверной причине.
        assertThatThrownBy(() -> catalog.validate(positives))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("race.overBudget");

        // А со слабостями тот же набор проходит: они и оплачивают сильные стороны.
        List<String> withWeakness = new ArrayList<>(positives);
        int returned = 0;
        for (RaceTraitGroup group : catalog.groups()) {
            if (spent - returned <= catalog.picksBudget()) {
                break;
            }
            RaceTrait weak = group.options().stream()
                    .filter(one -> one.picks() < 0 && one.excludes().isEmpty())
                    .filter(one -> withWeakness.stream()
                            .noneMatch(code -> catalog.require(code).groupCode()
                                    .equals(one.groupCode())))
                    .findFirst()
                    .orElse(null);
            if (weak != null) {
                withWeakness.add(weak.code());
                returned -= weak.picks();
            }
        }
        assertThat(returned).as("слабости вернули очки").isPositive();
        assertThat(catalog.validate(withWeakness)).hasSize(withWeakness.size());
    }

    @Test
    @DisplayName("Слабостями нельзя вернуть больше потолка анти-выбора")
    void negativeTraitsCappedByAntiBudget() {
        // Набор строится ОТ ПОТОЛКА, а не вписан числом. Здесь стояла тройка «отталкивающие,
        // феодалы, медленный рост» с припиской «четырнадцать очков при потолке в десять» —
        // и подъём потолка до пятнадцати (журнал, п. 3.61) сделал бы её законной, уронив
        // проверку там, где поломки нет. Те же грабли, что в п. 3.30.
        //
        // Слабости берутся ПО ОДНОЙ ИЗ ГРУППЫ: так отказ не может прийти по другой причине
        // («из группы берётся не больше одной»), и остаётся ровно потолок.
        List<String> selling = new ArrayList<>();
        int returned = 0;
        for (RaceTraitGroup group : catalog.groups()) {
            if (returned > catalog.antiPicksBudget()) {
                break;
            }
            group.options().stream()
                    .filter(option -> option.picks() < 0)
                    .findFirst()
                    .map(RaceTrait::code)
                    .ifPresent(selling::add);
            returned = -selling.stream().mapToInt(code -> catalog.require(code).picks()).sum();
        }
        assertThat(returned).as("слабостей набрано сверх потолка")
                .isGreaterThan(catalog.antiPicksBudget());

        // Отказ именно по потолку, а не по бюджету и не по несовместимости: иначе проверка
        // проходила бы по неверной причине.
        assertThatThrownBy(() -> catalog.validate(selling))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("race.overAntiBudget");

        // А раса, продающая слабости у самого потолка, законна — так устроены Силикоиды,
        // самая «продающая» раса игры.
        List<String> silicoids = catalog.raceTraits("SILICOIDS");
        assertThat(catalog.validate(silicoids)).hasSize(silicoids.size());
        assertThat(-silicoids.stream()
                .mapToInt(code -> catalog.require(code).picks())
                .filter(picks -> picks < 0)
                .sum())
                .as("слабости Силикоидов")
                .isPositive()
                .isLessThanOrEqualTo(catalog.antiPicksBudget());
    }

    @Test
    @DisplayName("Неизвестная особенность отвергается")
    void unknownTraitRejected() {
        assertThatThrownBy(() -> catalog.validate(List.of("no-such-trait")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Развитый строй в замере считается тем, что был куплен")
    void advancedGovernmentCountsAsThePickedOne() {
        // Единство стоит 6 очков, галактическое единство — ничего: оно приходит
        // технологией. Без обратной подмены раса на десять очков превращалась бы посреди
        // партии в расу на четыре, и курс «очко → сила» мерил бы рост, а не сборку.
        List<String> grown = List.of("gov-galactic-unification", "growth-fast", "science-poor");

        assertThat(catalog.asPicked(grown))
                .containsExactly("gov-unification", "growth-fast", "science-poor");
        assertThat(catalog.asPicked(List.of("gov-unification", "growth-fast")))
                .containsExactly("gov-unification", "growth-fast");
    }
}
