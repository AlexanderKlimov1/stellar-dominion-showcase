package com.moo3.server.service;

import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.TerraformingTech;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

/**
 * Терраформирование — п. 4.1.2.1.
 * <p>
 * Цепочка Toxic → Radiated → Barren → Desert → Tundra → Arid → Swamp → Ocean → Terran → Gaya.
 * Технология Terraforming работает только на участке Barren → Terran; выход из Toxic требует
 * Toxic Waste Elimination, выход из Radiated — Radiation Shield, переход в Gaya —
 * Gaya Transformation.
 */
@Service
public class TerraformingService {

    /** Следующая ступень цепочки, если планета не на её вершине. */
    public Optional<PlanetClimate> nextClimate(PlanetClimate current) {
        return current.next();
    }

    /** Технология, открывающая переход из текущего климата на следующую ступень. */
    public Optional<TerraformingTech> requiredTech(PlanetClimate current) {
        if (current.next().isEmpty()) {
            return Optional.empty();
        }
        return switch (current) {
            case TOXIC -> Optional.of(TerraformingTech.TOXIC_WASTE_ELIMINATION);
            case RADIATED -> Optional.of(TerraformingTech.RADIATION_SHIELD);
            case BARREN, DESERT, TUNDRA, ARID, SWAMP, OCEAN -> Optional.of(TerraformingTech.TERRAFORMING);
            case TERRAN -> Optional.of(TerraformingTech.GAIA_TRANSFORMATION);
            case GAIA, ASTEROID_BELT, GAS_GIANT -> Optional.empty();
        };
    }

    /** Доступно ли терраформирование при текущем наборе изученных технологий. */
    public Boolean canTerraform(PlanetClimate current, Collection<String> researchedTechCodes) {
        return requiredTech(current)
                .map(tech -> researchedTechCodes.contains(tech.name()))
                .orElse(Boolean.FALSE);
    }

    /**
     * Один шаг терраформирования. Если технологии не хватает или планета вне цепочки,
     * климат остаётся прежним.
     */
    public PlanetClimate terraform(PlanetClimate current, Collection<String> researchedTechCodes) {
        if (!canTerraform(current, researchedTechCodes)) {
            return current;
        }
        return current.next().orElse(current);
    }
}
