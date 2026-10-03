package com.sddnw.server.service;

import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.FleetShipEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.domain.enums.DiplomacyTreaty;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.ScannerTech;
import com.sddnw.server.domain.enums.ShipRole;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.FleetRepository;
import com.sddnw.server.repository.FleetShipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Предупреждение об угрозе ДО потери — backlog-promo, пункт 9.
 * <p>
 * Потеря ощущается вдвое сильнее равной находки, а потеря флота или колонии — самый частый
 * миг, когда партию бросают. Бой и захват игрок узнавал из итогов хода, когда что-либо
 * делать уже поздно. Теперь, заметив вражеский флот, летящий к его системе, он узнаёт об
 * этом заранее — с силой флота против своей обороны и числом ходов на ответ: выкупить
 * оборону, стянуть флот, построить казармы.
 * <p>
 * Предупреждают <b>только о том, что действительно грозит</b>: с хозяином флота идёт война
 * и договор не связывает ему руки, а флот сильнее обороны системы (свои стоящие флоты плюс
 * платформы колоний — той же мерой, что решит бой «авто») или везёт десант. Флот слабее
 * обороны и без десанта — не угроза, и строка о нём была бы шумом.
 * <p>
 * <b>Только людям:</b> у ИИ то же правило давно есть своё ({@code AiEmpireService.alarmSystems}),
 * а итоги хода ему читать незачем. Когда флот заметен — решает {@link ThreatRules}: знать
 * больше, чем видели бы глаза игрока, предупреждение не вправе. Скрытные корабли (п. 7)
 * незаметны вовсе, кроме как всевидящей расе: в этом и состоит их сторона.
 * <p>
 * Выборки здесь — одна на ход (летящие флоты партии) и по одной на угрожающий флот; угроза
 * человеку — событие редкое, и запрос «на флот» тут дешевле общего контекста боя.
 */
@Service
public class ThreatWarningService {

    private static final Logger log = LoggerFactory.getLogger(ThreatWarningService.class);

    private final FleetRepository fleetRepository;
    private final FleetShipRepository fleetShipRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final FleetService fleetService;
    private final EncounterService encounterService;
    private final ShipDesignService shipDesignService;
    private final RaceService raceService;

    public ThreatWarningService(FleetRepository fleetRepository,
                                FleetShipRepository fleetShipRepository,
                                DiplomacyRelationRepository relationRepository,
                                FleetService fleetService,
                                EncounterService encounterService,
                                ShipDesignService shipDesignService,
                                RaceService raceService) {
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.relationRepository = relationRepository;
        this.fleetService = fleetService;
        this.encounterService = encounterService;
        this.shipDesignService = shipDesignService;
        this.raceService = raceService;
    }

    /** Предупредить людей о вражеских флотах, замеченных на этом ходу впервые. */
    public void warn(TurnContext context) {
        List<PlayerEntity> humans = context.players().stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .toList();
        if (humans.isEmpty()) {
            return;
        }
        List<FleetEntity> flying = fleetRepository
                .findAllByGameIdAndTargetSystemIdIsNotNull(context.game().getId()).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .toList();
        if (flying.isEmpty()) {
            return;
        }

        Integer turn = context.turn();
        Map<UUID, StarSystemEntity> systems = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, Function.identity()));
        Map<UUID, PlayerEntity> players = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        Map<UUID, Map<UUID, DiplomacyRelationEntity>> relations = relationRepository
                .findAllByPlayerIdIn(humans.stream().map(PlayerEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(DiplomacyRelationEntity::getPlayerId,
                        Collectors.toMap(DiplomacyRelationEntity::getOtherPlayerId,
                                Function.identity(), (first, second) -> first)));
        Map<UUID, RaceEffects> races = raceService.effectsByPlayer(players.keySet());
        Map<UUID, ShipDesignEntity> designs = null;

        for (PlayerEntity human : humans) {
            Set<UUID> ownSystems = context.coloniesOf(human.getId()).stream()
                    .map(context::systemOf)
                    .collect(Collectors.toSet());
            if (ownSystems.isEmpty()) {
                continue;
            }
            List<FleetEntity> ownFleets = fleetRepository.findAllByOwnerPlayerId(human.getId());
            List<StarSystemEntity> posts = watchPosts(ownSystems, ownFleets, human, systems);
            Integer scanRange = ScannerTech.bestRange(context.colonyContext().technologiesByOwner()
                    .getOrDefault(human.getId(), Set.of()));
            Boolean omniscient = Boolean.TRUE.equals(
                    races.getOrDefault(human.getId(), RaceEffects.NONE).omniscient());

            for (FleetEntity fleet : flying) {
                if (!ownSystems.contains(fleet.getTargetSystemId())
                        || !hostile(relations.getOrDefault(human.getId(), Map.of())
                                .get(fleet.getOwnerPlayerId()))) {
                    continue;
                }
                if (!omniscient && Boolean.TRUE.equals(races.getOrDefault(fleet.getOwnerPlayerId(),
                        RaceEffects.NONE).stealthyShips())) {
                    continue;
                }
                if (!firstNoticed(fleet, turn, posts, scanRange, systems)) {
                    continue;
                }

                PlayerEntity owner = players.get(fleet.getOwnerPlayerId());
                if (owner == null) {
                    continue;
                }
                if (designs == null) {
                    designs = shipDesignService.designsOfGame(context.game().getId());
                }
                List<FleetShipEntity> rows = fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId());
                Map<UUID, ShipDesignEntity> known = designs;
                Integer troops = rows.stream()
                        .filter(row -> known.get(row.getDesignId()) != null
                                && known.get(row.getDesignId()).getRole() == ShipRole.TRANSPORT)
                        .mapToInt(FleetShipEntity::getColonists)
                        .sum();
                Integer attack = fleetService.power(fleet, owner);
                Integer defence = defence(human, fleet.getTargetSystemId(), ownFleets);
                if (troops <= 0 && attack <= defence) {
                    continue;
                }

                StarSystemEntity target = systems.get(fleet.getTargetSystemId());
                String where = target == null ? "" : target.getName();
                Integer left = ThreatRules.turnsLeft(fleet.getArrivalTurn(), turn);
                MessageKey message = troops > 0
                        ? new MessageKey("turn.threat.landing", where, owner.getName(), troops,
                                left, attack, defence)
                        : new MessageKey("turn.threat.stronger", where, owner.getName(),
                                attack, defence, left);
                context.report().add(human.getId(), "THREAT", message,
                        fleet.getTargetSystemId(), null);
                log.info("Предупреждение игроку {}: флот империи {} летит к {} (сила {} против {}, "
                                + "десант {}, ходов {})",
                        human.getName(), owner.getName(), where, attack, defence, troops, left);
            }
        }
    }

    /** Война без договора, связывающего руки: только такой флот и нападёт. */
    private static Boolean hostile(DiplomacyRelationEntity relation) {
        if (relation == null || relation.getStance() != DiplomacyStance.WAR) {
            return Boolean.FALSE;
        }
        Set<DiplomacyTreaty> treaties = relation.getTreaties();
        return !treaties.contains(DiplomacyTreaty.ALLIANCE)
                && !treaties.contains(DiplomacyTreaty.NON_AGGRESSION);
    }

    /** Заметили ли флот на этом ходу впервые: заметен сейчас и не был заметен ходом раньше. */
    private static Boolean firstNoticed(FleetEntity fleet, Integer turn, List<StarSystemEntity> posts,
                                        Integer scanRange, Map<UUID, StarSystemEntity> systems) {
        StarSystemEntity origin = systems.get(fleet.getOriginSystemId());
        StarSystemEntity target = systems.get(fleet.getTargetSystemId());
        if (target == null) {
            return Boolean.FALSE;
        }
        if (origin == null) {
            origin = target;
        }
        Boolean now = ThreatRules.noticed(fleet.getArrivalTurn(), turn,
                nearest(origin, target, ThreatRules.progress(fleet.getDepartureTurn(),
                        fleet.getArrivalTurn(), turn), posts), scanRange);
        if (!Boolean.TRUE.equals(now)) {
            return Boolean.FALSE;
        }
        // Ходом раньше флота в пути ещё не было — значит, раньше его и не замечали.
        if (fleet.getDepartureTurn() >= turn) {
            return Boolean.TRUE;
        }
        Boolean before = ThreatRules.noticed(fleet.getArrivalTurn(), turn - 1,
                nearest(origin, target, ThreatRules.progress(fleet.getDepartureTurn(),
                        fleet.getArrivalTurn(), turn - 1), posts), scanRange);
        return !Boolean.TRUE.equals(before);
    }

    /** Расстояние от точки пути до ближайшего наблюдательного поста, в парсеках. */
    private static Double nearest(StarSystemEntity origin, StarSystemEntity target, Double progress,
                                  List<StarSystemEntity> posts) {
        double x = origin.getXParsec() + (target.getXParsec() - origin.getXParsec()) * progress;
        double y = origin.getYParsec() + (target.getYParsec() - origin.getYParsec()) * progress;
        return posts.stream()
                .mapToDouble(post -> Math.hypot(post.getXParsec() - x, post.getYParsec() - y))
                .min()
                .orElse(Double.MAX_VALUE);
    }

    /**
     * Откуда игрок смотрит: системы его колоний и застав, родная система и системы его
     * стоящих флотов — те же посты, что у сканеров ({@code ExplorationService.watchPosts}).
     */
    private static List<StarSystemEntity> watchPosts(Set<UUID> ownSystems, List<FleetEntity> ownFleets,
                                                     PlayerEntity human,
                                                     Map<UUID, StarSystemEntity> systems) {
        Set<UUID> ids = new HashSet<>(ownSystems);
        if (human.getHomeSystemId() != null) {
            ids.add(human.getHomeSystemId());
        }
        ownFleets.stream()
                .filter(fleet -> fleet.getShips() > 0)
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .map(FleetEntity::getStarSystemId)
                .forEach(ids::add);
        return ids.stream()
                .map(systems::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(StarSystemEntity::getName))
                .toList();
    }

    /** Оборона системы: свои стоящие там флоты и платформы колоний — мерой боя «авто». */
    private Integer defence(PlayerEntity human, UUID systemId, List<FleetEntity> ownFleets) {
        int fleets = ownFleets.stream()
                .filter(fleet -> fleet.getShips() > 0)
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .filter(fleet -> systemId.equals(fleet.getStarSystemId()))
                .mapToInt(fleet -> fleetService.power(fleet, human))
                .sum();
        return fleets + encounterService.colonyDefencePower(human, systemId);
    }
}
