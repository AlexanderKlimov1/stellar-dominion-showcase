package com.sddnw.server.domain.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import com.sddnw.server.service.ResearchCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Технология, которую климат называет для терраформирования, есть в дереве — п. 4.1.2.1.
 * <p>
 * Код в {@link PlanetClimate} — ссылка в {@code tech.json}, и ничем, кроме этой проверки, она
 * не держится: сервер по ней ничего не считает, а клиент ищет по ней название технологии для
 * карточки планеты. Прежде здесь стояли заглавные имена перечисления
 * ({@code GAIA_TRANSFORMATION}), которые в дереве не находились никак, — и игрок читал в
 * карточке код вместо названия. Ни одна проверка этого не видела: код был, и он был строкой.
 */
class PlanetClimateTechTest {

    private final ResearchCatalog catalog = new ResearchCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    @Test
    @DisplayName("Каждый код технологии у климата находится в дереве технологий")
    void everyTerraformingTechnologyIsInTheTree() {
        Arrays.stream(PlanetClimate.values())
                .filter(climate -> climate.getRequiredTechCode() != null)
                .forEach(climate -> assertThat(catalog.places())
                        .as("технология «%s» климата %s есть в tech.json",
                                climate.getRequiredTechCode(), climate)
                        .containsKey(climate.getRequiredTechCode()));
    }

    @Test
    @DisplayName("Требование стоит у каждой ступени цепочки, кроме ядовитой и вершины")
    void everyStepOfTheChainNamesItsTechnology() {
        // Ядовитый климат молчит намеренно: его технологии в дереве этой игры нет (см.
        // javadoc поля). Вершине цепочки переделываться некуда.
        PlanetClimate.terraformChain().stream()
                .filter(climate -> climate != PlanetClimate.TOXIC && climate.next().isPresent())
                .forEach(climate -> assertThat(climate.getRequiredTechCode())
                        .as("у климата %s названа технология перехода", climate)
                        .isNotNull());
    }
}
