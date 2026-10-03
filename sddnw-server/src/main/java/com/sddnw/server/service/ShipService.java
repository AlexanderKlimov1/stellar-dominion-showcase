package com.sddnw.server.service;

import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.dto.FleetDto;
import com.sddnw.server.dto.FleetGroupDto;
import com.sddnw.server.repository.PlanetRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

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
        List<FleetGroupDto> fleets = fleetService.fleetsOf(player);
        /*
          Дальностей у империи две — п. 8: своя у кораблей без дополнительных баков и своя
          у кораблей с баками. Карта рисует линию к звезде по списку достижимого, и списка
          поэтому тоже два: дальше базовой летит тот флот, чья дальность её превышает.
          Считать список на каждый флот незачем — это была бы одна и та же галактика,
          повторённая столько раз, сколько у империи флотов.
        */
        Integer base = fleetService.rangeParsecs(player);
        Integer far = fleets.stream()
                .map(FleetGroupDto::rangeParsecs)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(base);
        Set<UUID> reachable = fleetService.reachableSystems(game, player, base);
        Set<UUID> reachableFar = far.equals(base)
                ? reachable
                : fleetService.reachableSystems(game, player, far);
        return new FleetDto(
                race.shipAttackPercent(),
                race.shipDefensePercent(),
                shipDesignService.designs(player),
                fleets,
                base,
                List.copyOf(reachable),
                List.copyOf(reachableFar),
                fleetService.canRedirect(player),
                race.telepathic(),
                fleetService.commandUsedByPlayer(game.getId()).getOrDefault(player.getId(), 0),
                commandRules.capacity(planetRepository.countByOwnerPlayerId(player.getId()), race));
    }
}
