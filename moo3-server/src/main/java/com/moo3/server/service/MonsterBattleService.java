package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.BattleShipEntity;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.ShipDesignComponentEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.entity.SpaceBattleEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.BattleSide;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.enums.SpaceMonster;
import com.moo3.server.repository.BattleShipRepository;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.ShipDesignComponentRepository;
import com.moo3.server.repository.ShipDesignRepository;
import com.moo3.server.repository.StarSystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Тактический бой с космическим чудищем — п. 8, п. 11.1.
 * <p>
 * До этого чудище решалось одной формулой: сила флота против силы сторожа. Объяснялось
 * это тем, что «тактический бой ведётся между двумя ИГРОКАМИ, а у чудища хозяина нет», —
 * и получалось, что встреча с драконом, ради которой игрок и копил флот, проходила мимо
 * него строкой в итогах хода. Теперь у чудища есть сторона на поле, и человек дерётся с
 * ним сам.
 * <p>
 * <b>Чудище — строка игрока, но не империя</b> ({@link PlayerType#MONSTER}). Заводится она
 * ОДНА на партию и лениво, при первом же бое: у неё нет ни колоний, ни науки, ни
 * дипломатии, и ни в один список участников она не попадает — списки берутся у
 * {@code PlayerRepository.findEmpiresByGameIdOrderBySlotAsc}. Иначе чудище проросло бы в
 * лобби, в летопись, в совет и в слепок партии.
 * <p>
 * <b>Дерётся тактически только человек.</b> Империи ИИ решают свои бои быстрым счётом —
 * и с чудищем тоже ({@code FleetService.monster}): поле боя нужно тому, кто на него
 * смотрит. Сила при этом у обоих путей одна и та же ({@link MonsterBattleRules}), так что
 * исход не зависит от того, кто пришёл.
 * <p>
 * <b>Итог боя переводится обратно в силу</b> ({@link #close}): уцелело тело — чудище
 * осталось в системе ослабевшим, разбито — системы больше никто не сторожит. Тем же полем
 * {@code monster_strength} живёт и быстрый бой, так что ослабленного дракона можно
 * добить следующим флотом, как и раньше.
 */
@Service
public class MonsterBattleService {

    private static final Logger log = LoggerFactory.getLogger(MonsterBattleService.class);

    /**
     * Место чудища в партии.
     * <p>
     * Места империй идут с нуля, поэтому у чудища оно отрицательное: зерно боя считается
     * от мест обеих сторон, и накладываться на место империи ему нельзя.
     */
    private static final int MONSTER_SLOT = -1;

    /**
     * Имя строки чудища — оно ложится в базу, поэтому английское и без пометок на языке
     * читателя (правило имён: в хранимое имя слов не кладут). На экране сторона
     * подписывается самим чудищем — его название переводится ключом перечисления.
     */
    private static final String MONSTER_NAME = "Space Monsters";

    /** Раса чудища: её нет. Код нужен колонке, а сторон расы у чудища не бывает вовсе. */
    private static final String MONSTER_RACE = "MONSTER";

    /** Цвет стороны на поле и в журнале: красный, как кольцо чудища на карте. */
    private static final String MONSTER_COLOR = "#f87171";

    private final PlayerRepository playerRepository;
    private final ShipDesignRepository shipDesignRepository;
    private final ShipDesignComponentRepository shipDesignComponentRepository;
    private final StarSystemRepository starSystemRepository;
    private final BattleShipRepository battleShipRepository;
    private final FleetRepository fleetRepository;
    private final TacticalBattleService battles;
    private final ShipDesignRules shipDesignRules;
    private final MonsterBattleRules rules;
    private final EmpireActivityService activity;
    private final PlayerEventService playerEvents;

    public MonsterBattleService(PlayerRepository playerRepository,
                                ShipDesignRepository shipDesignRepository,
                                ShipDesignComponentRepository shipDesignComponentRepository,
                                StarSystemRepository starSystemRepository,
                                BattleShipRepository battleShipRepository,
                                FleetRepository fleetRepository,
                                TacticalBattleService battles,
                                ShipDesignRules shipDesignRules,
                                MonsterBattleRules rules,
                                EmpireActivityService activity,
                                PlayerEventService playerEvents) {
        this.playerRepository = playerRepository;
        this.shipDesignRepository = shipDesignRepository;
        this.shipDesignComponentRepository = shipDesignComponentRepository;
        this.starSystemRepository = starSystemRepository;
        this.battleShipRepository = battleShipRepository;
        this.fleetRepository = fleetRepository;
        this.battles = battles;
        this.shipDesignRules = shipDesignRules;
        this.rules = rules;
        this.activity = activity;
        this.playerEvents = playerEvents;
    }

    /**
     * Выводит чудище на поле против пришедшего флота — п. 8, п. 11.1.
     * <p>
     * Встречи у такого боя нет ({@code encounterId} пуст): встреча — это переговоры двух
     * флотов о том, драться ли, а чудище не спрашивает и не отвечает. Оно нападает само,
     * и выбора «напасть или разойтись» игроку не предлагают — его нет и в оригинале.
     * <p>
     * Строка чудища и его проект заводятся прямо здесь, изнутри посчитанного хода. Это
     * выборка «на событие», а не «на игрока в каждой фазе»: чудищ в партии считанные
     * штуки, и бой с ними случается считанные разы (то же послабление, что у высадки
     * десанта).
     */
    @Transactional
    public SpaceBattleEntity start(GameEntity game, StarSystemEntity system,
                                   PlayerEntity attacker, FleetEntity fleet, Integer turn) {
        SpaceMonster kind = system.getMonster();
        Integer strength = system.getMonsterStrength();
        PlayerEntity monster = player(game);
        ShipDesignEntity design = design(game, monster, kind, strength, turn);

        SpaceBattleEntity battle = battles.start(game.getId(), null, system.getId(), turn,
                attacker, fleet, monster, null, List.of(design.getId()), game.getSeed());

        log.info("Чудище {} в системе {} вышло на поле против флота империи {}",
                kind.getLabel(), system.getName(), attacker.getName());
        return battle;
    }

    /**
     * Заводит бои с чудищами за всех, кому они в этот ход достались, — п. 8, п. 11.1.
     * <p>
     * Зовётся фазой прибытия сразу после того, как флоты сели: дерётся чудище с флотом,
     * который уже стоит в системе, — иначе бою некуда было бы списывать потери.
     * <p>
     * Смотрит на СОСТОЯНИЕ, а не на приход: флот человека стоит в сторожевой системе —
     * значит, будет бой. Оттого и флот, оставшийся там после прошлого боя, получает новый:
     * чудище не пропускает через свою систему никого, и уйти от него можно только уйдя.
     * Идущий бой второй раз не заводится: у боя своя очередь ходов, и вторая сцена в той
     * же системе была бы двумя боями об одном чудище.
     * <p>
     * Выборка здесь ОДНА на партию (стоящие флоты), а не на игрока: фаза идёт внутри
     * посчитанного хода, и запрос на игрока бил бы по всему ходу.
     */
    @Transactional
    public void startPending(TurnContext context) {
        Map<UUID, StarSystemEntity> guarded = context.systems().stream()
                .filter(system -> Boolean.TRUE.equals(system.hasLiveMonster()))
                .collect(Collectors.toMap(StarSystemEntity::getId, Function.identity()));
        if (guarded.isEmpty()) {
            return;
        }

        Map<UUID, PlayerEntity> humans = context.players().stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        if (humans.isEmpty()) {
            return;
        }

        Set<UUID> busy = battles.active(context.game().getId()).stream()
                .map(SpaceBattleEntity::getStarSystemId)
                .collect(Collectors.toSet());

        // Порядок задан явно: ход обязан повторяться от прогона к прогону (иначе парные
        // прогоны балансировки сравнивают не расы, а разную удачу).
        List<FleetEntity> standing = fleetRepository.findAllByGameId(context.game().getId()).stream()
                .filter(fleet -> fleet.getTargetSystemId() == null)
                .filter(fleet -> guarded.containsKey(fleet.getStarSystemId()))
                .filter(fleet -> humans.containsKey(fleet.getOwnerPlayerId()))
                .filter(fleet -> fleet.getShips() > 0)
                .sorted(Comparator.comparing(FleetEntity::getId))
                .toList();

        for (FleetEntity fleet : standing) {
            if (!busy.add(fleet.getStarSystemId())) {
                continue;
            }
            StarSystemEntity system = guarded.get(fleet.getStarSystemId());
            PlayerEntity owner = humans.get(fleet.getOwnerPlayerId());
            start(context.game(), system, owner, fleet, context.turn());
            context.report().add(owner.getId(), "MONSTER",
                    new MessageKey("turn.monster.attacks", system.getMonster(), system.getName()),
                    system.getId(), null);
        }
    }

    /** Этот бой — бой с чудищем? Решает род стороны, а не название проекта. */
    public Boolean isMonsterBattle(SpaceBattleEntity battle) {
        return playerRepository.findById(battle.getDefenderPlayerId())
                .map(player -> player.getPlayerType() == PlayerType.MONSTER)
                .orElse(Boolean.FALSE);
    }

    /**
     * Переводит конец боя обратно в силу чудища — п. 11.1.
     * <p>
     * Потери флота списал сам бой: он считал корабли поимённо. Здесь решается судьба
     * системы — сторожат её дальше или больше нет. Счётчик убитых чудищ ставится там же,
     * где и у быстрого боя ({@code EmpireActivityService.MONSTER}): мерило баланса
     * спрашивает «трогала ли игра эту механику», а не «каким путём».
     */
    @Transactional
    public void close(SpaceBattleEntity battle, Integer turn) {
        StarSystemEntity system = starSystemRepository.findById(battle.getStarSystemId()).orElse(null);
        if (system == null || !Boolean.TRUE.equals(system.hasLiveMonster())) {
            return;
        }
        SpaceMonster kind = system.getMonster();
        Integer strength = system.getMonsterStrength();

        List<BattleShipEntity> body = battleShipRepository
                .findAllByBattleIdOrderByInitiativeDescOrdinalAsc(battle.getId())
                .stream()
                .filter(ship -> ship.getSide() == BattleSide.DEFENDER)
                .toList();
        UUID hunterId = battle.getAttackerPlayerId();

        boolean alive = body.stream().anyMatch(BattleShipEntity::alive);
        if (!alive) {
            system.setMonster(null);
            system.setMonsterStrength(null);
            starSystemRepository.save(system);
            activity.record(battle.getGameId(), hunterId, EmpireActivityService.MONSTER);
            playerEvents.record(battle.getGameId(), hunterId, turn, "MONSTER",
                    new MessageKey("turn.monster.slain", kind, system.getName()),
                    system.getId(), null);
            log.info("Чудище {} в системе {} убито в тактическом бою", kind.getLabel(), system.getName());
            return;
        }

        // Уцелело: сила становится долей уцелевшего тела. Ниже единицы она не опускается —
        // живое чудище системы не отдаёт, даже добитое до последней чешуйки.
        ShipStats full = shipDesignRules.stats(rules.hull(kind), rules.items(kind, strength),
                RaceEffects.NONE);
        Integer left = body.stream()
                .filter(BattleShipEntity::alive)
                .map(ship -> rules.leftAfter(strength, full, ship.getStructure(), ship.getArmour()))
                .max(Integer::compareTo)
                .orElse(strength);
        system.setMonsterStrength(Math.max(1, left));
        starSystemRepository.save(system);
        playerEvents.record(battle.getGameId(), hunterId, turn, "MONSTER",
                new MessageKey("turn.monster.survived", kind, system.getName()),
                system.getId(), null);
        log.info("Чудище {} в системе {} отбилось: силы осталось {}",
                kind.getLabel(), system.getName(), system.getMonsterStrength());
    }

    /**
     * Строка чудища этой партии: одна на всех её сторожей, заводится при первом бое.
     * <p>
     * Почему одна, а не по чудищу: строка нужна лишь затем, чтобы у стороны на поле был
     * владелец, а кто именно вышел на поле, видно по его телу — проект чудища зовётся
     * своим корпусом.
     */
    @Transactional
    public PlayerEntity player(GameEntity game) {
        return playerRepository.findAllByGameIdOrderBySlotAsc(game.getId()).stream()
                .filter(player -> player.getPlayerType() == PlayerType.MONSTER)
                .findFirst()
                .orElseGet(() -> create(game));
    }

    private PlayerEntity create(GameEntity game) {
        PlayerEntity monster = new PlayerEntity();
        monster.setGame(game);
        monster.setSlot(MONSTER_SLOT);
        monster.setName(MONSTER_NAME);
        monster.setPlayerType(PlayerType.MONSTER);
        monster.setRaceCode(MONSTER_RACE);
        monster.setColor(MONSTER_COLOR);
        // Пропуск строке нужен колонкой, но войти им нельзя ни в какой осмысленной игре:
        // он случаен и никому не выдаётся. Чудищем не управляет никто — за него ходит
        // сервер, как за кораблями ИИ.
        monster.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
        monster.setJoinedAt(OffsetDateTime.now());
        return playerRepository.saveAndFlush(monster);
    }

    /**
     * Проект чудища на этот бой: тело, шкура и когти по нынешней силе.
     * <p>
     * Новой строкой на каждый бой, а не одной на партию: сила чудища меняется от боя к
     * бою, а проект, по которому уже дрались, править нельзя — на него смотрят корабли
     * прошедших боёв (то же правило, что у обновления автоматических проектов).
     */
    private ShipDesignEntity design(GameEntity game, PlayerEntity monster, SpaceMonster kind,
                                    Integer strength, Integer turn) {
        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(monster.getId());
        design.setSlot(MonsterBattleRules.DESIGN_SLOT);
        // Имя ложится в базу, поэтому английское — название тела из справочника.
        design.setName(rules.hull(kind).names().en());
        design.setHullCode(kind.getHullCode());
        design.setCreatedTurn(turn);
        design.setObsolete(Boolean.FALSE);
        shipDesignRepository.saveAndFlush(design);

        List<ShipDesignRules.Item> items = rules.items(kind, strength);
        List<ShipDesignComponentEntity> parts = new java.util.ArrayList<>(items.size());
        int order = 0;
        for (ShipDesignRules.Item item : items) {
            ShipDesignComponentEntity part = new ShipDesignComponentEntity();
            part.setDesignId(design.getId());
            part.setComponentCode(item.component().code());
            part.setCount(item.count());
            part.setSortOrder(order++);
            parts.add(part);
        }
        shipDesignComponentRepository.saveAll(parts);
        shipDesignComponentRepository.flush();
        return design;
    }
}
