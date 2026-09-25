package com.moo3.server.service;

import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.PlanetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Разбитая оборона колонии — п. 8, п. 11.
 * <p>
 * Платформа в бою гибнет как корабль, но на карте она — ЗДАНИЕ, и разбитая звёздная база
 * должна исчезнуть с планеты. Иначе оборону нельзя подавить вовсе: к следующему бою она
 * возрождалась бы целой, и колония со станцией стала бы неприступной навсегда.
 * <p>
 * Отстроить её можно заново, обычной стройкой, — и это тоже правило оригинала: потерянная
 * база не возвращается сама, за неё платят второй раз.
 * <p>
 * Стоит отдельной службой, а не внутри боя, потому что бой о планетах не знает ничего: он
 * считает корабли на поле. Сюда же ведёт и обратная дорога — какое здание выставило какую
 * платформу ({@link OrbitalDefenceRules}).
 */
@Service
public class OrbitalDefenceService {

    private static final Logger log = LoggerFactory.getLogger(OrbitalDefenceService.class);

    private final PlanetRepository planetRepository;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final OrbitalDefenceRules rules;

    public OrbitalDefenceService(PlanetRepository planetRepository,
                                 PlanetBuildingRepository planetBuildingRepository,
                                 OrbitalDefenceRules rules) {
        this.planetRepository = planetRepository;
        this.planetBuildingRepository = planetBuildingRepository;
        this.rules = rules;
    }

    /**
     * Убирает с планет системы столько платформ этого вида, сколько их погибло в бою.
     * <p>
     * Орбитальная платформа у колонии одна, поэтому у каждой снимается своё здание. Наземные
     * батареи — дело другое: их у колонии до трёх, и какая именно разбита, бой не различает,
     * поэтому сносится первая из уцелевших по порядку справочника. Выбор этот всё равно
     * произволен, зато определён: партия обязана повторяться до последнего числа.
     *
     * @param hull  корпус платформы из справочника кораблей
     * @param count сколько их погибло
     */
    @Transactional
    public void destroy(UUID ownerId, UUID systemId, String hull, Integer count) {
        List<PlanetEntity> colonies = planetRepository.findAllByOwnerPlayerId(ownerId).stream()
                .filter(planet -> planet.getStarSystem() != null
                        && systemId.equals(planet.getStarSystem().getId()))
                // По ОРБИТЕ, а не по идентификатору: id это случайный UUID, и порядок по
                // нему между двумя прогонами одной партии разный, а от порядка зависит,
                // чья платформа погибнет первой. В пределах системы орбита уникальна и
                // задана самой галактикой.
                .sorted(Comparator.comparing(PlanetEntity::getOrbit))
                .toList();

        int left = count == null ? 0 : count;
        for (PlanetEntity colony : colonies) {
            if (left <= 0) {
                break;
            }
            List<PlanetBuildingEntity> built =
                    new ArrayList<>(planetBuildingRepository.findAllByPlanetId(colony.getId()));
            List<String> codes = OrbitalDefenceRules.SURFACE_HULL.equals(hull)
                    ? rules.surfaceBuildings()
                    : OrbitalDefenceRules.SHIELD_HULL.equals(hull)
                            ? rules.shieldBuildings()
                            : List.of(hull);

            for (String code : codes) {
                if (left <= 0) {
                    break;
                }
                PlanetBuildingEntity row = built.stream()
                        .filter(one -> code.equals(one.getBuildingCode()))
                        .findFirst()
                        .orElse(null);
                if (row == null) {
                    continue;
                }
                planetBuildingRepository.delete(row);
                built.remove(row);
                left--;
                log.info("Оборона колонии {} разбита: снято здание {}", colony.getName(), code);
            }
        }
    }
}
