package com.sddnw.server.galaxy;

import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.MineralRichness;
import com.sddnw.server.domain.enums.PlanetClimate;
import com.sddnw.server.domain.enums.PlanetSize;
import com.sddnw.server.service.PopulationCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * У Wardenhold есть где поселиться — backlog-promo, пункт 3: держат звезду колонией, и
 * галактика, где у неё один газовый гигант, закрывала бы третий путь к победе для всех.
 */
class WardenholdPlanetsTest {

    private final PlanetGenerator planets = new PlanetGenerator(new PopulationCalculator());
    private final GalaxyGenerator generator = new GalaxyGenerator(null, null, planets);

    private StarSystemEntity system(PlanetClimate... climates) {
        StarSystemEntity system = new StarSystemEntity();
        system.setName("Wardenhold");
        int orbit = 1;
        for (PlanetClimate climate : climates) {
            system.addPlanet(planets.planet("Wardenhold", new PlanetGenerator.PlanetSpec(
                    orbit++, PlanetSize.MEDIUM, climate, MineralRichness.RICH)));
        }
        return system;
    }

    @Test
    @DisplayName("Один газовый гигант у особой звезды становится пригодной планетой")
    void gasGiantOnlyBecomesSettleable() {
        StarSystemEntity system = system(PlanetClimate.GAS_GIANT);

        generator.settleableWardenhold(system);

        PlanetEntity planet = system.getPlanets().get(0);
        assertThat(planet.getClimate().getColonizable()).isTrue();
        assertThat(planet.getMaxPopulation()).isPositive();
        assertThat(planet.getPlanetSize()).isEqualTo(PlanetSize.MEDIUM);
    }

    @Test
    @DisplayName("Если пригодная планета уже есть, система не меняется")
    void settleableSystemIsLeftAlone() {
        StarSystemEntity system = system(PlanetClimate.GAS_GIANT, PlanetClimate.TUNDRA);

        generator.settleableWardenhold(system);

        assertThat(system.getPlanets().get(0).getClimate()).isEqualTo(PlanetClimate.GAS_GIANT);
        assertThat(system.getPlanets().get(1).getClimate()).isEqualTo(PlanetClimate.TUNDRA);
    }
}
