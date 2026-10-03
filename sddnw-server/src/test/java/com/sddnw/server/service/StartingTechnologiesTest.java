package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import com.sddnw.server.dto.ResearchCategoryDto;
import com.sddnw.server.dto.ResearchLevelDto;
import com.sddnw.server.dto.ResearchOptionDto;
import com.sddnw.server.dto.ResearchTreeDto;
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
 * <b>С 01.10.2026 список пуст</b> (решение хозяина проекта): все уровни, и первые тоже,
 * исследуются. Когда-то выдача лечила ИИ, чьё устремление не любит химию и который поэтому
 * не строил кораблей вовсе (зерно 777: шесть перелётов, ноль знакомств за 150 ходов); с тех
 * пор ИИ ведёт науку по нуждам (`AiEmpireService.wantedTechnologies`), и выдача ему не
 * нужна. Проверка держит то, на чём это стоит: путь к кораблям короткий.
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
    @DisplayName("Стартовых технологий нет: первые уровни тоже исследуются")
    void nothingIsGrantedAtStart() {
        // Решение хозяина проекта (01.10.2026): уровни за 50 очков, выданные даром, игрок
        // видел открытыми и не понимал, почему их нельзя исследовать. Механизм выдачи
        // остался (`ResearchService.grantStarting`), пуст только список в справочнике.
        assertThat(tree.startingLevels()).isEmpty();
        assertThat(startingLevels()).isEmpty();
    }

    @Test
    @DisplayName("До кораблей — первые уровни Power и Chemistry, а не середина дерева")
    void shipbuildingIsOneLevelAway() {
        // Кораблестроение требует ДВУХ разделов разом: Power даёт двигатель, Chemistry —
        // топливо. Без стартовой выдачи до первого корабля надо изучить оба, и проверка держит
        // то, что путь короткий: оба лежат на ПЕРВОМ уровне своего раздела. Уедь один из них
        // выше — и империя, особенно ИИ, сидела бы на родной звезде полпартии (так уже было:
        // зерно 777, восемь империй, 150 ходов — шесть перелётов, ноль знакомств).
        Set<String> firstLevels = tree.categories().stream()
                .filter(category -> category.code().equals("power") || category.code().equals("chemistry"))
                .map(category -> catalog.level(category.code(), 1))
                .flatMap(level -> level.options().stream())
                .map(ResearchOptionDto::code)
                .collect(Collectors.toSet());
        assertThat(firstLevels).contains(ShipDesignRules.DRIVE_TECH, ShipDesignRules.FUEL_TECH);
        assertThat(new ShipDesignRules().shipbuildingAvailable(firstLevels)).isTrue();
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
