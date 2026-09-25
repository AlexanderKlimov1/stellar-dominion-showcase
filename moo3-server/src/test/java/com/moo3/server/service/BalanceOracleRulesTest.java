package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила оракула сборок — этап 3 (`balance-metrics-works.txt`).
 * <p>
 * Проверяется то, ради чего оракул и заведён: два условия плана — «нет доминирующей сборки»
 * и «нет мёртвой стороны», — и законность потомков. Данные выдуманные, ответ известен
 * заранее: на настоящем прогоне правильного ответа не знает никто.
 */
class BalanceOracleRulesTest {

    private final BalanceOracleRules rules = new BalanceOracleRules();

    private final RaceTraitCatalog catalog = new RaceTraitCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private final RaceBuildGenerator builds = new RaceBuildGenerator(catalog);

    private BalanceOracleRules.Build build(double strength, String... traits) {
        return new BalanceOracleRules.Build(List.of(traits), 15, 4, strength, 0.2);
    }

    @Test
    @DisplayName("Доминирующая сборка видна, а ровная верхушка — нет")
    void seesTheDominatingBuild() {
        List<BalanceOracleRules.Build> even = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            even.add(build(3.0 - i * 0.02, "growth-fast", "food-good"));
        }
        BalanceOracleRules.Search calm = rules.read(even, Set.of("growth-fast", "food-good"), 3);
        assertThat(calm.dominated()).isFalse();
        assertThat(calm.gap()).isLessThan(BalanceOracleRules.DOMINATION_GAP);

        // Та же верхушка, но первая сборка отрывается на три пункта — это уже «играть надо
        // только так», и прибор обязан сказать это словом, а не оставить на глазок.
        List<BalanceOracleRules.Build> broken = new ArrayList<>(even);
        broken.add(build(6.0, "gov-unification", "industry-great"));
        BalanceOracleRules.Search alarm = rules.read(broken,
                Set.of("growth-fast", "food-good", "gov-unification", "industry-great"), 3);
        assertThat(alarm.dominated()).isTrue();
        assertThat(alarm.ladder().getFirst().strength()).isEqualTo(6.0);
    }

    @Test
    @DisplayName("Три сильные сборки — это конкуренция, а не доминирование")
    void threeRivalsAreBalance() {
        // Решение хозяина проекта: баланс — не одна ровная верхушка, а СОПЕРНИЧЕСТВО
        // нескольких сборок. Если три идут вровень, игроку есть из чего выбирать, и на их
        // борьбе баланс и стоит. Прежняя мера сравнивала сильнейшую с СЕРЕДИНОЙ верхушки и
        // на таком поле кричала бы о доминировании — а доминирования тут нет.
        List<BalanceOracleRules.Build> ladder = new ArrayList<>();
        ladder.add(build(6.00, "gov-unification", "food-good"));
        ladder.add(build(5.90, "growth-fast", "industry-good"));
        ladder.add(build(5.80, "science-great", "money-rich"));
        // Длинный слабый хвост: именно он утягивал середину вниз и делал разрыв огромным.
        for (int i = 0; i < 17; i++) {
            ladder.add(build(1.0 - i * 0.02, "growth-fast", "food-good"));
        }
        BalanceOracleRules.Search rivals = rules.read(ladder,
                Set.of("gov-unification", "food-good", "growth-fast", "industry-good",
                        "science-great", "money-rich"), 3);

        assertThat(rivals.dominated()).isFalse();
        assertThat(rivals.gap()).isLessThan(BalanceOracleRules.DOMINATION_GAP);
        // А середина верхушки при этом далеко внизу — и по прежней мере приговор был бы
        // обратным. Она осталась в отчёте для справки: по ней видно длину хвоста.
        assertThat(rivals.median()).isLessThan(2.0);
    }

    @Test
    @DisplayName("Оторвавшаяся от третьей сборка — доминирующая, даже если вторая рядом")
    void oneRunawayIsNotBalance() {
        List<BalanceOracleRules.Build> ladder = new ArrayList<>();
        ladder.add(build(9.00, "gov-unification", "food-good"));
        ladder.add(build(8.90, "gov-unification", "industry-good"));
        // Третьей вровень нет: соперников у пары только два, выбора это не даёт.
        for (int i = 0; i < 18; i++) {
            ladder.add(build(3.0 - i * 0.02, "growth-fast", "food-good"));
        }
        BalanceOracleRules.Search alarm = rules.read(ladder,
                Set.of("gov-unification", "food-good", "growth-fast", "industry-good"), 3);

        assertThat(alarm.dominated()).isTrue();
    }

    @Test
    @DisplayName("Сторона, не вошедшая в верхушку, названа мёртвой")
    void namesTheDeadTraits() {
        List<BalanceOracleRules.Build> ladder = List.of(
                build(3.0, "growth-fast", "food-good"),
                build(2.0, "growth-fast", "industry-good"));

        BalanceOracleRules.Search search = rules.read(ladder,
                Set.of("growth-fast", "food-good", "industry-good", "telepathic", "repulsive"), 2);

        // По плану это не «дорогая сторона», а неработающая или неизмеримая механика —
        // потому её и называют отдельным списком, а не прячут в ладдере.
        // «Мёртвая» и «не пробованная» — разные ответы: первый про игру, второй про длину
        // прогона, и валить их в один список значит выдавать пробел поиска за приговор.
        assertThat(search.deadTraits()).isEmpty();
        assertThat(search.unseenTraits()).containsExactly("repulsive", "telepathic");
    }

    /**
     * Потомок законен, и поиск не глохнет — п. 3.
     * <p>
     * <b>Выход меряется по МНОГИМ родителям, а не по одному.</b> Здесь стоял один родитель
     * из зерна 31 и порог «больше 80 потомков из ста», и порог этот был свойством не
     * таблицы цен, а того единственного родителя: честная правка цен (еда, рост,
     * промышленность — журнал, п. 3.30) уронила его до 54, хотя по двумстам родителям выход
     * составил 98 % при нулевом числе несобравшихся сборок. Родители и правда разные:
     * сборке, набравшей бюджет копейками, заменить сторону труднее, и худший из двухсот дал
     * 60 %. Порог поэтому взят по СОВОКУПНОСТИ и с запасом вниз: он ловит настоящую беду —
     * таблицу, из которой бюджет перестал набираться, — и не ловит того, что одному
     * родителю не повезло.
     */
    @Test
    @DisplayName("Потомок сборки законен: ровно бюджет и одна заменённая сторона")
    void childStaysLegal() {
        int budget = catalog.picksBudget();
        int anti = catalog.antiPicksBudget();
        Random random = new Random(31);

        int born = 0;
        int attempts = 0;
        for (int family = 0; family < 20; family++) {
            List<String> parent = builds.build(budget, anti, random);
            assertThat(parent).isNotEmpty();
            for (int attempt = 0; attempt < 20; attempt++) {
                attempts++;
                List<String> child = rules.mutate(parent, builds, budget, anti, random);
                if (child.isEmpty()) {
                    continue;
                }
                born++;
                // Законность проверяет сам справочник — тем же способом, каким проверял бы
                // сборку игрока.
                assertThat(catalog.validate(child)).hasSize(child.size());
                assertThat(child.stream()
                        .mapToInt(code -> catalog.require(code).picks())
                        .sum())
                        .isEqualTo(budget);
                // Потомок отличается от родителя: иначе поиск топтался бы на месте.
                assertThat(Set.copyOf(child)).isNotEqualTo(Set.copyOf(parent));
            }
        }
        assertThat(born).isGreaterThan(attempts * 3 / 4);
    }

    @Test
    @DisplayName("Сборки рассаживаются по партиям и каждая играет ровно раз за круг")
    void dealsEveryBuildOncePerRound() {
        List<List<Integer>> tables = rules.deal(24, 8, new Random(5));

        assertThat(tables).hasSize(3);
        assertThat(tables.stream().flatMap(List::stream).distinct().count()).isEqualTo(24);
        assertThat(tables).allMatch(table -> table.size() == 8);

        // Недобранная партия добирается соседями по второму кругу: одна империя в партии
        // долей не мерится вовсе — доля её всегда сто процентов.
        List<List<Integer>> ragged = rules.deal(10, 8, new Random(5));
        assertThat(ragged).allMatch(table -> table.size() >= 2);
    }

    @Test
    @DisplayName("Партии удлиняются поколение за поколением, но не без предела")
    void turnsGrowWithGenerations() {
        assertThat(rules.turns(50, 0, 300)).isEqualTo(50);
        assertThat(rules.turns(50, 1, 300)).isEqualTo(100);
        assertThat(rules.turns(50, 2, 300)).isEqualTo(200);
        assertThat(rules.turns(50, 5, 300)).isEqualTo(300);
    }

    @Test
    @DisplayName("Сила сборки — среднее по её партиям, и ошибка падает с их числом")
    void averagesOverGames() {
        BalanceOracleRules.Build one = rules.measured(List.of("food-good"), 4, List.of(1.0));
        BalanceOracleRules.Build many =
                rules.measured(List.of("food-good"), 4, List.of(1.0, 2.0, 3.0, 2.0));

        assertThat(one.games()).isEqualTo(1);
        // Одна партия своей ошибки не имеет вовсе: разбросу неоткуда взяться.
        assertThat(one.error()).isNull();
        assertThat(many.strength()).isEqualTo(2.0);
        assertThat(many.error()).isNotNull().isLessThan(1.0);
    }

    @Test
    @DisplayName("Поле сжимается вдвое и не пополняется до прежнего размера")
    void fieldReallyShrinks() {
        // Отсев должен СЖИМАТЬ поле: половина слабейших уходит совсем. Пока освободившиеся
        // места добирались потомками до прежнего размера, поле крутилось на месте, и ни
        // одна сборка не набирала партий — на первом прогоне оракула вышло тридцать
        // «поколений» по четыре партии и ошибки больше разницы.
        assertThat(rules.survivors(32)).isEqualTo(16);
        assertThat(rules.survivors(16)).isEqualTo(8);
        assertThat(rules.survivors(8)).isEqualTo(4);
        // Меньше двух не остаётся: долей одну сборку не измерить вовсе.
        assertThat(rules.survivors(3)).isEqualTo(2);
        assertThat(rules.survivors(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("Верхушку не закрывает сборка, отыгравшая три партии из девяноста")
    void aLuckyShortLivedBuildDoesNotDecideTheVerdict() {
        // Поиск отсевом даёт сборкам РАЗНОЕ число партий: финалисты по девяносто,
        // отсеянные в первом поколении — по одной-две. На настоящем прогоне первой строкой
        // встала сборка с тремя партиями и ошибкой ±19,64 — и объявила доминирующей себя,
        // а не настоящего лидера. В ладдере она остаётся, в приговоре — нет.
        List<BalanceOracleRules.Build> ladder = new ArrayList<>();
        ladder.add(new BalanceOracleRules.Build(List.of("traders"), 15, 3, 9.30, 19.64));
        ladder.add(new BalanceOracleRules.Build(List.of("industry-great"), 15, 89, 9.19, 2.41));
        for (int i = 0; i < 6; i++) {
            ladder.add(new BalanceOracleRules.Build(
                    List.of("food-good"), 15, 90, 8.9 - i * 0.1, 2.0));
        }

        BalanceOracleRules.Search search =
                rules.read(ladder, Set.of("traders", "industry-great", "food-good"), 10);

        // Приговор выносит финалист, а везучий новичок остаётся в ладдере строкой.
        assertThat(search.best()).isEqualTo(9.19);
        assertThat(search.ladder().getFirst().games()).isEqualTo(3);
    }
}
