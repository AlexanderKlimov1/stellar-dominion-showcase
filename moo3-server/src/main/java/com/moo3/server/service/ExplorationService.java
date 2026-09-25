package com.moo3.server.service;

import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerExploredSystemEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.ScannerTech;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.PlayerExploredSystemRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Collection;
import java.util.HashMap;

/**
 * Разведка звёздных систем — п. 15.
 * <p>
 * Сами звёзды видны все: светило заметно из любой точки галактики, поэтому карта отдаётся
 * целиком. Разведанность решает, что игрок о системе знает — её название, состав планет
 * и чьи колонии в ней стоят.
 * <p>
 * <b>Разведывают только корабли.</b> Система становится разведанной, когда в неё приходит
 * флот игрока ({@link FleetService#move}), — как в MOO2, где к звезде надо долететь.
 * Разведаны, кроме того, свои системы: там, где стоит колония, разведывать нечего.
 * Знание не теряется: улетел флот — система остаётся известной.
 * <p>
 * <b>Сканеры показывают присутствие, а не состав.</b> Изученные технологии сканеров
 * ({@link ScannerTech}) дают дальность, на которой империя замечает чужие колонии и флоты.
 * Это ровно один факт — «там кто-то есть»; какие в системе планеты, сканер не расскажет,
 * за этим по-прежнему нужно лететь. Считается от своих колоний и своих флотов: приборы
 * стоят там, где есть кому смотреть.
 * <p>
 * Приход флота в чужую систему — это ещё и знакомство: если в ней стоят чужие колонии,
 * империи узнают друг о друге, и с ними становится возможна дипломатия
 * ({@link DiplomacyService}).
 * <p>
 * Флаг «Показать галактику» («Инфо» в интерфейсе) объявляет разведанными все системы —
 * он остаётся отладочным: с ним карта видна целиком, но знакомств не случается.
 */
@Service
public class ExplorationService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationService.class);

    private final PlayerExploredSystemRepository exploredRepository;
    private final PlayerTechnologyRepository technologyRepository;
    private final FleetRepository fleetRepository;
    private final DiplomacyService diplomacyService;
    private final RaceService raceService;

    public ExplorationService(PlayerExploredSystemRepository exploredRepository,
                              PlayerTechnologyRepository technologyRepository,
                              FleetRepository fleetRepository,
                              DiplomacyService diplomacyService,
                              RaceService raceService) {
        this.exploredRepository = exploredRepository;
        this.technologyRepository = technologyRepository;
        this.fleetRepository = fleetRepository;
        this.diplomacyService = diplomacyService;
        this.raceService = raceService;
    }

    /**
     * Разведанные игроком системы: об остальных известны только положение и цвет звезды.
     * <p>
     * Союзники делятся разведкой — п. 15: что разведал один, знают оба, пока союз в силе.
     */
    public Set<UUID> exploredBy(List<StarSystemEntity> systems, PlayerEntity player) {
        // Всевидящей расе (п. 7) разведка не нужна вовсе: она видит галактику с первого
        // хода и до последнего — ни флот посылать, ни сканеры изучать ей незачем.
        if (Boolean.TRUE.equals(raceService.effects(player).omniscient())) {
            return systems.stream().map(StarSystemEntity::getId).collect(Collectors.toUnmodifiableSet());
        }

        Set<UUID> knowers = new HashSet<>(diplomacyService.allies(player.getId()));
        knowers.add(player.getId());

        Set<UUID> visited = knowers.stream()
                .flatMap(knower -> exploredRepository.findAllByPlayerId(knower).stream())
                .map(PlayerExploredSystemEntity::getStarSystemId)
                .collect(Collectors.toSet());

        Set<UUID> explored = new HashSet<>(visited);
        systems.stream()
                // Свои системы разведаны по факту владения, системы союзника — по союзу:
                // он показывает и то, куда летал его флот, и то, где стоят его колонии.
                .filter(system -> knowers.stream().anyMatch(knower -> owns(system, knower))
                        || system.getId().equals(player.getHomeSystemId()))
                .map(StarSystemEntity::getId)
                .forEach(explored::add);
        return Set.copyOf(explored);
    }

    /**
     * То же самое сразу для многих игроков — одной выборкой на всех.
     * <p>
     * Заведено для фазы ИИ: с тех пор как империя ИИ видит галактику НЕ ЦЕЛИКОМ, а как
     * человек, разведка нужна каждому решению — куда селиться, где ставить заставу, на
     * кого идти. Спрашивать её по одному {@link #exploredBy} значило бы делать по три
     * запроса на империю каждый ход изнутри посчитанного хода, а это в проекте прямо
     * запрещено: любой запрос там заставляет Hibernate сбросить в базу всё, что ход успел
     * изменить.
     * <p>
     * Всевидящей расе (п. 7) по-прежнему отдаётся вся галактика — в этом и состоит её
     * сторона; союзники по-прежнему делятся разведкой (п. 15).
     *
     * @return игрок → системы, которые он знает
     */
    public Map<UUID, Set<UUID>> exploredByAll(List<StarSystemEntity> systems,
                                              Collection<PlayerEntity> players) {
        if (players.isEmpty()) {
            return Map.of();
        }
        Set<UUID> all = systems.stream()
                .map(StarSystemEntity::getId)
                .collect(Collectors.toUnmodifiableSet());

        List<UUID> ids = players.stream().map(PlayerEntity::getId).toList();
        Map<UUID, Set<UUID>> visited = new HashMap<>();
        exploredRepository.findAllByPlayerIdIn(ids).forEach(row -> visited
                .computeIfAbsent(row.getPlayerId(), one -> new HashSet<>())
                .add(row.getStarSystemId()));
        Map<UUID, Set<UUID>> alliesOf = diplomacyService.alliesOfAll(ids);

        Map<UUID, Set<UUID>> known = new HashMap<>();
        for (PlayerEntity player : players) {
            if (Boolean.TRUE.equals(raceService.effects(player).omniscient())) {
                known.put(player.getId(), all);
                continue;
            }
            Set<UUID> knowers = new HashSet<>(alliesOf.getOrDefault(player.getId(), Set.of()));
            knowers.add(player.getId());

            Set<UUID> explored = new HashSet<>();
            knowers.forEach(knower -> explored.addAll(visited.getOrDefault(knower, Set.of())));
            systems.stream()
                    .filter(system -> knowers.stream().anyMatch(knower -> owns(system, knower))
                            || system.getId().equals(player.getHomeSystemId()))
                    .map(StarSystemEntity::getId)
                    .forEach(explored::add);
            known.put(player.getId(), Set.copyOf(explored));
        }
        return known;
    }

    /** Знает ли игрок эту систему: посещал ли её флот. */
    public Boolean isExplored(PlayerEntity player, UUID systemId) {
        return exploredRepository.existsByPlayerIdAndStarSystemId(player.getId(), systemId);
    }

    /**
     * Записывает разведанную флотом систему и знакомит с её хозяевами — п. 15.
     * <p>
     * Вызывается, когда флот приходит в систему. Повторный приход ничего не меняет:
     * знание о системе не теряется и не удваивается.
     *
     * @return империи, с которыми игрок познакомился именно этим приходом
     */
    @Transactional
    public List<PlayerEntity> exploreBy(PlayerEntity player, StarSystemEntity system, Integer turn) {
        if (exploredRepository.existsByPlayerIdAndStarSystemId(player.getId(), system.getId())) {
            return List.of();
        }

        PlayerExploredSystemEntity explored = new PlayerExploredSystemEntity();
        explored.setPlayerId(player.getId());
        explored.setStarSystemId(system.getId());
        explored.setExploredTurn(turn);
        exploredRepository.save(explored);

        List<PlayerEntity> met = diplomacyService.meetOwnersOf(player, system, turn);
        log.info("Флот игрока {} разведал систему {}: познакомился с {} империями",
                player.getName(), system.getName(), met.size());
        return met;
    }

    /**
     * Системы, накрытые сканерами империи — п. 15.
     * <p>
     * Сканер видит от своих колоний и своих флотов на дальность лучшей изученной
     * технологии. Разведанные системы в этот список не попадают: о них и так известно
     * всё, и «сканер видит присутствие» им нечего добавить.
     */
    public Set<UUID> scannedBy(List<StarSystemEntity> systems, PlayerEntity player) {
        Integer range = scannerRange(player);
        if (range <= 0) {
            return Set.of();
        }

        List<StarSystemEntity> eyes = watchPosts(systems, player);
        if (eyes.isEmpty()) {
            return Set.of();
        }

        long squared = (long) range * range;
        return systems.stream()
                .filter(system -> eyes.stream().anyMatch(eye -> distanceSquared(eye, system) <= squared))
                .map(StarSystemEntity::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Системы, где сканер замечает чужое присутствие — п. 15: чужая колония или чужой флот.
     * <p>
     * Ровно один факт: «там кто-то есть». Ни чей это флот, ни что за планеты в системе,
     * сканер не сообщает — за этим нужно лететь.
     */
    public Set<UUID> occupiedBy(List<StarSystemEntity> systems, PlayerEntity player, Set<UUID> scanned) {
        if (scanned.isEmpty()) {
            return Set.of();
        }

        // Флот в пути сканеру не виден: он не стоит в системе вылета — п. 8.
        List<FleetEntity> foreign = fleetRepository.findAllByGameId(player.getGame().getId()).stream()
                .filter(fleet -> !fleet.getOwnerPlayerId().equals(player.getId()))
                .filter(fleet -> fleet.getShips() > 0)
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .toList();

        // Скрытные корабли (п. 7) сканеру не показываются: издали система с таким флотом
        // выглядит пустой. Не прячутся они от всевидящей расы — она «обнаруживает даже
        // невидимые корабли», — и от того, кто стоит в той же системе сам.
        Set<UUID> hidden = Boolean.TRUE.equals(raceService.effects(player).omniscient())
                ? Set.of()
                : stealthyOwners(foreign);
        Set<UUID> nearby = watchPosts(systems, player).stream()
                .map(StarSystemEntity::getId)
                .collect(Collectors.toSet());

        Set<UUID> withForeignFleets = foreign.stream()
                .filter(fleet -> !hidden.contains(fleet.getOwnerPlayerId())
                        || nearby.contains(fleet.getStarSystemId()))
                .map(FleetEntity::getStarSystemId)
                .collect(Collectors.toSet());

        return systems.stream()
                .filter(system -> scanned.contains(system.getId()))
                .filter(system -> withForeignFleets.contains(system.getId())
                        || hasForeignColony(system, player.getId()))
                .map(StarSystemEntity::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Империи, чьи корабли не видны на карте — п. 7: скрытные корабли.
     * <p>
     * Расы спрашиваются только у хозяев этих флотов и одной выборкой: сканеры считаются
     * и внутри посчитанного хода, а выборка на игрока там стоит дорого.
     */
    private Set<UUID> stealthyOwners(List<FleetEntity> fleets) {
        Set<UUID> owners = fleets.stream()
                .map(FleetEntity::getOwnerPlayerId)
                .collect(Collectors.toSet());
        return raceService.effectsByPlayer(owners).entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue().stealthyShips()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Дальность сканеров империи в ГРЕ; 0 — сканеров нет. */
    public Integer scannerRange(PlayerEntity player) {
        Set<String> technologies = technologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
        return ScannerTech.bestRange(technologies);
    }

    /**
     * Откуда империя смотрит: системы со своими колониями и системы со своими флотами.
     * Родная система считается всегда — с неё империя и начинает.
     */
    private List<StarSystemEntity> watchPosts(List<StarSystemEntity> systems, PlayerEntity player) {
        // Летящий флот смотровой площадкой не работает: система вылета им уже покинута.
        Set<UUID> withOwnFleets = fleetRepository.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .map(FleetEntity::getStarSystemId)
                .collect(Collectors.toSet());

        return systems.stream()
                .filter(system -> owns(system, player.getId())
                        || system.getId().equals(player.getHomeSystemId())
                        || withOwnFleets.contains(system.getId()))
                .toList();
    }

    /** Квадрат расстояния между системами: корень для сравнения с дальностью не нужен. */
    private double distanceSquared(StarSystemEntity first, StarSystemEntity second) {
        double dx = first.getXParsec() - second.getXParsec();
        double dy = first.getYParsec() - second.getYParsec();
        return dx * dx + dy * dy;
    }

    private Boolean hasForeignColony(StarSystemEntity system, UUID playerId) {
        return system.getPlanets().stream()
                .map(PlanetEntity::getOwnerPlayerId)
                .anyMatch(owner -> owner != null && !owner.equals(playerId));
    }

    private Boolean owns(StarSystemEntity system, UUID playerId) {
        return system.getPlanets().stream()
                .map(PlanetEntity::getOwnerPlayerId)
                .anyMatch(playerId::equals);
    }
}
