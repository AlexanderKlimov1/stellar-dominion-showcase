package com.moo3.server.service;

import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.BattleShipEntity;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.FleetShipEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.entity.SpaceBattleEntity;
import com.moo3.server.domain.enums.BattleActionType;
import com.moo3.server.domain.enums.BattleSide;
import com.moo3.server.domain.enums.BattleState;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.enums.WeaponKind;
import com.moo3.server.dto.BattleActionRequest;
import com.moo3.server.dto.BattleDto;
import com.moo3.server.dto.BattleEventDto;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.WeaponModification;
import com.moo3.server.dto.BattleShipDto;
import com.moo3.server.dto.BattleWeaponDto;
import com.moo3.server.repository.BattleShipRepository;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.SpaceBattleRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Тактический бой кораблей — п. 8, сцена боя MOO II.
 * <p>
 * <b>Ходят корабли, а не игроки.</b> Очередь в круге строится по инициативе корабля
 * ({@link BattleRules#initiative}) и идёт сквозь обе стороны: быстрый фрегат успевает
 * выстрелить раньше чужого дредноута. Отходил круг — начинается следующий, щиты
 * восстанавливаются, движение и залп открываются заново.
 * <p>
 * <b>Бой живёт на сервере.</b> Он списывает корабли и решает исход встречи, поэтому
 * состояние поля, попадания и потери считает сервер, а экран боя только показывает поле
 * и просит ходы. Кораблями ИИ ходит сам сервер — сразу, как очередь доходит до них:
 * клиент получает пачку событий и проигрывает их подряд.
 * <p>
 * <b>Заглушка осталась рядом.</b> «Авто» в диалоге встречи по-прежнему считает бой одной
 * формулой ({@code stub/SpaceBattleService}) — это быстрый путь для тех, кому сцена не
 * нужна. Ручной бой идёт сюда.
 */
@Service
public class TacticalBattleService {

    /** Множители зерна боя: своя тройка, чтобы бой не шёл в ногу с прочими случайностями. */
    private static final long BATTLE_SEED_GAME = 1009L;
    private static final long BATTLE_SEED_TURN = 101L;
    private static final long BATTLE_SEED_SLOT = 11L;

    private static final Logger log = LoggerFactory.getLogger(TacticalBattleService.class);

    /**
     * Сколько кораблей одной стороны помещается на поле.
     * <p>
     * Реконструкция: в MOO II на тактическую карту тоже выходит не весь флот. Двадцать
     * четыре — два полных столбца поля; остальные корабли в бою не участвуют и потерь
     * не несут.
     */
    public static final int MAX_SHIPS_PER_SIDE = 24;

    /**
     * Ключ подписи стороны чудищ — п. 11.1.
     * <p>
     * Ключ, а не текст: он идёт и в живой журнал (там переводится сразу), и в хранимый
     * исход боя (там переводится при чтении, на языке того, кто открыл отчёт).
     */
    private static final String MONSTER_SIDE_KEY = "battle.side.monster";

    /**
     * Предел кругов боя. Реконструкция: в MOO II бой кончается сам, когда одна сторона
     * разбита или ушла. Два флота из одних «черепах» могут не пробить друг друга вовсе,
     * и без предела бой висел бы вечно — на этом круге стороны расходятся.
     */
    private static final int MAX_ROUNDS = 30;

    private final Messages messages;
    private final SpaceBattleRepository battleRepository;
    private final BattleShipRepository shipRepository;
    private final BattleRules rules;
    private final ShipDesignService shipDesignService;
    private final ShipDesignRules shipDesignRules;
    private final ShipCatalog shipCatalog;
    private final RaceService raceService;
    private final FleetService fleetService;
    private final FleetRepository fleetRepository;
    private final LeaderBonusService leaderBonuses;

    /** Разбитая оборона колонии — п. 8, п. 11: платформа списывается с планеты, а не с флота. */
    private final OrbitalDefenceService orbitalDefence;

    public TacticalBattleService(Messages messages,
                                 SpaceBattleRepository battleRepository,
                                 BattleShipRepository shipRepository,
                                 BattleRules rules,
                                 ShipDesignService shipDesignService,
                                 ShipDesignRules shipDesignRules,
                                 ShipCatalog shipCatalog,
                                 RaceService raceService,
                                 FleetService fleetService,
                                 FleetRepository fleetRepository,
                                 LeaderBonusService leaderBonuses,
                                 OrbitalDefenceService orbitalDefence) {
        this.messages = messages;
        this.orbitalDefence = orbitalDefence;
        this.fleetRepository = fleetRepository;
        this.leaderBonuses = leaderBonuses;
        this.battleRepository = battleRepository;
        this.shipRepository = shipRepository;
        this.rules = rules;
        this.shipDesignService = shipDesignService;
        this.shipDesignRules = shipDesignRules;
        this.shipCatalog = shipCatalog;
        this.raceService = raceService;
        this.fleetService = fleetService;
    }

    /** Бой и его корабли — то, чем оперируют все остальные методы. */
    public record BattleView(SpaceBattleEntity battle, List<BattleShipEntity> ships,
                             List<BattleEventDto> events) {
    }

    /**
     * Разворачивает два флота в бой — п. 8.
     * <p>
     * Во флоте корабли лежат числом по проектам; на поле каждый становится своей
     * единицей: иначе ни ходить, ни гибнуть по одному они не смогут. Строй нападающего
     * встаёт слева, обороняющегося — справа.
     */
    @Transactional
    public SpaceBattleEntity start(UUID gameId, UUID encounterId, UUID starSystemId, Integer turn,
                                   PlayerEntity attacker, FleetEntity attackerFleet,
                                   PlayerEntity defender, FleetEntity defenderFleet,
                                   Long gameSeed) {
        return start(gameId, encounterId, starSystemId, turn, attacker, attackerFleet,
                defender, defenderFleet, List.of(), gameSeed);
    }

    /**
     * Тот же бой, но с ОБОРОНОЙ КОЛОНИИ на стороне защищающегося — п. 8, п. 11.
     * <p>
     * Платформы приходят проектами ({@code defenderPlatforms} — по записи на каждую) и
     * встают в строй наравне с кораблями: у них те же пушки, та же броня и та же кривая
     * попадания, только двигателя нет — с места они не сходят. Флота у обороны при этом
     * может не быть вовсе: колония со звёздной базой защищается и сама.
     */
    @Transactional
    public SpaceBattleEntity start(UUID gameId, UUID encounterId, UUID starSystemId, Integer turn,
                                   PlayerEntity attacker, FleetEntity attackerFleet,
                                   PlayerEntity defender, FleetEntity defenderFleet,
                                   List<UUID> defenderPlatforms,
                                   Long gameSeed) {
        SpaceBattleEntity battle = new SpaceBattleEntity();
        battle.setGameId(gameId);
        battle.setEncounterId(encounterId);
        battle.setStarSystemId(starSystemId);
        battle.setAttackerPlayerId(attacker.getId());
        battle.setDefenderPlayerId(defender.getId());
        battle.setTurn(turn);
        battle.setRound(1);
        battle.setState(BattleState.IN_PROGRESS);
        // Зерно от партии, хода и мест обеих сторон — и ни в коем случае не от
         // идентификаторов: те у каждой партии свои случайные, и повтор той же партии с тем
         // же зерном давал бы другие бои. Парные прогоны балансировки на этом и держатся
         // (balance-metrics-works.txt, этап 0).
        battle.setSeed(gameSeed * BATTLE_SEED_GAME
                + turn * BATTLE_SEED_TURN
                + attacker.getSlot() * BATTLE_SEED_SLOT
                + defender.getSlot());
        battleRepository.saveAndFlush(battle);

        List<BattleShipEntity> ships = new ArrayList<>();
        ships.addAll(deploy(battle, attacker, attackerFleet, BattleSide.ATTACKER));
        ships.addAll(deploy(battle, defender, defenderFleet, defenderPlatforms, BattleSide.DEFENDER));
        shipRepository.saveAll(ships);
        shipRepository.flush();

        battle.setCurrentShipId(order(ships).stream().findFirst().map(BattleShipEntity::getId).orElse(null));
        battleRepository.save(battle);

        log.info("Бой в системе {}: {} кораблей против {}", starSystemId,
                ships.stream().filter(ship -> ship.getSide() == BattleSide.ATTACKER).count(),
                ships.stream().filter(ship -> ship.getSide() == BattleSide.DEFENDER).count());
        return battle;
    }

    /**
     * Заводит бой прямо из составов, без флотов и без встречи — п. 8.
     * <p>
     * Так собирается демонстрационный бой: партии с флотами за ним нет, есть только два
     * строя кораблей. Всё остальное — очередь, ходы, попадания, конец боя — то же самое,
     * что и в настоящем бою: демонстрация показывает игру, а не её подобие.
     */
    @Transactional
    public SpaceBattleEntity startBetween(UUID gameId, UUID starSystemId, Integer turn,
                                          PlayerEntity attacker, List<ShipCount> attackerShips,
                                          PlayerEntity defender, List<ShipCount> defenderShips,
                                          Long seed) {
        SpaceBattleEntity battle = new SpaceBattleEntity();
        battle.setGameId(gameId);
        battle.setStarSystemId(starSystemId);
        battle.setAttackerPlayerId(attacker.getId());
        battle.setDefenderPlayerId(defender.getId());
        battle.setTurn(turn);
        battle.setRound(1);
        battle.setState(BattleState.IN_PROGRESS);
        battle.setSeed(seed);
        battleRepository.saveAndFlush(battle);

        List<BattleShipEntity> ships = new ArrayList<>();
        ships.addAll(deploy(battle, attacker, attackerShips, BattleSide.ATTACKER));
        ships.addAll(deploy(battle, defender, defenderShips, BattleSide.DEFENDER));
        shipRepository.saveAll(ships);
        shipRepository.flush();

        battle.setCurrentShipId(order(ships).stream().findFirst().map(BattleShipEntity::getId).orElse(null));
        battleRepository.save(battle);
        return battle;
    }

    /**
     * Один ход демонстрации: сервер ходит за корабль, чья очередь, кем бы тот ни был — п. 8.
     * <p>
     * В обычном бою так ходят только корабли ИИ, и то следом за ходом человека. Здесь
     * человека нет вовсе, поэтому шаг делается по запросу извне: экран просит их один за
     * другим и показывает бой как кино. Ходить за живого игрока этим нельзя — метод
     * зовёт только демонстрация, у которой обе стороны ИИ.
     */
    @Transactional
    public BattleView autoStep(UUID battleId, Map<UUID, PlayerEntity> players) {
        SpaceBattleEntity battle = require(battleId);
        if (battle.getState() != BattleState.IN_PROGRESS) {
            return new BattleView(battle, order(ships(battleId)), List.of());
        }

        List<BattleEventDto> events = new ArrayList<>();
        Context context = context(battle, order(ships(battleId)), players);
        BattleShipEntity current = context.current();
        if (current != null) {
            playAi(context, current, events);
        }
        advance(context, events);
        save(context);
        return new BattleView(context.battle(), order(context.ships()), events);
    }

    /** Состояние боя без событий: экран открылся и спрашивает поле. */
    @Transactional(readOnly = true)
    public BattleView view(UUID battleId) {
        SpaceBattleEntity battle = require(battleId);
        return new BattleView(battle, order(ships(battleId)), List.of());
    }

    /** Бои партии, которые ещё идут: по ним клиент понимает, что сцену пора открыть. */
    @Transactional(readOnly = true)
    public List<SpaceBattleEntity> active(UUID gameId) {
        return battleRepository.findAllByGameIdAndState(gameId, BattleState.IN_PROGRESS);
    }

    /**
     * Ход корабля — п. 8.
     * <p>
     * Ходить можно только своим кораблём и только когда очередь его. Движение ход не
     * заканчивает, залп и пропуск — заканчивают; после этого очередь идёт дальше, и
     * корабли ИИ ходят тут же, пока очередь не дойдёт до корабля человека или бой
     * не кончится.
     */
    @Transactional
    public BattleView act(PlayerEntity player, UUID battleId, BattleActionRequest request,
                          Map<UUID, PlayerEntity> players) {
        SpaceBattleEntity battle = require(battleId);
        if (battle.getState() != BattleState.IN_PROGRESS) {
            throw new ConflictException("battle.over");
        }

        List<BattleShipEntity> ships = order(ships(battleId));
        BattleShipEntity ship = ships.stream()
                .filter(candidate -> candidate.getId().equals(request.shipId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("battle.shipNotFound", request.shipId()));

        if (!ship.getOwnerPlayerId().equals(player.getId())) {
            throw new ForbiddenException("battle.shipNotYours");
        }
        if (!ship.getId().equals(battle.getCurrentShipId())) {
            throw new ConflictException("battle.notYourTurn");
        }

        List<BattleEventDto> events = new ArrayList<>();
        Context context = context(battle, ships, players);

        switch (request.action()) {
            case MOVE -> move(context, ship, request.x(), request.y(), events);
            case FIRE -> {
                fire(context, ship, target(ships, request.targetShipId()), events);
                endShipTurn(context, events);
            }
            case PASS -> {
                events.add(event("PASS", ship, null, null,
                        messages.get("battle.log.pass", name(context, ship))));
                endShipTurn(context, events);
            }
            case RETREAT -> retreat(context, player, events);
            case SELF_DESTRUCT -> {
                selfDestruct(context, ship, events);
                endShipTurn(context, events);
            }
        }

        save(context);
        return new BattleView(context.battle(), order(context.ships()), events);
    }

    /** Ставит корабли флота в строй своей стороны. */
    /** Сколько кораблей какого проекта выходит в бой — общий вид состава для расстановки. */
    public record ShipCount(UUID designId, Integer ships) {
    }

    /** Строй стороны из её флота: корабли лежат в нём числом по проектам. */
    private List<BattleShipEntity> deploy(SpaceBattleEntity battle, PlayerEntity owner,
                                          FleetEntity fleet, BattleSide side) {
        return deploy(battle, owner, fleet, List.of(), side);
    }

    /**
     * То же, но вместе с платформами обороны колонии — п. 8, п. 11.
     * <p>
     * Платформы ставятся ПЕРВЫМИ: поле не резиновое ({@code MAX_SHIPS_PER_SIDE}), и если
     * обороне не хватает места, уступать его должен подвижный флот, а не то, что улететь
     * всё равно не может. Флота может не быть вовсе — колонию прикрывает одна оборона.
     */
    private List<BattleShipEntity> deploy(SpaceBattleEntity battle, PlayerEntity owner,
                                          FleetEntity fleet, List<UUID> platforms,
                                          BattleSide side) {
        Map<UUID, ShipStats> stats = shipDesignService.statsOf(owner);

        List<FleetShipEntity> rows = fleet == null
                ? List.of()
                : fleetService.composition(List.of(fleet)).getOrDefault(fleet.getId(), List.of());

        // Сперва самые сильные: если весь флот на поле не помещается, в бой идут лучшие.
        List<ShipCount> sorted = rows.stream()
                .sorted(Comparator.comparing((FleetShipEntity row) -> {
                    ShipStats value = stats.get(row.getDesignId());
                    return value == null ? 0 : value.structure() + value.armour() + value.attack();
                }).reversed())
                .map(row -> new ShipCount(row.getDesignId(), row.getShips()))
                .toList();

        if (platforms.isEmpty()) {
            return deploy(battle, owner, sorted, side);
        }
        List<ShipCount> composition = new ArrayList<>(platforms.size() + sorted.size());
        platforms.stream()
                .collect(Collectors.groupingBy(one -> one, LinkedHashMap::new, Collectors.counting()))
                .forEach((designId, count) -> composition.add(new ShipCount(designId, count.intValue())));
        composition.addAll(sorted);
        return deploy(battle, owner, composition, side);
    }

    /**
     * Ставит строй стороны на поле — п. 8.
     * <p>
     * Клетки считаются от <b>общего</b> числа кораблей стороны, а не от числа кораблей
     * одного проекта: иначе каждый проект центрировался бы отдельно и корабли разных
     * проектов встали бы на одни и те же клетки.
     */
    private List<BattleShipEntity> deploy(SpaceBattleEntity battle, PlayerEntity owner,
                                          List<ShipCount> composition, BattleSide side) {
        Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(owner);
        Map<UUID, ShipStats> stats = shipDesignService.statsByDesign(designs.values(),
                raceService.effects(owner));
        Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs.values());

        int total = Math.min(MAX_SHIPS_PER_SIDE,
                composition.stream().mapToInt(ShipCount::ships).sum());

        List<BattleShipEntity> ships = new ArrayList<>();
        int index = 0;
        for (ShipCount row : composition) {
            ShipStats value = stats.get(row.designId());
            if (value == null) {
                continue;
            }
            List<ShipDesignRules.Item> parts = items.getOrDefault(row.designId(), List.of());
            for (int number = 0; number < row.ships() && index < MAX_SHIPS_PER_SIDE; number++, index++) {
                BattleShipEntity ship = new BattleShipEntity();
                ship.setBattleId(battle.getId());
                ship.setOwnerPlayerId(owner.getId());
                ship.setDesignId(row.designId());
                ship.setSide(side);
                ship.setOrdinal(index + 1);
                ship.setStructure(value.structure());
                ship.setArmour(value.armour());
                ship.setShield(value.shield());
                ship.setInitiative(rules.initiative(value, parts));
                int[] cell = rules.startingCell(side, index, total);
                ship.setX(cell[0]);
                ship.setY(cell[1]);
                ships.add(ship);
            }
        }
        return ships;
    }

    /** Перелёт корабля: не дальше своей скорости и не на занятую клетку. */
    private void move(Context context, BattleShipEntity ship, Integer x, Integer y,
                      List<BattleEventDto> events) {
        if (x == null || y == null || !rules.onField(x, y)) {
            throw new ConflictException("battle.noSuchCell");
        }
        if (occupied(context.ships(), x, y)) {
            throw new ConflictException("battle.cellTaken");
        }

        // Разбитый двигатель — это неподвижность (п. 8), и сказать об этом нужно прямо:
        // «слишком далеко на 0 клеток» игрок прочёл бы как поломку игры, а не корабля.
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
            throw new ConflictException("battle.engineWrecked");
        }
        int distance = rules.distance(ship.getX(), ship.getY(), x, y);
        int left = speed(context, ship) - ship.getMoved();
        if (distance > left) {
            throw new ConflictException("battle.tooFar", left, distance);
        }

        ship.setX(x);
        ship.setY(y);
        ship.setMoved(ship.getMoved() + distance);
        events.add(event("MOVE", ship, null, null,
                messages.get("battle.log.move", name(context, ship), x, y)));
    }

    /**
     * Залп по цели — п. 8.
     * <p>
     * Каждый ствол стреляет отдельно: попадание разыгрывается по шансу от размера корпуса
     * цели, а щит гасит выстрелы поодиночке. Дошедший урон снимает броню, а остаток —
     * прочность корпуса; на нуле корабль уничтожен.
     */
    private void fire(Context context, BattleShipEntity ship, BattleShipEntity target,
                      List<BattleEventDto> events) {
        if (Boolean.TRUE.equals(ship.getFired())) {
            throw new ConflictException("battle.alreadyFired");
        }
        if (target.getSide() == ship.getSide()) {
            throw new ConflictException("battle.friendlyFire");
        }
        if (!target.alive()) {
            throw new ConflictException("battle.targetDestroyed");
        }
        Model model = context.model(ship);
        // Стволы берутся у САМОГО КОРАБЛЯ, а не у проекта: разбитая пушка не стреляет —
        // п. 8. Корабль, у которого выбили все стволы, не стреляет вовсе.
        List<ShipDesignRules.Item> parts = parts(context, ship);
        List<BattleRules.Shot> salvo = shots(context, ship);
        if (salvo.isEmpty()) {
            throw new ConflictException("battle.weaponsWrecked");
        }
        if (!rules.inReach(salvo, ship.getX(), ship.getY(),
                target.getX(), target.getY())) {
            throw new ConflictException("battle.outOfRange", rules.reach(salvo));
        }

        Model targetModel = context.model(target);
        // Офицеры флота считаются наравне с приборами корабля — п. 6: «Оружейник»
        // добавляет меткости стреляющему, «Рулевой» — уклонения цели.
        // Прицел тоже считается по уцелевшему: сожжённый компьютер больше не целится.
        Integer attackRating = rules.attackPercent(parts) + model.officers().attack();
        Integer defenceRating = rules.defenceBonus(targetModel.hull(), targetModel.stats())
                + targetModel.officers().defence();
        Integer chance = rules.hitChancePercent(targetModel.hull(), attackRating, defenceRating);

        // Залп разбирается по видам оружия: лучи, снаряды и ракеты уходят своими
        // событиями, и сцена рисует их по-разному. На числа это не влияет — урон
        // складывается и снимается разом, как и раньше.
        // Урона два вида: обычный снимает сперва броню, бронебойный идёт прямо в корпус —
        // п. 8. Модификация ствола решает, каким будет выстрел.
        int damage = 0;
        int piercing = 0;
        int intercepted = 0;
        boolean engineHit = false;
        // Ближняя оборона цели сбивает чужие ракеты — п. 8: её стволы считаются один раз
        // на весь залп, а бросок идёт на каждую ракету.
        // Ближняя оборона цели — тоже стволы, и разбитые не сбивают ничего.
        Integer defenceGuns = rules.pointDefenceGuns(parts(context, target));
        Integer distance = rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY());
        for (Map.Entry<WeaponKind, List<BattleRules.Shot>> group
                : rules.shotsByKind(parts).entrySet()) {
            int dealt = 0;
            for (BattleRules.Shot shot : group.getValue()) {
                // Дальность у каждого ствола своя — п. 8: тяжёлая установка достаёт туда,
                // куда ближняя оборона не дотягивается вдвое.
                if (!Boolean.TRUE.equals(rules.shotReaches(shot, distance))) {
                    continue;
                }
                Integer interception = rules.interceptChancePercent(defenceGuns, shot);
                if (interception > 0
                        && Boolean.TRUE.equals(rules.rollHit(context.random(), interception))) {
                    intercepted++;
                    continue;
                }
                Integer shotChance = rules.shotHitChancePercent(targetModel.hull(),
                        attackRating, defenceRating, shot,
                        targetModel.stats().missileEvasion());
                if (!Boolean.TRUE.equals(rules.rollHit(context.random(), shotChance))) {
                    continue;
                }
                // Луч слабеет в пути (п. 8), а «артиллерист» усиливает каждый выстрел до
                // щита, а не после: в MOO II он добавляет урона оружию, а щит гасит то,
                // что до него долетело.
                Integer reached = rules.damageAtRange(shot, distance);
                Integer damaged = reached * (100 + model.officers().damage()) / 100;
                Integer through = Boolean.TRUE.equals(shot.piercesShield())
                        ? damaged
                        : rules.damageThroughShield(damaged, target.getShield());
                // Обволакивающий удар (ENV) приходится на все стороны корабля разом, и
                // щит гасит его на каждой по отдельности — п. 8.
                through = through * rules.envelopingFactor(shot);
                dealt += through;
                if (Boolean.TRUE.equals(shot.piercesArmour())) {
                    piercing += through;
                } else {
                    damage += through;
                }
                // Наведение по излучению (EMG): пробившись сквозь щит, ракета выбивает
                // двигатель — корабль до конца боя ползёт.
                if (through > 0 && Boolean.TRUE.equals(shot.hitsEngine())) {
                    engineHit = true;
                }
            }
            events.add(new BattleEventDto("FIRE", ship.getId(), target.getId(),
                    target.getX(), target.getY(), dealt, group.getKey(),
                    messages.get("battle.log.fire", name(context, ship), name(context, target),
                            group.getKey(), dealt, chance)));
        }

        if (intercepted > 0) {
            events.add(new BattleEventDto("INTERCEPT", target.getId(), ship.getId(),
                    target.getX(), target.getY(), intercepted, WeaponKind.MISSILE,
                    messages.get("battle.log.intercepted",
                            name(context, target), intercepted)));
        }
        if (engineHit && !Boolean.TRUE.equals(target.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
            target.damageSystem(BattleRules.ENGINE_SYSTEM);
            events.add(new BattleEventDto("ENGINE", ship.getId(), target.getId(),
                    target.getX(), target.getY(), 0, WeaponKind.MISSILE,
                    messages.get("battle.log.engineHit", name(context, target))));
        }

        int armour = Math.max(0, target.getArmour() - damage);
        int throughArmour = Math.max(0, damage - target.getArmour()) + piercing;
        target.setArmour(armour);
        target.setStructure(Math.max(0, target.getStructure() - throughArmour));
        ship.setFired(Boolean.TRUE);

        if (target.getStructure() <= 0) {
            destroy(context, target, events);
            return;
        }
        // Всё, что прошло сквозь щит и броню, рвёт корабль ИЗНУТРИ — п. 8.
        if (throughArmour > 0) {
            wreckSystem(context, target, events);
        }
    }

    /**
     * Попадание по корпусу выбивает бортовую систему — п. 8.
     * <p>
     * Правило MOO II: щит и броня держат удар, а прошедшее сквозь них гасит ствол,
     * разбивает двигатель или сжигает прицел. Жребий идёт по тому, что ещё цело, поэтому
     * у корабля с полным трюмом стволов двигатель под ударом реже: рвётся то, чего в
     * корабле больше. Числа — в {@link BattleRules}.
     * <p>
     * <b>Разбитый двигатель взрывается</b> с вероятностью
     * {@link BattleRules#ENGINE_BLAST_PERCENT}: корабль уходит сразу и уносит с собой
     * соседей — и своих тоже.
     */
    private void wreckSystem(Context context, BattleShipEntity target,
                             List<BattleEventDto> events) {
        if (!Boolean.TRUE.equals(rules.rollSystemHit(context.random()))) {
            return;
        }
        List<String> live = rules.liveSystems(context.model(target).items(),
                target.getDamagedSystems());
        String system = rules.pickSystem(context.random(), live);
        if (system == null) {
            // Ломать больше нечего: у корабля выбито всё, что ломается.
            return;
        }
        target.damageSystem(system);
        events.add(new BattleEventDto("SYSTEM", target.getId(), null,
                target.getX(), target.getY(), null, null,
                messages.get("battle.log.systemWrecked", name(context, target),
                        systemName(context, target, system))));

        if (BattleRules.ENGINE_SYSTEM.equals(system)
                && Boolean.TRUE.equals(rules.rollEngineBlast(context.random()))) {
            explode(context, target, events, "battle.log.blast");
        }
    }

    /**
     * Корабль взрывается — п. 8: сам гибнет и бьёт всех, кто рядом.
     * <p>
     * Взрывом кончается разбитый двигатель ({@link #wreckSystem}) и самоподрыв
     * ({@link #selfDestruct}) — разница только в том, чей это был выбор. Сила взрыва
     * считается от ПОЛНОЙ прочности корпуса: рвётся корабль, а не его остаток, и потому
     * дредноут опасен даже добитым.
     * <p>
     * <b>Соседям взрыв систем не ломает и цепи не устраивает.</b> Взрыв бьёт снаружи, а
     * не пробивает корабль насквозь, — и это же снимает бесконечную цепочку: иначе один
     * подрыв в плотном строю уносил бы всё поле, а у боя не осталось бы ни одного
     * решения игрока.
     */
    private void explode(Context context, BattleShipEntity ship, List<BattleEventDto> events,
                         String key) {
        Model model = context.model(ship);
        Integer damage = rules.blastDamage(model.hull());
        Integer radius = rules.blastRadius(model.hull());
        // Сперва взрыв, потом гибель: журнал рассказывает бой по порядку, а «уничтожен»
        // раньше «взрывается» читается как две разных беды.
        events.add(new BattleEventDto("BLAST", ship.getId(), null,
                ship.getX(), ship.getY(), damage, null,
                messages.get(key, name(context, ship), damage)));
        destroy(context, ship, events);

        for (BattleShipEntity near : context.ships()) {
            if (near.getId().equals(ship.getId()) || !near.alive()) {
                continue;
            }
            if (rules.distance(ship.getX(), ship.getY(), near.getX(), near.getY()) > radius) {
                continue;
            }
            Integer through = rules.damageThroughShield(damage, near.getShield());
            Integer armourLeft = Math.max(0, near.getArmour() - through);
            Integer structureLeft = Math.max(0,
                    near.getStructure() - Math.max(0, through - near.getArmour()));
            near.setArmour(armourLeft);
            near.setStructure(structureLeft);
            events.add(new BattleEventDto("BLAST_HIT", ship.getId(), near.getId(),
                    near.getX(), near.getY(), through, null,
                    messages.get("battle.log.blastHit", name(context, near), through)));
            if (near.getStructure() <= 0) {
                destroy(context, near, events);
            }
        }
    }

    /**
     * Самоподрыв — п. 8, кнопка SELF DESTRUCT оригинала.
     * <p>
     * Гарантированный взрыв по воле игрока: корабль уходит сам и уносит с собой всех, кто
     * стоит рядом. Смысл его в том, что подбитый корабль уже не спасти, а взрыв — это
     * единственное, что он ещё может сделать по чужому строю.
     */
    private void selfDestruct(Context context, BattleShipEntity ship,
                              List<BattleEventDto> events) {
        explode(context, ship, events, "battle.log.selfDestruct");
    }

    /** Корабль уничтожен: с поля уходит, а в записи боя остаётся ради подсчёта потерь. */
    private void destroy(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        if (!ship.alive()) {
            return;
        }
        ship.setDestroyed(Boolean.TRUE);
        events.add(new BattleEventDto("DESTROYED", ship.getId(), null,
                ship.getX(), ship.getY(), null, null,
                messages.get("battle.log.destroyed", name(context, ship))));
    }

    /**
     * Как называется выбитая система на языке читателя.
     * <p>
     * У ствола имя берётся из справочника — игрок должен видеть, ЧТО именно замолчало:
     * потерять ближнюю оборону и потерять тяжёлую установку — разные беды.
     */
    private String systemName(Context context, BattleShipEntity ship, String system) {
        if (BattleRules.ENGINE_SYSTEM.equals(system)) {
            return messages.get("battle.system.engine");
        }
        if (BattleRules.SHIELD_SYSTEM.equals(system)) {
            return messages.get("battle.system.shield");
        }
        if (BattleRules.COMPUTER_SYSTEM.equals(system)) {
            return messages.get("battle.system.computer");
        }
        return context.model(ship).items().stream()
                .map(ShipDesignRules.Item::component)
                .filter(component -> component.code().equals(system))
                .map(component -> component.name())
                .findFirst()
                .orElse(system);
    }

    /**
     * Состав корабля без выбитых систем — п. 8: по нему считаются залп, прицел и дальность.
     * <p>
     * У целого корабля возвращается сам состав проекта: пока ломать нечего, лишних
     * списков не строим — залп считается на каждый выстрел каждого корабля.
     */
    private List<ShipDesignRules.Item> parts(Context context, BattleShipEntity ship) {
        return rules.partsLeft(context.model(ship).items(), ship.getDamagedSystems());
    }

    /** Стволы, которые у этого корабля ещё стреляют. */
    private List<BattleRules.Shot> shots(Context context, BattleShipEntity ship) {
        Model model = context.model(ship);
        List<ShipDesignRules.Item> left = parts(context, ship);
        return left == model.items() ? model.shots() : rules.shots(left);
    }

    /** Отступление: сторона уходит с поля, бой кончается, поле остаётся за противником. */
    /**
     * Отступление: сторона уходит с поля, бой кончается, поле остаётся за противником.
     * <p>
     * <b>Уходят только те, кто может идти</b> — п. 8: корабль с разбитым двигателем
     * остаётся на месте и гибнет вместе с полем. Это и есть цена неподвижности: пока
     * двигатель цел, из безнадёжного боя можно выйти, а подбитому бежать уже нечем.
     */
    private void retreat(Context context, PlayerEntity player, List<BattleEventDto> events) {
        context.battle().setRetreatedPlayerId(player.getId());
        events.add(new BattleEventDto("RETREAT", null, null, null, null, null, null,
                messages.get("battle.log.retreat", player.getName())));
        for (BattleShipEntity left : context.ships()) {
            if (!left.alive() || !left.getOwnerPlayerId().equals(player.getId())) {
                continue;
            }
            if (Boolean.TRUE.equals(left.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
                events.add(new BattleEventDto("STRANDED", left.getId(), null,
                        left.getX(), left.getY(), null, null,
                        messages.get("battle.log.stranded", name(context, left))));
                destroy(context, left, events);
            }
        }
        finish(context, events);
    }

    /**
     * Ход переходит следующему кораблю, а корабли ИИ ходят тут же.
     * <p>
     * Ждать от ИИ отдельного запроса некому: своих решений он не принимает нигде в игре.
     * Поэтому сервер проигрывает его ходы сразу и отдаёт клиенту пачку событий — экран
     * покажет их подряд, как будто бой шёл у игрока на глазах.
     */
    private void endShipTurn(Context context, List<BattleEventDto> events) {
        advance(context, events);

        int guard = 0;
        while (context.battle().getState() == BattleState.IN_PROGRESS
                && guard++ < MAX_SHIPS_PER_SIDE * 4) {
            BattleShipEntity current = context.current();
            if (current == null || !isAi(context, current)) {
                return;
            }
            playAi(context, current, events);
            advance(context, events);
        }
    }

    /** Ход корабля ИИ: подойти к ближайшему врагу и выстрелить, если достанет. */
    private void playAi(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        BattleShipEntity target = nearestEnemy(context.ships(), ship);
        if (target == null) {
            return;
        }

        List<BattleRules.Shot> shots = shots(context, ship);
        if (shots.isEmpty()) {
            // Стволов не осталось вовсе: безоружный корабль ходит, но не стреляет.
            return;
        }
        if (!rules.inReach(shots, ship.getX(), ship.getY(), target.getX(), target.getY())) {
            approach(context, ship, target, events);
        }
        if (Boolean.TRUE.equals(rules.inReach(shots, ship.getX(), ship.getY(),
                target.getX(), target.getY()))
                && !Boolean.TRUE.equals(ship.getFired())) {
            fire(context, ship, target, events);
        }
    }

    /** Шаг за шагом к цели: по клетке, пока есть ход и пока не подошли на выстрел. */
    private void approach(Context context, BattleShipEntity ship, BattleShipEntity target,
                          List<BattleEventDto> events) {
        int left = speed(context, ship) - ship.getMoved();
        List<BattleRules.Shot> shots = shots(context, ship);
        int x = ship.getX();
        int y = ship.getY();
        for (int step = 0; step < left; step++) {
            if (Boolean.TRUE.equals(rules.inReach(shots, x, y, target.getX(), target.getY()))) {
                break;
            }
            int nextX = x + Integer.signum(target.getX() - x);
            int nextY = y + Integer.signum(target.getY() - y);
            if (!rules.onField(nextX, nextY) || occupied(context.ships(), nextX, nextY)) {
                // Дорогу загородили — обходим по одной оси, а не стоим на месте.
                if (!occupied(context.ships(), nextX, y) && rules.onField(nextX, y)) {
                    nextY = y;
                } else if (!occupied(context.ships(), x, nextY) && rules.onField(x, nextY)) {
                    nextX = x;
                } else {
                    break;
                }
            }
            x = nextX;
            y = nextY;
        }

        if (x != ship.getX() || y != ship.getY()) {
            int distance = rules.distance(ship.getX(), ship.getY(), x, y);
            ship.setX(x);
            ship.setY(y);
            ship.setMoved(ship.getMoved() + distance);
            events.add(event("MOVE", ship, null, null,
                    messages.get("battle.log.move", name(context, ship), x, y)));
        }
    }

    /**
     * Передаёт ход следующему живому кораблю очереди. Круг кончился — начинается
     * следующий: щиты восстанавливаются, движение и залп открываются заново.
     */
    private void advance(Context context, List<BattleEventDto> events) {
        if (finished(context, events)) {
            return;
        }

        List<BattleShipEntity> queue = order(context.ships()).stream()
                .filter(BattleShipEntity::alive)
                .toList();
        UUID currentId = context.battle().getCurrentShipId();
        int index = -1;
        for (int position = 0; position < queue.size(); position++) {
            if (queue.get(position).getId().equals(currentId)) {
                index = position;
                break;
            }
        }

        if (index >= 0 && index + 1 < queue.size()) {
            context.battle().setCurrentShipId(queue.get(index + 1).getId());
            return;
        }

        // Круг кончился.
        if (context.battle().getRound() >= MAX_ROUNDS) {
            events.add(new BattleEventDto("FINISHED", null, null, null, null, null, null,
                    messages.get("battle.log.stalemate")));
            finish(context, events);
            return;
        }

        context.battle().setRound(context.battle().getRound() + 1);
        for (BattleShipEntity ship : context.ships()) {
            ship.setMoved(0);
            ship.setFired(Boolean.FALSE);
            // Щит восстанавливается между кругами — как в MOO II. Разбитому щиту
            // восстанавливаться нечем: выбитая система держится до конца боя (п. 8).
            ship.setShield(Boolean.TRUE.equals(ship.systemDamaged(BattleRules.SHIELD_SYSTEM))
                    ? 0
                    : context.model(ship).stats().shield());
            repairCybernetic(context, ship, events);
        }
        context.battle().setCurrentShipId(queue.isEmpty() ? null : queue.get(0).getId());
    }

    /**
     * Кибернетическая раса чинит свои корабли прямо в бою — п. 7.
     * <p>
     * Между кругами корабль возвращает себе десятую долю брони и корпуса (число MOO II).
     * Погибшему это уже не помогает: чинить нечего, и правило оригинала о полном
     * восстановлении «после битвы» здесь выполняется само — корабли выходят из боя
     * целыми, потому что урон между боями не хранится вовсе.
     */
    private void repairCybernetic(Context context, BattleShipEntity ship,
                                  List<BattleEventDto> events) {
        if (!ship.alive()) {
            return;
        }
        PlayerEntity owner = context.players().get(ship.getOwnerPlayerId());
        if (owner == null || !Boolean.TRUE.equals(raceService.effects(owner).cybernetic())) {
            return;
        }

        ShipStats stats = context.model(ship).stats();
        Integer armour = rules.repaired(ship.getArmour(), stats.armour());
        Integer structure = rules.repaired(ship.getStructure(), stats.structure());
        if (armour.equals(ship.getArmour()) && structure.equals(ship.getStructure())) {
            return;
        }

        Integer healed = (armour - ship.getArmour()) + (structure - ship.getStructure());
        ship.setArmour(armour);
        ship.setStructure(structure);
        events.add(new BattleEventDto("REPAIR", ship.getId(), ship.getId(),
                ship.getX(), ship.getY(), healed, null,
                messages.get("battle.log.repaired", name(context, ship), healed)));
    }

    /** Кончился ли бой: у одной из сторон не осталось живых кораблей. */
    private Boolean finished(Context context, List<BattleEventDto> events) {
        if (context.battle().getState() != BattleState.IN_PROGRESS) {
            return Boolean.TRUE;
        }
        boolean attackers = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.ATTACKER && ship.alive());
        boolean defenders = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.DEFENDER && ship.alive());
        if (attackers && defenders) {
            return Boolean.FALSE;
        }
        finish(context, events);
        return Boolean.TRUE;
    }

    /**
     * Конец боя: потери списываются с флотов, исход ложится в запись боя.
     * <p>
     * Уцелевшие возвращаются во флот числом, погибшие уходят из состава. Встречу и итоги
     * хода закрывает {@code EncounterService} — он же и начал бой.
     */
    private void finish(Context context, List<BattleEventDto> events) {
        SpaceBattleEntity battle = context.battle();
        battle.setState(BattleState.FINISHED);
        battle.setCurrentShipId(null);

        Map<UUID, Integer> lost = new HashMap<>();
        for (BattleShipEntity ship : context.ships()) {
            if (Boolean.TRUE.equals(ship.getDestroyed())) {
                lost.merge(ship.getOwnerPlayerId(), 1, Integer::sum);
            }
        }

        applyLosses(context, BattleSide.ATTACKER);
        applyLosses(context, BattleSide.DEFENDER);

        battle.setOutcome(outcome(context, lost).packed());
        events.add(new BattleEventDto("FINISHED", null, null, null, null, null, null,
                messages.text(battle.getOutcome())));
        log.info("Бой в системе {} окончен: {}",
                battle.getStarSystemId(), battle.getOutcome());
    }

    /**
     * Списывает погибшие корабли стороны с её флота — по проектам.
     * <p>
     * Платформы обороны списываются не с флота, а С ПЛАНЕТЫ: разбитая звёздная база
     * перестаёт быть зданием колонии (п. 8, п. 11). Без этого оборону нельзя было бы
     * подавить вовсе — она возрождалась бы к каждому следующему бою целой.
     */
    private void applyLosses(Context context, BattleSide side) {
        UUID ownerId = side == BattleSide.ATTACKER
                ? context.battle().getAttackerPlayerId()
                : context.battle().getDefenderPlayerId();
        Map<UUID, Integer> lostByDesign = new HashMap<>();
        for (BattleShipEntity ship : context.ships()) {
            if (ship.getSide() == side && Boolean.TRUE.equals(ship.getDestroyed())) {
                lostByDesign.merge(ship.getDesignId(), 1, Integer::sum);
            }
        }
        if (lostByDesign.isEmpty()) {
            return;
        }

        PlayerEntity owner = context.player(ownerId);
        Map<String, ShipDesignEntity> platforms = owner == null
                ? Map.of()
                : shipDesignService.platformDesigns(owner);
        Map<UUID, String> hullByDesign = new HashMap<>();
        platforms.forEach((hull, design) -> hullByDesign.put(design.getId(), hull));
        if (!hullByDesign.isEmpty()) {
            Map<UUID, Integer> lostPlatforms = new HashMap<>();
            lostByDesign.entrySet().removeIf(entry -> {
                if (hullByDesign.containsKey(entry.getKey())) {
                    lostPlatforms.put(entry.getKey(), entry.getValue());
                    return true;
                }
                return false;
            });
            lostPlatforms.forEach((designId, count) -> orbitalDefence.destroy(
                    ownerId, context.battle().getStarSystemId(),
                    hullByDesign.get(designId), count));
        }
        if (lostByDesign.isEmpty()) {
            return;
        }

        FleetEntity fleet = fleetService.fleetAt(ownerId, context.battle().getStarSystemId());
        fleetService.applyBattleLosses(fleet, lostByDesign);
    }

    /** Готовая строка исхода для итогов хода обеих сторон. */
    private MessageKey outcome(Context context, Map<UUID, Integer> lost) {
        String attacker = context.nameKey(context.battle().getAttackerPlayerId());
        String defender = context.nameKey(context.battle().getDefenderPlayerId());
        int attackerLost = lost.getOrDefault(context.battle().getAttackerPlayerId(), 0);
        int defenderLost = lost.getOrDefault(context.battle().getDefenderPlayerId(), 0);
        /*
          Название системы спрашивается запросом, и это допустимо: бой кончается считанные
          разы за партию — как высадка десанта, — а не в каждой фазе на каждого игрока.
          Нужно оно затем, что этот же ключ идёт строкой в отчёт хода, где системы не видно.
        */
        String system = fleetService.systemNames(context.battle().getGameId())
                .getOrDefault(context.battle().getStarSystemId(),
                        messages.get("turn.system.unnamed"));

        if (context.battle().getRetreatedPlayerId() != null) {
            return new MessageKey("battle.outcome.retreated", system,
                    context.nameKey(context.battle().getRetreatedPlayerId()),
                    attacker, attackerLost, defender, defenderLost);
        }

        boolean attackersAlive = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.ATTACKER && ship.alive());
        boolean defendersAlive = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.DEFENDER && ship.alive());

        String winner = attackersAlive && !defendersAlive ? attacker
                : defendersAlive && !attackersAlive ? defender : null;
        return winner == null
                ? new MessageKey("battle.outcome.draw", system,
                        attacker, attackerLost, defender, defenderLost)
                : new MessageKey("battle.outcome.winner", system, winner,
                        attacker, attackerLost, defender, defenderLost);
    }

    /** Очередь хода: по убыванию инициативы, при равной — по номеру в строю. */
    private List<BattleShipEntity> order(List<BattleShipEntity> ships) {
        List<BattleShipEntity> sorted = new ArrayList<>(ships);
        sorted.sort(Comparator.comparing(BattleShipEntity::getInitiative).reversed()
                .thenComparing(BattleShipEntity::getOrdinal)
                .thenComparing(BattleShipEntity::getId));
        return sorted;
    }

    private BattleShipEntity nearestEnemy(List<BattleShipEntity> ships, BattleShipEntity ship) {
        return ships.stream()
                .filter(candidate -> candidate.getSide() != ship.getSide() && candidate.alive())
                .min(Comparator.comparing(candidate ->
                        rules.distance(ship.getX(), ship.getY(), candidate.getX(), candidate.getY())))
                .orElse(null);
    }

    private Boolean occupied(List<BattleShipEntity> ships, Integer x, Integer y) {
        return ships.stream()
                .anyMatch(ship -> ship.alive() && ship.getX().equals(x) && ship.getY().equals(y));
    }

    private BattleShipEntity target(List<BattleShipEntity> ships, UUID targetId) {
        if (targetId == null) {
            throw new ConflictException("battle.noTarget");
        }
        return ships.stream()
                .filter(ship -> ship.getId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("battle.targetNotFound", targetId));
    }

    /**
     * Скорость корабля на поле — сколько клеток он проходит за свой ход.
     * <p>
     * Надпространственная раса (п. 7) складывает пространство и в бою: её корабли ходят
     * дальше прочих при том же двигателе.
     */
    /**
     * Сколько клеток корабль проходит за ход — п. 8: боевая скорость двигателя, переведённая
     * в клетки (`BattleRules.cellsPerTurn`), плюс расовая прибавка надпространственных.
     */
    private Integer speed(Context context, BattleShipEntity ship) {
        // Разбитый двигатель — это НЕПОДВИЖНОСТЬ, а не медленный ход (п. 8): корабль
        // остаётся там, где его подбили, и уйти с поля уже не может. Так его выбивает и
        // ракета с наведением по излучению (EMG), и всякое попадание по корпусу.
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
            return 0;
        }
        PlayerEntity owner = context.players().get(ship.getOwnerPlayerId());
        Integer racial = owner == null ? 0 : raceService.effects(owner).shipCombatSpeed();
        return Math.max(1, rules.cellsPerTurn(context.model(ship).stats()) + racial);
    }

    /**
     * За этот корабль ходит сервер, а не человек.
     * <p>
     * Это империи ИИ и <b>чудища</b> (п. 11.1): у чудища хозяина нет вовсе, поэтому ходит
     * за него тот же код, что и за соседа. Проверка идёт по роду стороны, а не «не
     * человек ли»: род без хозяина в игре ровно один, и назвать его прямо честнее.
     */
    private Boolean isAi(Context context, BattleShipEntity ship) {
        PlayerEntity owner = context.players().get(ship.getOwnerPlayerId());
        return owner != null
                && (owner.getPlayerType() == PlayerType.AI
                    || owner.getPlayerType() == PlayerType.MONSTER);
    }

    /**
     * Как корабль зовут в журнале боя: проект, номер в строю и империя.
     * <p>
     * Империя в названии не для красоты: проекты у обеих сторон часто называются
     * одинаково («Фрегат» против «Фрегата»), и без неё журнал читался бы как разговор
     * корабля с самим собой.
     */
    private String name(Context context, BattleShipEntity ship) {
        Model model = context.model(ship);
        // Чудище зовётся своим телом из справочника, а тот переводится (п. 11.1);
        // у корабля имя проекта хранится в базе и языка не имеет.
        String design = Boolean.TRUE.equals(model.hull().monster())
                ? model.hull().name()
                : model.design().getName();
        return design + " " + ship.getOrdinal()
                + " (" + context.name(ship.getOwnerPlayerId()) + ")";
    }

    private BattleEventDto event(String type, BattleShipEntity ship, UUID targetId, Integer damage,
                                 String text) {
        return new BattleEventDto(type, ship.getId(), targetId, ship.getX(), ship.getY(),
                damage, null, text);
    }

    private List<BattleShipEntity> ships(UUID battleId) {
        return shipRepository.findAllByBattleIdOrderByInitiativeDescOrdinalAsc(battleId);
    }

    public SpaceBattleEntity require(UUID battleId) {
        return battleRepository.findById(battleId)
                .orElseThrow(() -> new NotFoundException("battle.notFound", battleId));
    }

    private void save(Context context) {
        shipRepository.saveAll(context.ships());
        battleRepository.save(context.battle());
    }

    private Context context(SpaceBattleEntity battle, List<BattleShipEntity> ships,
                            Map<UUID, PlayerEntity> players) {
        Map<UUID, Model> models = new HashMap<>();
        for (PlayerEntity player : List.of(
                players.get(battle.getAttackerPlayerId()), players.get(battle.getDefenderPlayerId()))) {
            if (player == null) {
                continue;
            }
            Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(player);
            Map<UUID, ShipStats> stats = shipDesignService.statsByDesign(designs.values(),
                    raceService.effects(player));
            Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs.values());
            Officers officers = officers(player.getId(), battle.getStarSystemId());
            designs.forEach((id, design) -> {
                List<ShipDesignRules.Item> parts = items.getOrDefault(id, List.of());
                models.put(id, new Model(design, shipCatalog.hull(design.getHullCode()),
                        stats.get(id), parts, rules.attackPercent(parts), rules.shots(parts),
                        officers));
            });
        }
        return new Context(battle, ships, players, models,
                new Random(battle.getSeed() + battle.getRound()),
                messages.get("battle.ship.unknownOwner"),
                messages.get(MONSTER_SIDE_KEY));
    }

    /** Проект корабля со всем, что нужно бою: корпус, характеристики, стволы, офицеры. */
    public record Model(ShipDesignEntity design, ShipHull hull, ShipStats stats,
                        List<ShipDesignRules.Item> items, Integer attackPercent,
                        List<BattleRules.Shot> shots, Officers officers) {
    }

    /**
     * Что корабельные лидеры дают этой стороне боя — п. 6.
     * <p>
     * Офицер приписан к флоту, а флот у империи в системе один (стоящий), поэтому надбавка
     * у всех её кораблей в этом бою общая: разбираться, на каком именно корабле стоит
     * адмирал, игра не умеет — в MOO II он приписан к кораблю, и это точка расширения,
     * когда корабли станут именными и в базе.
     *
     * @param attack  «Оружейник»: прибавка к меткости
     * @param defence «Рулевой»: прибавка к уклонению
     * @param damage  «Артиллерист»: проценты к урону каждого выстрела
     */
    public record Officers(Integer attack, Integer defence, Integer damage) {

        static Officers none() {
            return new Officers(0, 0, 0);
        }
    }

    /** Надбавки офицеров флота этой империи, стоящего в системе боя, — п. 6. */
    private Officers officers(UUID playerId, UUID starSystemId) {
        return fleetRepository
                .findByOwnerPlayerIdAndStarSystemIdAndTargetSystemIdIsNull(playerId, starSystemId)
                .map(fleet -> {
                    LeaderBonusService.Bonuses bonuses = leaderBonuses.of(playerId);
                    return new Officers(
                            bonuses.fleetValue(fleet.getId(), "WEAPONRY"),
                            bonuses.fleetValue(fleet.getId(), "HELMSMAN"),
                            bonuses.fleetValue(fleet.getId(), "ORDNANCE"));
                })
                .orElseGet(Officers::none);
    }

    /** Всё, что нужно одному ходу боя, собранное разом: по кораблю в базу не ходим. */
    public record Context(SpaceBattleEntity battle, List<BattleShipEntity> ships,
                          Map<UUID, PlayerEntity> players, Map<UUID, Model> models,
                          RandomGenerator random, String unknownOwner,
                          /** Подпись стороны чудищ на языке запроса — п. 11.1. */
                          String monsterSide) {

        Model model(BattleShipEntity ship) {
            Model model = models.get(ship.getDesignId());
            if (model == null) {
                throw new NotFoundException("ship.designNotFound", ship.getDesignId());
            }
            return model;
        }

        BattleShipEntity current() {
            return ships.stream()
                    .filter(ship -> ship.getId().equals(battle.getCurrentShipId()))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Как зовут сторону в ЖИВОМ журнале боя: он собирается на запрос, поэтому имя
         * чудища берётся переведённым. В ХРАНИМЫЙ исход идёт ключ ({@link #nameKey}).
         */
        String name(UUID playerId) {
            PlayerEntity player = players.get(playerId);
            if (player == null) {
                return unknownOwner;
            }
            return player.getPlayerType() == PlayerType.MONSTER ? monsterSide : player.getName();
        }

        /**
         * То же имя, но для ХРАНИМОГО исхода боя — п. 3.5.
         * <p>
         * Исход ложится в базу подстановками и читается потом на языке читателя, а
         * чудище зовётся переводимой подписью: поэтому подставляется её КЛЮЧ — словарь
         * узнает его при показе. У империи имя своё, хранимое, и ключом оно не бывает.
         */
        String nameKey(UUID playerId) {
            PlayerEntity player = players.get(playerId);
            if (player == null) {
                return unknownOwner;
            }
            return player.getPlayerType() == PlayerType.MONSTER ? MONSTER_SIDE_KEY : player.getName();
        }

        /** Сам игрок — нужен там, где спрашивают его проекты (например, платформы обороны). */
        PlayerEntity player(UUID playerId) {
            return players.get(playerId);
        }
    }

    /**
     * Чем корабль вооружён — п. 8: строки для нижней полосы сцены и окна осмотра.
     * <p>
     * Состав берётся у проекта, а не у поля боя: на поле корабль хранится числом
     * прочности и брони, а что на нём стоит — знает только его проект. Одинаковые стволы
     * с разными модификациями приходят разными строками, как в оригинале.
     */
    private List<BattleWeaponDto> weapons(Model model, BattleShipEntity ship) {
        if (model == null) {
            return List.of();
        }
        List<String> broken = ship.getDamagedSystems();
        return model.items().stream()
                .filter(item -> item.component().slot() == ShipComponentSlot.WEAPON)
                .map(item -> new BattleWeaponDto(
                        item.component().name(),
                        item.count(),
                        item.damage(),
                        item.component().weaponKind() == null
                                ? null : item.component().weaponKind().name(),
                        item.modifications().stream().map(WeaponModification::name).toList(),
                        // Сколько гнёзд этого ствола замолчало — п. 8: окно осмотра
                        // показывает не «пушка есть», а «пушка есть, да не стреляет».
                        (int) broken.stream()
                                .filter(one -> one.equals(item.component().code()))
                                .count()))
                .toList();
    }

    /** Скорость корабля для сцены: разбитый двигатель держит его на месте — п. 8. */
    private Integer speedOf(BattleShipEntity ship, ShipStats stats) {
        return Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))
                ? 0
                : rules.cellsPerTurn(stats);
    }


    /** Названия всего, что стоит в этом гнезде: особые модули идут списком. */
    private List<String> named(Model model, ShipComponentSlot slot) {
        if (model == null) {
            return List.of();
        }
        return model.items().stream()
                .filter(item -> item.component().slot() == slot)
                .map(item -> item.count() > 1
                        ? item.component().name() + " x" + item.count()
                        : item.component().name())
                .toList();
    }

    /** Название единственной системы этого гнезда; {@code null} — гнездо пусто. */
    private String single(Model model, ShipComponentSlot slot) {
        return named(model, slot).stream().findFirst().orElse(null);
    }

    /**
     * Ответ для сцены боя — п. 8.
     * <p>
     * Экран рисует корабли прямоугольниками, и размер берёт из корпуса: у фрегата он
     * маленький, у Leviathan — во всю клетку с запасом. Порядок хода отдаётся отдельным
     * списком: очередь в бою идёт по кораблям, а не по игрокам, и её видно целиком.
     */
    @Transactional(readOnly = true)
    public BattleDto toDto(BattleView view, PlayerEntity player, Map<UUID, PlayerEntity> players,
                           String systemName) {
        SpaceBattleEntity battle = view.battle();
        Context shown = context(battle, view.ships(), players);
        Map<UUID, Model> models = shown.models();

        List<BattleShipDto> ships = view.ships().stream()
                .map(ship -> {
                    Model model = models.get(ship.getDesignId());
                    ShipStats stats = model == null ? null : model.stats();
                    PlayerEntity owner = players.get(ship.getOwnerPlayerId());
                    return new BattleShipDto(
                            ship.getId(),
                            ship.getOwnerPlayerId(),
                            // Хозяин корабля: у чудища служебного имени строки игрок не
                            // видит — сторона подписывается переводимым ключом (п. 11.1).
                            owner == null
                                    ? messages.get("battle.ship.unknownOwner")
                                    : owner.getPlayerType() == PlayerType.MONSTER
                                            ? messages.get(MONSTER_SIDE_KEY)
                                            : owner.getName(),
                            ship.getSide(),
                            // Имя проекта хранится в базе и потому английское — но тело
                            // чудища зовётся по справочнику, а тот переводится (п. 11.1).
                            model == null
                                    ? messages.get("battle.ship.unknown")
                                    : Boolean.TRUE.equals(model.hull().monster())
                                            ? model.hull().name()
                                            : model.design().getName(),
                            model == null ? "" : model.hull().name(),
                            model == null ? 1 : model.hull().sortOrder(),
                            ship.getOrdinal(),
                            ship.getX(),
                            ship.getY(),
                            ship.getStructure(),
                            stats == null ? ship.getStructure() : stats.structure(),
                            ship.getArmour(),
                            stats == null ? ship.getArmour() : stats.armour(),
                            ship.getShield(),
                            stats == null ? 0 : stats.attack(),
                            ship.getInitiative(),
                            stats == null ? 1 : speedOf(ship, stats),
                            Math.max(0, (stats == null ? 1 : speedOf(ship, stats))
                                    - ship.getMoved()),
                            model == null ? BattleRules.WEAPON_RANGE : rules.reach(shots(shown, ship)),
                            ship.getFired(),
                            ship.getDestroyed(),
                            ship.getOwnerPlayerId().equals(player.getId()),
                            model != null && Boolean.TRUE.equals(model.hull().platform()),
                            model != null && Boolean.TRUE.equals(model.hull().monster()),
                            stats == null ? 0 : stats.defense(),
                            stats == null ? 0 : stats.missileEvasion(),
                            weapons(model, ship),
                            named(model, ShipComponentSlot.SPECIAL),
                            ship.systemDamaged(BattleRules.ENGINE_SYSTEM),
                            ship.systemDamaged(BattleRules.SHIELD_SYSTEM),
                            ship.systemDamaged(BattleRules.COMPUTER_SYSTEM),
                            single(model, ShipComponentSlot.ENGINE),
                            single(model, ShipComponentSlot.ARMOR),
                            single(model, ShipComponentSlot.SHIELD),
                            single(model, ShipComponentSlot.COMPUTER));
                })
                .toList();

        BattleShipEntity current = view.ships().stream()
                .filter(ship -> ship.getId().equals(battle.getCurrentShipId()))
                .findFirst()
                .orElse(null);

        return new BattleDto(
                battle.getId(),
                battle.getStarSystemId(),
                systemName,
                battle.getTurn(),
                battle.getRound(),
                battle.getState().name(),
                messages.label(battle.getState()),
                BattleRules.FIELD_WIDTH,
                BattleRules.FIELD_HEIGHT,
                BattleRules.WEAPON_RANGE,
                battle.getAttackerPlayerId().equals(player.getId())
                        ? BattleSide.ATTACKER : BattleSide.DEFENDER,
                nameOf(players, battle.getAttackerPlayerId()),
                nameOf(players, battle.getDefenderPlayerId()),
                battle.getCurrentShipId(),
                current != null && current.getOwnerPlayerId().equals(player.getId()),
                power(view.ships(), models, BattleSide.ATTACKER),
                power(view.ships(), models, BattleSide.DEFENDER),
                ships,
                view.ships().stream().filter(BattleShipEntity::alive).map(BattleShipEntity::getId).toList(),
                view.events(),
                messages.text(battle.getOutcome()));
    }

    /**
     * Сила уцелевших кораблей стороны — та же, что у флотов ({@code ShipDesignRules.power}).
     * По ней на экране видно, кто сильнее и как перевес тает по ходу боя.
     */
    private Integer power(List<BattleShipEntity> ships, Map<UUID, Model> models, BattleSide side) {
        int total = 0;
        for (BattleShipEntity ship : ships) {
            Model model = models.get(ship.getDesignId());
            if (ship.getSide() == side && ship.alive() && model != null && model.stats() != null) {
                total += shipDesignRules.power(model.stats());
            }
        }
        return total;
    }

    /**
     * Как зовут сторону боя.
     * <p>
     * У империи — её имя, оно хранится в базе и языка не имеет. У <b>чудища</b> (п. 11.1)
     * хранимое имя служебное («Space Monsters»), и на экран оно не годится: сторона
     * подписывается переводимым ключом, как и всё, что собирается на запрос.
     */
    private String nameOf(Map<UUID, PlayerEntity> players, UUID playerId) {
        PlayerEntity player = players.get(playerId);
        if (player == null) {
            return messages.get("battle.ship.unknownOwner");
        }
        return player.getPlayerType() == PlayerType.MONSTER
                ? messages.get("battle.side.monster")
                : player.getName();
    }
}
