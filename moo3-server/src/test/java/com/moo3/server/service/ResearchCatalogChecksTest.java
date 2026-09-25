package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Справочник дерева проверяет СЕБЯ при чтении — п. 9.
 * <p>
 * Код технологии лежит теперь в самом файле, а не выводится из её названия, и ссылки внутри
 * файла (рекомендованная технология уровня, стартовые уровни) называют коды и места, а не
 * названия. Цена ошибки в такой ссылке раньше была нулевой на вид и высокой на деле:
 * рекомендация, написанная с опечаткой, просто не совпадала ни с чем — уровень молча
 * оставался без рекомендации, и заметить это можно было только замером поведения ИИ.
 * Поэтому неверная ссылка теперь отказ, и эта проверка держит именно отказ.
 */
class ResearchCatalogChecksTest {

    @TempDir
    Path directory;

    /** Дерево из двух уровней — ровно столько, сколько нужно, чтобы сломать одну ссылку. */
    private static final String TREE = """
            {
              "version": "test",
              "tech_categories": {
                "power": {
                  "name": { "en": "Power", "ru": "Энергетика" },
                  "description": { "en": "Engines", "ru": "Двигатели" },
                  "levels": [
                    {
                      "cost": 80,
                      "cumulative_cost": 80,
                      "general": true,
                      "level_name": { "en": "Nuclear Fission", "ru": "Ядерное расщепление" },
                      "technologies": [
                        { "code": "nuclear-drive", "name": { "en": "Nuclear Drive", "ru": "Ядерный двигатель" } },
                        { "code": "freighters", "name": { "en": "Freighters", "ru": "Грузовой флот" } }
                      ],
                      "recommended": ["nuclear-drive"]
                    }
                  ]
                }
              },
              "starting_levels": { "all_races": ["power:1"] }
            }
            """;

    private ResearchCatalog catalogOf(String tree) {
        Path file = directory.resolve("tech.json");
        try {
            Files.writeString(file, tree, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GameProperties properties = new GameProperties(8, 4, 1.5, "star-names.txt",
                file.toString(),
                "../resources/Buildings/buildings.json",
                "../resources/Races/race-traits.json",
                "../resources/Ships/ship-components.json",
                "../resources/Leaders/leaders.json");
        return new ResearchCatalog(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("Целое дерево читается: код берётся из файла, стартовый уровень — из ссылки")
    void wholeTreeReads() {
        ResearchCatalog catalog = catalogOf(TREE);

        assertThat(catalog.tree().categories()).hasSize(1);
        assertThat(catalog.tree().categories().getFirst().levels().getFirst().options())
                .extracting("code").containsExactly("nuclear-drive", "freighters");
        assertThat(catalog.startingLevels())
                .containsExactly(new ResearchCatalog.StartingLevel("power", 1));
        // Рекомендация называет код, и признак доезжает до клетки дерева.
        assertThat(catalog.tree().categories().getFirst().levels().getFirst().options().getFirst()
                .recommended()).isTrue();
    }

    @Test
    @DisplayName("Технология без кода — отказ, а не безымянная запись")
    void codeIsRequired() {
        assertThatThrownBy(() -> catalogOf(TREE.replace("\"code\": \"freighters\", ", "")).tree())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("нет кода");
    }

    @Test
    @DisplayName("Повторённый код — отказ: по коду партия узнаёт изученное")
    void codesAreUnique() {
        assertThatThrownBy(() -> catalogOf(TREE.replace("\"freighters\"", "\"nuclear-drive\"")).tree())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("повторяется");
    }

    @Test
    @DisplayName("Рекомендация с опечаткой — отказ, а не уровень без рекомендации")
    void recommendedMustName() {
        assertThatThrownBy(() -> catalogOf(TREE.replace("[\"nuclear-drive\"]", "[\"nuclear-drve\"]")).tree())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рекомендация");
    }

    @Test
    @DisplayName("Стартовый уровень, которого в дереве нет, — отказ")
    void startingLevelMustExist() {
        assertThatThrownBy(() -> catalogOf(TREE.replace("\"power:1\"", "\"power:9\"")).startingLevels())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Стартового уровня в дереве нет");
    }

    @Test
    @DisplayName("Стартовый уровень, записанный не ссылкой, — отказ")
    void startingLevelMustBeReference() {
        assertThatThrownBy(() -> catalogOf(TREE.replace("\"power:1\"", "\"Nuclear Fission (Power)\""))
                .startingLevels())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не как «раздел:номер»");
    }
}
