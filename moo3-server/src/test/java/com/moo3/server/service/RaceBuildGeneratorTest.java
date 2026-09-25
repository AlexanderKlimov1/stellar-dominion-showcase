package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Генератор случайных сборок — п. 2.1 этапа 2 (`balance-metrics-works.txt`).
 * <p>
 * Правила проверяются здесь, а не прогоном: незаконная сборка в прогоне не падает, а
 * тихо портит замер — и замечают её только по странным ценам через сотню партий.
 */
class RaceBuildGeneratorTest {

    private final RaceTraitCatalog catalog = new RaceTraitCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private final RaceBuildGenerator generator = new RaceBuildGenerator(catalog);

    private Map<String, Integer> prices() {
        return catalog.groups().stream()
                .flatMap(group -> group.options().stream())
                .collect(java.util.stream.Collectors.toMap(
                        com.moo3.server.domain.RaceTrait::code,
                        com.moo3.server.domain.RaceTrait::picks));
    }

    @Test
    @DisplayName("Сборка стоит ровно бюджет и проходит проверку конструктора")
    void buildsCostExactlyTheBudget() {
        Map<String, Integer> prices = prices();
        Random random = new Random(1234);

        for (int budget = 0; budget <= catalog.picksBudget(); budget++) {
            for (int attempt = 0; attempt < 20; attempt++) {
                List<String> traits = generator.build(budget, random);
                if (traits.isEmpty()) {
                    // Не всякий бюджет складывается из цен таблицы: сегодня так с двойкой —
                    // сторона за очко в ней одна («большой мир»), а взять её дважды нельзя.
                    // Пустая сборка — честный ответ, а не поломка.
                    continue;
                }
                int spent = traits.stream().mapToInt(prices::get).sum();

                assertThat(spent).as("бюджет %d", budget).isEqualTo(budget);
                // Незаконную сборку конструктор отвергнет исключением — и проверка упадёт.
                assertThat(catalog.validate(traits)).hasSize(traits.size());
            }
        }
    }

    @Test
    @DisplayName("Бюджеты, которыми меряют курс, собираются все")
    void curveBudgetsAreBuildable() {
        Random random = new Random(5150);
        // Эти шесть и есть шкала замера (0 · 3 · 6 · 9 · 12 · 15). Если хоть один из них
        // перестанет собираться после правки цен, прогон молча потеряет целый столбик —
        // империя получит расу без сторон, и замер сравнит не то с тем.
        for (int budget : new int[] {0, 3, 6, 9, 12, 15}) {
            assertThat(generator.build(budget, random))
                    .as("бюджет %d", budget)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("Сборка со слабостями: возврат не выходит за потолок, а траты сходятся")
    void weaknessesStayWithinTheAntiBudget() {
        Map<String, Integer> prices = prices();
        Random random = new Random(99);
        int antiBudget = catalog.antiPicksBudget();

        Set<String> soldWeaknesses = new HashSet<>();
        for (int attempt = 0; attempt < 400; attempt++) {
            List<String> traits = generator.build(catalog.picksBudget(), antiBudget, random);
            if (traits.isEmpty()) {
                continue;
            }
            int spent = traits.stream().mapToInt(prices::get).sum();
            int returned = -traits.stream().mapToInt(prices::get).filter(picks -> picks < 0).sum();

            assertThat(spent).isEqualTo(catalog.picksBudget());
            assertThat(returned).isLessThanOrEqualTo(antiBudget);
            assertThat(catalog.validate(traits)).hasSize(traits.size());
            traits.stream().filter(code -> prices.get(code) < 0).forEach(soldWeaknesses::add);
        }

        // Ради этого пункт 2.1 и делался: до него минусовые стороны не попадали в замер
        // ни разу, и цену им нечем было проверить.
        assertThat(soldWeaknesses).as("проданные слабости").hasSizeGreaterThan(5);
    }

    @Test
    @DisplayName("Без потолка анти-выбора слабости не продаются вовсе")
    void withoutAntiBudgetNoWeaknesses() {
        Map<String, Integer> prices = prices();
        Random random = new Random(7);

        for (int attempt = 0; attempt < 50; attempt++) {
            List<String> traits = generator.build(catalog.picksBudget(), random);
            assertThat(traits.stream().mapToInt(prices::get).min().orElse(0))
                    .as("слабостей быть не должно")
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("Подсаженная связка стоит в сборке, а запрещённая — нет")
    void plantedPairIsAlwaysThere() {
        List<String> pair = List.of("cybernetic", "industry-great");
        int budget = catalog.picksBudget();
        int anti = catalog.antiPicksBudget();
        Map<String, Integer> prices = prices();

        int built = 0;
        for (int attempt = 0; attempt < 200; attempt++) {
            Random random = new Random(attempt);
            List<String> both = generator.build(budget, anti, random, pair, List.of());
            if (both.isEmpty()) {
                continue;
            }
            built++;
            assertThat(both).contains("cybernetic", "industry-great");
            // Подсаженная сборка остаётся ЗАКОННОЙ: обязательные стороны не освобождают
            // её ни от бюджета, ни от потолка анти-выбора.
            assertThat(both.stream().mapToInt(prices::get).sum()).isEqualTo(budget);
            assertThat(-both.stream().mapToInt(prices::get).filter(one -> one < 0).sum())
                    .isLessThanOrEqualTo(anti);
            assertThat(new HashSet<>(both)).hasSize(both.size());

            // Четверть «только вторая»: первой стороны в сборке нет вовсе.
            List<String> onlySecond = generator.build(budget, anti, new Random(attempt),
                    List.of("industry-great"), List.of("cybernetic"));
            if (!onlySecond.isEmpty()) {
                assertThat(onlySecond).contains("industry-great").doesNotContain("cybernetic");
            }
        }
        // Связка дорогая (12 очков промышленников), и всё же собирается почти всегда:
        // киборги за -7 её сами и оплачивают. Это и есть та пара, ради которой подсадка
        // затевалась.
        assertThat(built).isGreaterThan(150);
    }

    @Test
    @DisplayName("Стороны одной группы в связку не подсаживаются — и это видно сразу")
    void impossiblePairBuildsNothing() {
        // Обе из группы еды: вместе их не бывает, и четверть «обе сразу» осталась бы
        // пустой. Генератор отвечает пустотой, а пульт — отказом на заказе.
        assertThat(generator.build(catalog.picksBudget(), catalog.antiPicksBudget(),
                new Random(1), List.of("food-good", "food-great"), List.of())).isEmpty();
    }
}
