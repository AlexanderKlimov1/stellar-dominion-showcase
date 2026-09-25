package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.dto.ResearchCategoryDto;
import com.moo3.server.dto.ResearchLevelDto;
import com.moo3.server.dto.ResearchOptionDto;
import com.moo3.server.dto.ResearchTreeDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Технологии, с которыми империя входит в партию, — п. 9.
 * <p>
 * Связь между данными и кодом здесь одна: записи {@code starting_techs.all_races} называют
 * уровни ссылками «раздел:номер», и {@link ResearchCatalog#startingLevels()} их разбирает.
 * Раньше там стояли НАЗВАНИЯ («Nuclear Fission (Power)»), сверявшиеся с деревом текстом, —
 * и эта сверка ломалась дважды: от перевода названий и от любого их изменения, причём молча.
 * Теперь неверная ссылка — отказ при чтении файла, а проверка держит то, ради чего список
 * вообще есть.
 * <p>
 * Цена такой поломки измерена: пока стартовые технологии никому не выдавались, империя
 * ИИ, чьё устремление не любит химию, не строила ни одного корабля за всю партию — на
 * зерне 777 у восьми империй за 150 ходов вышло шесть перелётов, ноль знакомств и ноль
 * войн. Поэтому проверка держит не только сам список, но и то, ради чего он есть.
 */
class StartingTechnologiesTest {

    private static GameProperties properties() {
        return new GameProperties(8, 4, 1.5, "star-names.txt",
                "../resources/Technologies/tech.json",
                "../resources/Buildings/buildings.json",
                "../resources/Races/race-traits.json",
                "../resources/Ships/ship-components.json",
                "../resources/Leaders/leaders.json");
    }

    private final ResearchCatalog catalog = new ResearchCatalog(properties(), new ObjectMapper());
    private final ResearchTreeDto tree = catalog.tree();

    /** Уровни, которые выдаются на старте: ровно тем путём, каким их находит выдача. */
    private List<ResearchLevelDto> startingLevels() {
        return catalog.startingLevels().stream()
                .map(starting -> catalog.level(starting.categoryCode(), starting.levelOrder()))
                .toList();
    }

    @Test
    @DisplayName("Каждая стартовая ссылка дерева находит свой уровень")
    void everyStartingTechResolves() {
        assertThat(tree.startingLevels()).isNotEmpty();
        assertThat(startingLevels()).hasSameSizeAs(tree.startingLevels());
        // И все они первые: империя начинает партию у подножия лестниц, а не посередине.
        assertThat(startingLevels().stream().map(ResearchLevelDto::order).distinct())
                .containsExactly(1);
    }

    @Test
    @DisplayName("Со стартовыми технологиями империя строит корабли с первого хода")
    void shipbuildingWorksFromTheFirstTurn() {
        Set<String> known = startingLevels().stream()
                .flatMap(level -> level.options().stream())
                .map(ResearchOptionDto::code)
                .collect(Collectors.toSet());

        // Кораблестроение требует ДВУХ разделов разом: Power даёт двигатель, Chemistry —
        // топливо. Пока хоть одного нет, колония не поднимет ни фрегата, ни колониального
        // корабля, сколько бы она их ни изучала.
        assertThat(known).contains(ShipDesignRules.DRIVE_TECH, ShipDesignRules.FUEL_TECH);
        assertThat(new ShipDesignRules().shipbuildingAvailable(known)).isTrue();
    }

    @Test
    @DisplayName("Стартовые уровни выдаются целиком, без выбора")
    void startingLevelsAreGeneral() {
        // Выбирать на них нечего: в MOO II это общие уровни дерева, и выдача кладёт игроку
        // все их технологии разом. Стань такой уровень обычным — половина выданного
        // оказалась бы подарком, которого игрок не выбирал.
        assertThat(startingLevels()).allMatch(ResearchLevelDto::general);
    }

    @Test
    @DisplayName("Ссылки в списке называют настоящие разделы дерева")
    void startingTechsNameRealCategories() {
        Set<String> categories = tree.categories().stream()
                .map(ResearchCategoryDto::code)
                .collect(Collectors.toSet());
        for (String starting : tree.startingLevels()) {
            // Ссылка — «раздел:номер»; названия в ней нет вовсе, и переводить её нечем.
            assertThat(starting).matches("[a-z_]+:[0-9]+");
            String category = starting.substring(0, starting.indexOf(':'));
            assertThat(categories)
                    .withFailMessage("стартовый уровень «%s» называет раздел, которого нет", starting)
                    .contains(category);
        }
    }
}
