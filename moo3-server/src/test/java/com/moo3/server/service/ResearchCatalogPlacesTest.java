package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.dto.ResearchCategoryDto;
import com.moo3.server.dto.ResearchLevelDto;
import com.moo3.server.dto.ResearchOptionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дерево технологий картой «код — место» — п. 15.
 * <p>
 * Карта заведена ради обмена технологиями у ИИ: он перебирает за разговор десятки кодов, и
 * поиск проходом по дереву на каждый код стоил тысяч проходов за ход. Но у карты есть
 * опасность, которой не было у прохода: <b>повторяющийся код молча теряется</b> — проход
 * возвращал первое совпадение, а карта оставляет последнее. Поэтому здесь проверяется не
 * «карта работает», а ровно то, что могло бы разъехаться: число записей и совпадение
 * ответов карты с ответами {@link ResearchCatalog#place}.
 */
class ResearchCatalogPlacesTest {

    private static GameProperties properties() {
        return new GameProperties(8, 4, 1.5, "star-names.txt",
                "../resources/Technologies/tech.json",
                "../resources/Buildings/buildings.json",
                "../resources/Races/race-traits.json",
                "../resources/Ships/ship-components.json",
                "../resources/Leaders/leaders.json");
    }

    private final ResearchCatalog catalog = new ResearchCatalog(properties(), new ObjectMapper());

    @Test
    @DisplayName("В карте столько записей, сколько технологий в дереве: кодов-двойников нет")
    void everyTechnologyHasItsOwnCode() {
        long technologies = catalog.tree().categories().stream()
                .flatMap(category -> category.levels().stream())
                .flatMap(level -> level.options().stream())
                .count();
        assertThat(catalog.places()).hasSize((int) technologies);
    }

    @Test
    @DisplayName("Карта отвечает то же, что поиск по одному коду, — и место, и цену уровня")
    void mapAgreesWithSingleLookup() {
        Map<String, ResearchCatalog.TechnologyPlace> places = catalog.places();
        for (ResearchCategoryDto category : catalog.tree().categories()) {
            for (ResearchLevelDto level : category.levels()) {
                for (ResearchOptionDto option : level.options()) {
                    ResearchCatalog.TechnologyPlace place = places.get(option.code());
                    assertThat(place)
                            .as("технология %s есть в карте", option.code())
                            .isNotNull();
                    assertThat(place).isEqualTo(catalog.place(option.code()));
                    assertThat(place.categoryCode()).isEqualTo(category.code());
                    assertThat(place.levelOrder()).isEqualTo(level.order());
                    assertThat(place.levelCost()).isEqualTo(level.cost());
                }
            }
        }
    }

    @Test
    @DisplayName("Завод и лаборатория лежат на уровнях с выбором — на этом стоит цена неизобретательности")
    void economyTechnologiesShareTheirLevelWithOthers() {
        for (String code : new String[]{"automated-factory", "research-laboratory"}) {
            ResearchCatalog.TechnologyPlace place = catalog.place(code);
            long neighbours = catalog.tree().categories().stream()
                    .filter(category -> category.code().equals(place.categoryCode()))
                    .flatMap(category -> category.levels().stream())
                    .filter(level -> level.order().equals(place.levelOrder()))
                    .flatMap(level -> level.options().stream())
                    .count();
            assertThat(neighbours)
                    .as("уровень технологии %s даёт выбор, и неизобретательная раса бросает"
                            + " на нём жребий", code)
                    .isGreaterThan(1);
        }
    }
}
