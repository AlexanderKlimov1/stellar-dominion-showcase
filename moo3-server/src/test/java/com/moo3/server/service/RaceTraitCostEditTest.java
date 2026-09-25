package com.moo3.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Правка цен особенностей расы — п. 7: то, что делает экран «Стоимость особенностей рас».
 * <p>
 * Проверяется на копии настоящего справочника во временном каталоге: правка пишет на
 * диск, и трогать файл, с которым играют, тест не должен.
 */
class RaceTraitCostEditTest {

    private static final Path SOURCE = Path.of("../resources/Races/race-traits.json");

    @TempDir
    Path directory;

    private Path file;
    private RaceTraitCatalog catalog;

    @BeforeEach
    void setUp() throws IOException {
        file = directory.resolve("race-traits.json");
        Files.copy(SOURCE, file);
        catalog = new RaceTraitCatalog(
                new GameProperties(8, 4, 1.5, "star-names.txt",
                        "../resources/Technologies/tech.json",
                        "../resources/Buildings/buildings.json",
                        file.toString(),
                        "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
                new ObjectMapper());
    }

    @Test
    @DisplayName("Новая цена ложится в файл и сразу видна справочнику")
    void writesPicks() throws IOException {
        // Прежняя цена СНИМАЕТСЯ, а не пишется числом: здесь стояло «равно девяти», и
        // честная правка цен (журнал, п. 3.30) уронила проверку, в которой поломки нет.
        // Проверять нужно, что цена изменилась и легла в файл, а не какой она была вчера.
        Integer before = catalog.require("growth-fast").picks();
        assertThat(before).isNotEqualTo(7);
        int ceiling = catalog.antiPicksBudget();

        // Бюджет поднимается, а не опускается, и дешевеет сторона, которой нет ни в одной
        // готовой расе: справочник проверяет все тринадцать наборов при каждой перечитке,
        // и правка, делающая хоть один из них незаконным, роняет чтение файла целиком.
        //
        // Новый бюджет считается ОТ НЫНЕШНЕГО, а не пишется числом: здесь стояло «17», и
        // подъём бюджета расы с пятнадцати до двадцати (журнал, п. 3.56) превратил эту
        // правку в ПОНИЖЕНИЕ ниже цены готовых рас — проверка падала там, где поломки нет.
        Integer raised = catalog.picksBudget() + 2;
        catalog.updatePicks(raised, Map.of("growth-fast", 7, "world-poor", -5));

        // Справочник отдаёт новое, не дожидаясь перечитки по времени правки.
        assertThat(catalog.picksBudget()).isEqualTo(raised);
        assertThat(catalog.require("growth-fast").picks()).isEqualTo(7);
        assertThat(catalog.require("world-poor").picks()).isEqualTo(-5);

        // И то же самое лежит в файле: правка переживёт перезапуск сервера.
        JsonNode saved = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(saved.path("picks").asInt()).isEqualTo(raised);
        assertThat(pick(saved, "growth-fast")).isEqualTo(7);
        assertThat(pick(saved, "world-poor")).isEqualTo(-5);

        // Потолок анти-выбора редактор цен не трогает: он правит цены и бюджет, а
        // потолок живёт своей строкой файла. Сверяется он С САМИМ СОБОЙ до правки, а не с
        // числом: потолок — величина балансировочная (10 -> 15, журнал п. 3.61), и
        // вписанное число ломало бы проверку на каждой честной правке.
        assertThat(saved.path("anti_picks").asInt()).isEqualTo(ceiling);
    }

    @Test
    @DisplayName("Правится только цена: описания, действия и заметки файла остаются")
    void keepsEverythingElse() throws IOException {
        JsonNode before = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));

        catalog.updatePicks(null, Map.of("food-great", 7));

        JsonNode after = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(after.path("notes")).isEqualTo(before.path("notes"));
        assertThat(after.path("groups").size()).isEqualTo(before.path("groups").size());
        assertThat(option(after, "food-great").path("effects"))
                .isEqualTo(option(before, "food-great").path("effects"));
        assertThat(option(after, "food-great").path("description").asText())
                .isEqualTo(option(before, "food-great").path("description").asText());
        // Бюджет не передавали — он остался прежним.
        assertThat(after.path("picks").asInt()).isEqualTo(before.path("picks").asInt());
    }

    @Test
    @DisplayName("Неизвестная особенность — отказ, и файл не тронут")
    void unknownTraitLeavesFileAlone() throws IOException {
        String before = Files.readString(file, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> catalog.updatePicks(null, Map.of("no-such-trait", 3)))
                .isInstanceOf(NotFoundException.class);

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(before);
    }

    @Test
    @DisplayName("Цена за границами разумного — отказ целиком, половина правки не пишется")
    void outOfRangeIsRejectedWholly() throws IOException {
        String before = Files.readString(file, StandardCharsets.UTF_8);
        // Цена снимается до отказа и сверяется с собой же после: смысл проверки в том, что
        // отвергнутая правка не оставила следа, а не в том, какой эта цена была вчера.
        Integer price = catalog.require("growth-fast").picks();

        assertThatThrownBy(() -> catalog.updatePicks(null, Map.of("growth-fast", 500)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> catalog.updatePicks(0, Map.of()))
                .isInstanceOf(ConflictException.class);

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(before);
        assertThat(catalog.require("growth-fast").picks()).isEqualTo(price);
    }

    @Test
    @DisplayName("Бюджет ниже цены готовой расы больше не запрещён")
    void budgetBelowAReadyRaceIsAllowedNow() throws IOException {
        // Здесь стояло обратное: бюджет ниже цены готовой расы ронял чтение файла, и правка
        // откатывалась. Требование отменено решением хозяина проекта (журнал, п. 3.70):
        // готовые расы НЕ ОБЯЗАНЫ быть равными по силе, их набор — портрет, а не покупка.
        // Бюджет теперь спрашивается только игроку, который расу собирает.
        Integer raceCost = catalog.raceTraits("PSILONS").stream()
                .mapToInt(trait -> catalog.require(trait).picks())
                .sum();
        Integer below = raceCost - 1;

        catalog.updatePicks(below, Map.of());

        assertThat(catalog.picksBudget()).isEqualTo(below);
        // И справочник по-прежнему читается: расы на месте, ни одна не потеряла сторон.
        assertThat(catalog.raceTraits("PSILONS")).isNotEmpty();
        JsonNode saved = new ObjectMapper().readTree(
                Files.readString(file, StandardCharsets.UTF_8));
        assertThat(saved.path("picks").asInt()).isEqualTo(below);
    }

    @Test
    @DisplayName("Файл сохраняет свой вид: два пробела отступа и «ключ»: значение")
    void keepsFileLayout() throws IOException {
        catalog.updatePicks(null, Map.of("money-rich", 7));

        String saved = Files.readString(file, StandardCharsets.UTF_8);
        // Правка одной цены не должна переписывать весь файл: справочник правят и руками,
        // а «диф на весь файл» прятал бы настоящее изменение.
        assertThat(saved).contains("\n  \"picks\": ");
        assertThat(saved).contains("\"code\": \"money-rich\"");
        assertThat(saved).doesNotContain("\"picks\" : ");
        // Концы строк — \n, как у остальных справочников игры: иначе правка одной цены
        // на Windows переписывала бы файл целиком.
        assertThat(saved).doesNotContain("\r\n");
    }

    @Test
    @DisplayName("Временный файл после записи не остаётся")
    void leavesNoTemporaryFile() throws IOException {
        catalog.updatePicks(null, Map.of("science-great", 5));

        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactly("race-traits.json");
        }
    }

    private Integer pick(JsonNode root, String code) {
        return option(root, code).path("picks").asInt();
    }

    private JsonNode option(JsonNode root, String code) {
        for (JsonNode group : root.path("groups")) {
            for (JsonNode option : group.path("options")) {
                if (code.equals(option.path("code").asText())) {
                    return option;
                }
            }
        }
        throw new IllegalStateException("В файле нет особенности " + code);
    }
}
