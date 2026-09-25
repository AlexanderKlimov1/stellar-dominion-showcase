package com.moo3.server.service;

import com.moo3.server.domain.entity.FleetEncounterEntity;
import com.moo3.server.domain.entity.SpaceBattleEntity;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.EncounterDecision;
import com.moo3.server.domain.enums.EncounterState;
import com.moo3.server.dto.EncounterDto;
import com.moo3.server.repository.FleetEncounterRepository;
import com.moo3.server.repository.GameRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.service.stub.SpaceBattleService;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Встречи флотов — п. 8.
 * <p>
 * Флоты двух империй, оказавшиеся в конце хода в одной системе, друг друга видят. Первым
 * решает тот, чей флот быстрее: атаковать или разойтись. Разошёлся — выбор переходит
 * второму, и напасть может уже он. Разошлись оба — до следующего хода их никто не тревожит,
 * а в следующем конце хода встреча заводится заново: флоты всё ещё рядом.
 * <p>
 * Встреча заводится в конце хода и ждёт решения в начале следующего — вместе с остальными
 * итогами хода. Пока она ждёт, партия не стоит: решение это отдельное действие, а не
 * условие конца хода. Так сделано намеренно — иначе один отошедший игрок останавливал бы
 * всех, а ждать умеет только очередь ходов (п. 11.1).
 * <p>
 * На союзника и на связанного пактом соседа встреча не заводится вовсе: напасть всё равно
 * нельзя, и предлагать выбор было бы обманом.
 * <p>
 * Сам бой считает {@link SpaceBattleService} — заглушка: настоящая тактическая сцена MOO II
 * встанет на её место, а очередь решений и потери останутся прежними.
 */
@Service
public class EncounterService {

    private static final Logger log = LoggerFactory.getLogger(EncounterService.class);

    /** Состояния, в которых встреча ещё чего-то ждёт. */
    private static final List<EncounterState> PENDING =
            List.of(EncounterState.WAITING_FIRST, EncounterState.WAITING_SECOND);

    private final Messages messages;
    private final FleetEncounterRepository encounterRepository;
    private final EmpireActivityService activity;
    private final PlayerRepository playerRepository;
    private final FleetService fleetService;
    private final DiplomacyService diplomacyService;
    private final PlayerEventService playerEvents;
    private final SpaceBattleService battleService;
    private final TacticalBattleService tacticalBattles;
    private final GameRepository gameRepository;
    /** Оборона колонии — п. 8, п. 11: что колония выставляет в бой и в каких ячейках её проекты. */
    private final OrbitalDefenceRules orbitalDefenceRules;
    private final ShipDesignService shipDesignService;
    private final ShipDesignRules shipDesignRules;
    private final PlanetRepository planetRepository;
    private final PlanetBuildingRepository planetBuildingRepository;

    public EncounterService(Messages messages,
                            FleetEncounterRepository encounterRepository,
                            PlayerRepository playerRepository,
                            FleetService fleetService,
                            DiplomacyService diplomacyService,
                            PlayerEventService playerEvents,
                            SpaceBattleService battleService,
                            TacticalBattleService tacticalBattles,
                            GameRepository gameRepository,
                            EmpireActivityService activity,
                            OrbitalDefenceRules orbitalDefenceRules,
                            ShipDesignService shipDesignService,
                            ShipDesignRules shipDesignRules,
                            PlanetRepository planetRepository,
                            PlanetBuildingRepository planetBuildingRepository) {
        this.messages = messages;
        this.orbitalDefenceRules = orbitalDefenceRules;
        this.shipDesignService = shipDesignService;
        this.shipDesignRules = shipDesignRules;
        this.planetRepository = planetRepository;
        this.planetBuildingRepository = planetBuildingRepository;
        this.gameRepository = gameRepository;
        this.activity = activity;
        this.encounterRepository = encounterRepository;
        this.playerRepository = playerRepository;
        this.fleetService = fleetService;
        this.diplomacyService = diplomacyService;
        this.playerEvents = playerEvents;
        this.battleService = battleService;
        this.tacticalBattles = tacticalBattles;
    }

    /**
     * Ищет встречи в конце хода — п. 8.
     * <p>
     * В системе берутся два сильнейших флота разных империй: бой в MOO II идёт один на
     * систему, и третьей стороне в нём места нет. Остальные флоты дождутся своей очереди
     * в следующем ходу, когда сильнейшие разойдутся или погибнут.
     */
    @Transactional
    public void detect(TurnContext context) {
        UUID gameId = context.game().getId();
        Map<UUID, List<FleetEntity>> bySystem = fleetService.bySystem(gameId);
        if (bySystem.isEmpty()) {
            return;
        }

        Map<UUID, PlayerEntity> players = fleetService.playersById(context.players());
        Map<UUID, String> systemNames = fleetService.systemNames(gameId);

        for (Map.Entry<UUID, List<FleetEntity>> entry : bySystem.entrySet()) {
            List<FleetEntity> strongest = entry.getValue().stream()
                    .sorted(Comparator.comparing((FleetEntity fleet) ->
                                    initiative(fleet, players)).reversed()
                            // При равной инициативе первым решает игрок с меньшим слотом:
                            // выбор должен быть определённым, а не случайным.
                            .thenComparing(fleet -> slot(fleet, players)))
                    .toList();
            if (strongest.size() < 2 || strongest.get(0).getOwnerPlayerId()
                    .equals(strongest.get(1).getOwnerPlayerId())) {
                continue;
            }

            FleetEntity first = strongest.get(0);
            FleetEntity second = strongest.get(1);
            if (Boolean.TRUE.equals(peaceBound(first.getOwnerPlayerId(), second.getOwnerPlayerId()))) {
                continue;
            }
            if (Boolean.TRUE.equals(alreadyPending(gameId, entry.getKey(),
                    first.getOwnerPlayerId(), second.getOwnerPlayerId()))) {
                continue;
            }

            FleetEncounterEntity encounter = new FleetEncounterEntity();
            encounter.setGameId(gameId);
            encounter.setStarSystemId(entry.getKey());
            encounter.setTurn(context.turn());
            encounter.setFirstPlayerId(first.getOwnerPlayerId());
            encounter.setSecondPlayerId(second.getOwnerPlayerId());
            encounter.setState(EncounterState.WAITING_FIRST);
            encounterRepository.save(encounter);

            String system = systemNames.getOrDefault(entry.getKey(), messages.get("turn.system.unnamed"));
            String firstName = name(players, first.getOwnerPlayerId());
            String secondName = name(players, second.getOwnerPlayerId());
            context.report().add(first.getOwnerPlayerId(), "ENCOUNTER",
                    new MessageKey("turn.encounter.metFirst", secondName, system),
                    entry.getKey(), null);
            context.report().add(second.getOwnerPlayerId(), "ENCOUNTER",
                    new MessageKey("turn.encounter.metSecond", system, firstName),
                    entry.getKey(), null);
            log.info("Встреча флотов в системе {}: {} против {}", system, firstName, secondName);
        }

        defendedColonies(context, bySystem, players, systemNames);
    }

    /**
     * Встреча с ОБОРОНОЙ КОЛОНИИ — п. 8, п. 11: чужой флот пришёл туда, где стоит
     * защищённая колония, а флота у её хозяина в системе нет.
     * <p>
     * Без этого орбитальная оборона не значила бы ничего: бой заводился только флотом
     * против флота, и звёздная база с боевой станцией просто смотрели, как мимо них
     * высаживают десант. В MOO II ровно наоборот — платформа и есть то, обо что разбивается
     * первый набег.
     * <p>
     * Беззащитная колония встречи не даёт: подошедшему флоту не с кем драться, и
     * предлагать игроку «атаковать или разойтись» там, где обороны нет, значило бы просить
     * решения ни о чём. Захват такой колонии идёт своей дорогой — десантом (п. 12).
     */
    private void defendedColonies(TurnContext context, Map<UUID, List<FleetEntity>> bySystem,
                                  Map<UUID, PlayerEntity> players, Map<UUID, String> systemNames) {
        UUID gameId = context.game().getId();
        for (PlanetEntity colony : context.colonies()) {
            UUID systemId = context.systemOf(colony);
            UUID ownerId = colony.getOwnerPlayerId();
            if (systemId == null || ownerId == null) {
                continue;
            }
            List<FleetEntity> fleets = bySystem.getOrDefault(systemId, List.of());
            if (fleets.isEmpty() || fleets.stream()
                    .anyMatch(fleet -> ownerId.equals(fleet.getOwnerPlayerId()))) {
                // Свой флот на месте — встречу заведёт обычное правило, и платформы
                // придут в тот же бой вместе с ним.
                continue;
            }
            if (defence(context, colony).isEmpty()) {
                continue;
            }

            FleetEntity visitor = fleets.stream()
                    .sorted(Comparator.comparing((FleetEntity fleet) -> initiative(fleet, players))
                            .reversed()
                            .thenComparing(fleet -> slot(fleet, players)))
                    .findFirst()
                    .orElse(null);
            if (visitor == null || ownerId.equals(visitor.getOwnerPlayerId())) {
                continue;
            }
            if (Boolean.TRUE.equals(peaceBound(visitor.getOwnerPlayerId(), ownerId))
                    || Boolean.TRUE.equals(alreadyPending(gameId, systemId,
                            visitor.getOwnerPlayerId(), ownerId))) {
                continue;
            }

            FleetEncounterEntity encounter = new FleetEncounterEntity();
            encounter.setGameId(gameId);
            encounter.setStarSystemId(systemId);
            encounter.setTurn(context.turn());
            // Решает пришедший: обороне уходить некуда, и выбора «разойтись» у неё нет.
            encounter.setFirstPlayerId(visitor.getOwnerPlayerId());
            encounter.setSecondPlayerId(ownerId);
            encounter.setState(EncounterState.WAITING_FIRST);
            encounterRepository.save(encounter);

            String system = systemNames.getOrDefault(systemId, messages.get("turn.system.unnamed"));
            context.report().add(visitor.getOwnerPlayerId(), "ENCOUNTER",
                    new MessageKey("turn.encounter.atColony", name(players, ownerId), system),
                    systemId, null);
            context.report().add(ownerId, "ENCOUNTER",
                    new MessageKey("turn.encounter.colonyVisited",
                            system, name(players, visitor.getOwnerPlayerId())),
                    systemId, colony.getId());
            log.info("Флот империи {} вышел к обороне колонии {} в системе {}",
                    name(players, visitor.getOwnerPlayerId()), colony.getName(), system);
        }
    }

    /**
     * Проекты платформ, которыми империя обороняет свои колонии этой системы, — п. 8, п. 11.
     * <p>
     * Возвращается по записи на каждую платформу: две колонии со звёздными базами дают две.
     * Проекты берутся те же, что держит в порядке {@code ShipDesignService} — значит
     * вооружены платформы по последнему изученному, без всякой перестройки.
     */
    private List<UUID> platformsAt(PlayerEntity owner, UUID systemId) {
        Map<String, ShipDesignEntity> designs =
                shipDesignService.platformDesigns(owner);
        if (designs.isEmpty()) {
            return List.of();
        }
        List<UUID> platforms = new ArrayList<>();
        for (PlanetEntity colony : planetRepository.findAllByOwnerPlayerId(owner.getId())) {
            if (colony.getStarSystem() == null
                    || !systemId.equals(colony.getStarSystem().getId())) {
                continue;
            }
            List<String> built = planetBuildingRepository.findAllByPlanetId(colony.getId()).stream()
                    .map(PlanetBuildingEntity::getBuildingCode)
                    .toList();
            for (String hull : orbitalDefenceRules.platformsOf(built)) {
                ShipDesignEntity design = designs.get(hull);
                if (design != null) {
                    platforms.add(design.getId());
                }
            }
        }
        return platforms;
    }

    /** Сила платформ обороны — для быстрого «авто»: та же мера, что у флотов. */
    private Integer platformPower(PlayerEntity owner, List<UUID> platforms) {
        if (platforms.isEmpty()) {
            return 0;
        }
        Map<UUID, ShipStats> stats = shipDesignService.statsOf(owner);
        return platforms.stream()
                .map(stats::get)
                .filter(Objects::nonNull)
                .mapToInt(one -> shipDesignRules.power(one))
                .sum();
    }

    /** Платформы обороны колонии — п. 8, п. 11: пусто, если колония беззащитна. */
    private List<String> defence(TurnContext context, PlanetEntity colony) {
        return orbitalDefenceRules.platformsOf(context.colonyContext().buildings(colony).stream()
                .map(com.moo3.server.domain.Building::code)
                .toList());
    }

    /** Встречи игрока, ещё ждущие решения, — их же показывает диалог итогов хода. */
    @Transactional(readOnly = true)
    public List<EncounterDto> pending(PlayerEntity player) {
        UUID gameId = player.getGame().getId();
        Map<UUID, String> systemNames = fleetService.systemNames(gameId);
        Map<UUID, PlayerEntity> players = fleetService.playersById(
                playerRepository.findAllByGameIdOrderBySlotAsc(gameId));

        return encounterRepository.findAllByGameIdAndStateInOrderByTurnAscIdAsc(gameId, PENDING).stream()
                .filter(encounter -> involves(encounter, player.getId()))
                .map(encounter -> toDto(encounter, player, players, systemNames))
                .toList();
    }

    /**
     * Решение игрока — п. 8: атаковать чужой флот или разойтись.
     * <p>
     * Разошёлся первый — очередь второго. Разошлись оба — встреча закрыта. Напал любой —
     * бой считается сразу: ручная тактическая сцена ещё заглушка, и признак «авто» пока
     * только записывается в журнал.
     */
    @Transactional
    public EncounterDto decide(PlayerEntity player, Integer turn, UUID encounterId,
                               EncounterDecision decision, Boolean auto) {
        FleetEncounterEntity encounter = encounterRepository.findById(encounterId)
                .orElseThrow(() -> new NotFoundException("encounter.notFound", encounterId));
        if (!encounter.getGameId().equals(player.getGame().getId())) {
            throw new ForbiddenException("encounter.otherGame");
        }
        if (!Boolean.TRUE.equals(encounter.getState().isPending())) {
            throw new ConflictException("encounter.closed", encounter.getState());
        }
        if (!decidingPlayer(encounter).equals(player.getId())) {
            throw new ConflictException("encounter.otherSideDecides");
        }

        Map<UUID, PlayerEntity> players = fleetService.playersById(
                playerRepository.findAllByGameIdOrderBySlotAsc(encounter.getGameId()));
        Map<UUID, String> systemNames = fleetService.systemNames(encounter.getGameId());
        String system = systemNames.getOrDefault(encounter.getStarSystemId(),
                messages.get("turn.system.unnamed"));

        if (decision == EncounterDecision.IGNORE) {
            ignore(encounter, player, turn, system);
        } else {
            attack(encounter, player, turn, players, system, auto);
        }

        encounterRepository.save(encounter);
        return toDto(encounter, player, players, systemNames);
    }

    /**
     * Зерно партии — для боя, который из этой встречи вырастает (п. 8).
     * <p>
     * Спрашивается у базы, а не у {@code player.getGame()}: игрок приходит из
     * {@code GameAccess} отсоединённым, и с его ленивой ссылки на партию безопасно
     * читается только идентификатор («Грабли» в CLAUDE.md). Запрос редкий — он идёт
     * ровно на начало боя.
     */
    private Long gameSeed(FleetEncounterEntity encounter) {
        return gameRepository.findById(encounter.getGameId())
                .map(game -> game.getSeed())
                .orElse(0L);
    }

    /** Разойтись: право нападения переходит второй стороне, а после неё встреча закрыта. */
    private void ignore(FleetEncounterEntity encounter, PlayerEntity player,
                        Integer turn, String system) {
        UUID opponentId = encounter.opponentOf(player.getId());

        if (encounter.getState() == EncounterState.WAITING_FIRST) {
            encounter.setState(EncounterState.WAITING_SECOND);
            playerEvents.record(encounter.getGameId(), opponentId, turn, "ENCOUNTER",
                    new MessageKey("turn.encounter.partedYourTurn", player.getName(), system),
                    encounter.getStarSystemId(), null);
            log.info("Игрок {} разошёлся миром в системе {}", player.getName(), system);
            return;
        }

        encounter.setState(EncounterState.IGNORED);
        encounter.setOutcome(new MessageKey("battle.outcome.parted", system).packed());
        playerEvents.record(encounter.getGameId(), opponentId, turn, "ENCOUNTER",
                new MessageKey("turn.encounter.parted", system), encounter.getStarSystemId(), null);
        playerEvents.record(encounter.getGameId(), player.getId(), turn, "ENCOUNTER",
                new MessageKey("turn.encounter.parted", system), encounter.getStarSystemId(), null);
        log.info("Встреча в системе {} закрыта без боя", system);
    }

    /** Напасть: бой считает заглушка, потери списываются с обоих флотов. */
    private void attack(FleetEncounterEntity encounter, PlayerEntity player, Integer turn,
                        Map<UUID, PlayerEntity> players, String system, Boolean auto) {
        UUID opponentId = encounter.opponentOf(player.getId());
        PlayerEntity opponent = players.get(opponentId);

        // Пакт и союз держат руки связанными — та же проверка, что у десанта (п. 15).
        diplomacyService.requireAttackAllowed(player.getId(), opponentId);

        FleetEntity mine = fleetService.fleetAt(player.getId(), encounter.getStarSystemId());
        FleetEntity theirs = fleetService.fleetAt(opponentId, encounter.getStarSystemId());

        // Оборона колонии — п. 8, п. 11: платформы дерутся и тогда, когда флота у хозяина
        // в системе нет вовсе. Спрашиваются они здесь, а не в фазе: встреча решается
        // нажатием игрока, и лишний запрос на неё приходится один раз, а не каждый ход.
        List<UUID> defence = opponent == null
                ? List.of()
                : platformsAt(opponent, encounter.getStarSystemId());

        if (mine == null || (theirs == null && defence.isEmpty())) {
            // Флот успели увести или потерять, а обороны у колонии нет — нападать не на кого.
            encounter.setState(EncounterState.IGNORED);
            encounter.setOutcome(
                    new MessageKey("battle.outcome.nothingToAttack", system).packed());
            return;
        }

        // Ручной бой — тактическая сцена: корабли ходят по инициативе, и исход считает
        // не формула, а само поле (п. 8). Встреча ждёт его конца в состоянии IN_BATTLE.
        if (!Boolean.TRUE.equals(auto)) {
            var battle = tacticalBattles.start(encounter.getGameId(), encounter.getId(),
                    encounter.getStarSystemId(), turn, player, mine,
                    opponent == null ? player : opponent, theirs, defence, gameSeed(encounter));
            encounter.setState(EncounterState.IN_BATTLE);
            encounter.setAttackerPlayerId(player.getId());
            encounter.setBattleId(battle.getId());
            playerEvents.record(encounter.getGameId(), opponentId, turn, "BATTLE",
                    new MessageKey("turn.battle.forced", player.getName(), system),
                    encounter.getStarSystemId(), null);
            log.info("Игрок {} начал тактический бой в системе {}", player.getName(), system);
            return;
        }

        // «Авто» считает бой одной формулой — быстрый путь для тех, кому сцена не нужна.
        // В бой идёт сила флотов, а не число кораблей: корабль теперь строится по
        // проекту, и десять фрегатов больше не равны десяти дредноутам — п. 8.
        // Бой засчитывается обеим сторонам и здесь, у быстрого «авто»: счётчик отвечает
        // на вопрос «воевала ли империя вообще» (этап 1), а не «был ли тактический бой».
        activity.record(encounter.getGameId(), player.getId(), EmpireActivityService.BATTLE);
        if (opponent != null) {
            activity.record(encounter.getGameId(), opponent.getId(), EmpireActivityService.BATTLE);
        }
        // Оборона колонии входит в быстрый «авто» тем же, чем и в тактический бой, —
        // числом кораблей и силой: платформа для формулы такой же участник, как корабль.
        Integer defenceShips = defence.size();
        Integer defencePower = platformPower(opponent, defence);
        SpaceBattleService.BattleOutcome outcome = battleService.resolve(
                player.getName(), mine.getShips(), power(mine, players),
                opponent == null ? messages.get("turn.opponent.unknown") : opponent.getName(),
                (theirs == null ? 0 : theirs.getShips()) + defenceShips,
                (theirs == null ? 0 : power(theirs, players)) + defencePower,
                // Зерно боя — от партии, хода и системы: тот же бой в том же прогоне
                // обязан кончаться тем же (см. TacticalBattleService.start).
                gameSeed(encounter) + turn * 101L
                        + encounter.getStarSystemId().getMostSignificantBits());

        fleetService.applyLosses(mine, outcome.attackerLosses(), player);
        if (theirs != null) {
            fleetService.applyLosses(theirs, outcome.defenderLosses(), opponent);
        }

        encounter.setState(EncounterState.BATTLE);
        encounter.setAttackerPlayerId(player.getId());
        encounter.setOutcome(outcome.summary().packed());

        /*
          Каждой стороне — своё сообщение, а не общий пересказ боя: у нападавшего «ваш флот
          победил», у обороняющегося «нападение отбито». Так отчёт и задуман (см. TurnReport),
          и заодно это снимает вложенность — итог боя не подставляется строкой в другую
          строку, чего в базе выразить нечем.
        */
        Boolean attackerWins = outcome.attackerWins();
        playerEvents.record(encounter.getGameId(), player.getId(), turn, "BATTLE",
                attackerWins == null
                        ? new MessageKey("turn.battle.mutual", system)
                        : new MessageKey(attackerWins ? "turn.battle.won" : "turn.battle.lost",
                                system, outcome.attackerLosses()),
                encounter.getStarSystemId(), null);
        playerEvents.record(encounter.getGameId(), opponentId, turn, "BATTLE",
                attackerWins == null
                        ? new MessageKey("turn.battle.mutual", system)
                        : new MessageKey(attackerWins
                                ? "turn.battle.attackedLost" : "turn.battle.attackedWon",
                                player.getName(), system, outcome.defenderLosses()),
                encounter.getStarSystemId(), null);

        log.info("Игрок {} атаковал флот в системе {} ({}): {}",
                player.getName(), system, Boolean.TRUE.equals(auto) ? "авто" : "вручную",
                outcome.summary().key());
    }

    /**
     * Закрывает встречу исходом тактического боя — п. 8.
     * <p>
     * Потери бой списал сам: он считал корабли поимённо. Встрече остаётся исход и строки
     * в итогах хода обеим сторонам — событие, которого игрок не увидел, для игры не
     * случилось.
     */
    @Transactional
    public void closeByBattle(SpaceBattleEntity battle, Map<UUID, PlayerEntity> players, Integer turn) {
        FleetEncounterEntity encounter = encounterRepository.findById(battle.getEncounterId())
                .orElse(null);
        if (encounter == null || encounter.getState() != EncounterState.IN_BATTLE) {
            // Встречу уже закрыли: бой мог кончиться ходом соперника раньше нашего.
            return;
        }

        encounter.setState(EncounterState.BATTLE);
        encounter.setOutcome(battle.getOutcome());
        encounterRepository.save(encounter);

        // Итог тактического боя уже назван ключом и системой (см. TacticalBattleService),
        // поэтому в отчёт он идёт как есть: пересказывать его второй строкой нечего.
        MessageKey story = MessageKey.unpack(battle.getOutcome());
        for (UUID playerId : List.of(battle.getAttackerPlayerId(), battle.getDefenderPlayerId())) {
            playerEvents.record(battle.getGameId(), playerId, turn, "BATTLE", story,
                    battle.getStarSystemId(), null);
        }
        log.info("Встреча в системе {} закрыта боем: {}",
                battle.getStarSystemId(), story.key());
    }

    /** Чья очередь решать: первым — тот, чей флот быстрее. */
    private UUID decidingPlayer(FleetEncounterEntity encounter) {
        return encounter.getState() == EncounterState.WAITING_FIRST
                ? encounter.getFirstPlayerId()
                : encounter.getSecondPlayerId();
    }

    private Boolean involves(FleetEncounterEntity encounter, UUID playerId) {
        return encounter.getFirstPlayerId().equals(playerId)
                || encounter.getSecondPlayerId().equals(playerId);
    }

    /** Нападение запрещено договором с обеих сторон — встречу заводить незачем. */
    private Boolean peaceBound(UUID first, UUID second) {
        try {
            diplomacyService.requireAttackAllowed(first, second);
            diplomacyService.requireAttackAllowed(second, first);
            return Boolean.FALSE;
        } catch (ConflictException forbidden) {
            return Boolean.TRUE;
        }
    }

    private Boolean alreadyPending(UUID gameId, UUID systemId, UUID first, UUID second) {
        return encounterRepository.findAllByGameIdAndStarSystemIdAndStateIn(gameId, systemId, PENDING)
                .stream()
                .anyMatch(encounter -> involves(encounter, first) && involves(encounter, second));
    }

    /**
     * Инициатива флота — п. 8: сила его кораблей и их скорость. Оба числа приходят из
     * проектов, по которым корабли построены.
     */
    private Integer initiative(FleetEntity fleet, Map<UUID, PlayerEntity> players) {
        PlayerEntity owner = players.get(fleet.getOwnerPlayerId());
        if (owner == null) {
            return 0;
        }
        return fleetService.initiativeOf(fleet, owner);
    }

    /** Боевая сила флота — п. 8: сумма сил его кораблей по их проектам. */
    private Integer power(FleetEntity fleet, Map<UUID, PlayerEntity> players) {
        PlayerEntity owner = players.get(fleet.getOwnerPlayerId());
        return owner == null ? 0 : fleetService.power(fleet, owner);
    }

    private Integer slot(FleetEntity fleet, Map<UUID, PlayerEntity> players) {
        PlayerEntity owner = players.get(fleet.getOwnerPlayerId());
        return owner == null ? Integer.MAX_VALUE : owner.getSlot();
    }

    private String name(Map<UUID, PlayerEntity> players, UUID playerId) {
        PlayerEntity player = players.get(playerId);
        return player == null ? messages.get("turn.opponent.unknown") : player.getName();
    }

    private EncounterDto toDto(FleetEncounterEntity encounter, PlayerEntity player,
                               Map<UUID, PlayerEntity> players, Map<UUID, String> systemNames) {
        UUID opponentId = encounter.opponentOf(player.getId());
        FleetEntity mine = fleetService.fleetAt(player.getId(), encounter.getStarSystemId());
        FleetEntity theirs = fleetService.fleetAt(opponentId, encounter.getStarSystemId());

        return new EncounterDto(
                encounter.getId(),
                encounter.getStarSystemId(),
                systemNames.getOrDefault(encounter.getStarSystemId(),
                        messages.get("turn.system.unnamed")),
                encounter.getTurn(),
                encounter.getState().name(),
                messages.label(encounter.getState()),
                Boolean.TRUE.equals(encounter.getState().isPending())
                        && decidingPlayer(encounter).equals(player.getId()),
                encounter.getFirstPlayerId().equals(player.getId()),
                opponentId,
                name(players, opponentId),
                mine == null ? 0 : mine.getShips(),
                theirs == null ? 0 : theirs.getShips(),
                mine == null ? 0 : initiative(mine, players),
                theirs == null ? 0 : initiative(theirs, players),
                encounter.getBattleId(),
                messages.text(encounter.getOutcome()));
    }

    /** Встречи партии — нужны сохранению и отладке. */
    @Transactional(readOnly = true)
    public List<FleetEncounterEntity> pendingOf(UUID gameId) {
        return new ArrayList<>(encounterRepository.findAllByGameIdAndStateInOrderByTurnAscIdAsc(gameId, PENDING));
    }
}
