package com.moo3.server.service;

import com.moo3.server.dto.BuildingDto;
import com.moo3.server.dto.RaceDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Каталоги рас (п. 5, 7), способностей (п. 6), кораблей и вооружения (п. 8) и зданий (п. 10).
 * <p>
 * Пункты спецификации пока без детализации, поэтому каталоги наполнены минимальными
 * заглушками. Точка расширения — новые changeSet Liquibase и поля соответствующих сущностей;
 * контракт REST при этом не меняется.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final BuildingCatalog buildingCatalog;
    private final RaceTraitCatalog raceTraitCatalog;

    public CatalogService(BuildingCatalog buildingCatalog, RaceTraitCatalog raceTraitCatalog) {
        this.buildingCatalog = buildingCatalog;
        this.raceTraitCatalog = raceTraitCatalog;
    }

    /**
     * п. 5 / п. 7 — расы. Раса приходит вместе со своими сторонами: в MOO II готовая раса
     * и есть набор сторон, и выбирают её именно по ним, а не по цвету на карте.
     */
    public List<RaceDto> races() {
        return raceTraitCatalog.readyRaces().stream()
                .map(race -> new RaceDto(
                        race.code(),
                        race.name(),
                        race.description(),
                        race.homeClimate(),
                        race.color(),
                        race.traits()))
                .toList();
    }

    /** п. 10 — здания колоний: справочник читается из файла описания зданий. */
    public List<BuildingDto> buildings() {
        return buildingCatalog.all().stream()
                .map(building -> new BuildingDto(
                        building.code(),
                        building.name(),
                        building.description(),
                        building.cost(),
                        building.upkeep(),
                        building.requiredTechCode()))
                .toList();
    }

}
