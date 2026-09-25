package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.dto.FleetDto;
import com.moo3.server.repository.PlanetRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Корабли империи — п. 8: сводка «что империя может строить и что у неё уже летает».
 * <p>
 * Проекты кораблей живут в {@link ShipDesignService} (окно дизайна MOO II), сами корабли и
 * их перелёты — в {@link FleetService}. Здесь остаётся то, ради чего экран флота делает
 * один запрос вместо трёх: расовые проценты, действующие проекты и флоты по системам.
 * <p>
 * Раньше здесь же считались атака и защита «проекта» — корпус с лучшей пушкой каталога.
 * Считать это больше не нужно: у корабля есть настоящий проект, и его характеристики
 * приходят из {@link ShipDesignRules}.
 */
@Service
public class ShipService {

    private final RaceService raceService;
    private final ShipDesignService shipDesignService;
    private final FleetService fleetService;
    private final CommandRules commandRules;
    private final PlanetRepository planetRepository;

    public ShipService(RaceService raceService,
                       CommandRules commandRules,
                       PlanetRepository planetRepository,
                       ShipDesignService shipDesignService,
                       FleetService fleetService) {
        this.raceService = raceService;
        this.commandRules = commandRules;
        this.planetRepository = planetRepository;
        this.shipDesignService = shipDesignService;
        this.fleetService = fleetService;
    }

    /**
     * Флот игрока и его проекты кораблей — п. 8.
     * <p>
     * Партия приходит отдельным параметром, а не из {@code player.getGame()}: игрок
     * отсоединён от сессии, и его ссылка на игру — ленивая заглушка, с которой читается
     * только идентификатор. А дальность полёта считается по размеру галактики.
     */
    public FleetDto fleet(GameEntity game, PlayerEntity player) {
        RaceEffects race = raceService.effects(player);
        return new FleetDto(
                race.shipAttackPercent(),
                race.shipDefensePercent(),
                shipDesignService.designs(player),
                fleetService.fleetsOf(player),
                fleetService.rangeParsecs(player),
                List.copyOf(fleetService.reachableSystems(game, player)),
                fleetService.canRedirect(player),
                race.telepathic(),
                fleetService.commandUsedByPlayer(game.getId()).getOrDefault(player.getId(), 0),
                commandRules.capacity(planetRepository.countByOwnerPlayerId(player.getId()), race));
    }
}
