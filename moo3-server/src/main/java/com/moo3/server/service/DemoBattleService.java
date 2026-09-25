package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.ShipDesignComponentEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.entity.SpaceBattleEntity;
import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.dto.BattleDto;
import com.moo3.server.dto.DemoBattleDto;
import com.moo3.server.repository.GameRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.ShipDesignComponentRepository;
import com.moo3.server.repository.ShipDesignRepository;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Демонстрационный бой — п. 8: сцена боя, которую можно посмотреть, не начиная партии.
 * <p>
 * Показывается <b>настоящий</b> бой, а не его подобие: те же корабли, те же правила
 * ({@link BattleRules}), тот же экран. Разница одна — обе стороны ведёт сервер, и шаг
 * делается по запросу извне, чтобы бой шёл на глазах, а не заканчивался мгновенно.
 * <p>
 * <b>Подложка.</b> Бою нужны игроки и проекты кораблей, а те живут в партии. Поэтому
 * демонстрация заводит свою партию — без галактики, без колоний, только два игрока-ИИ и
 * их проекты. Встречи флотов у такого боя нет: он ни из чего не вырос (см. миграцию 036).
 * Предыдущая демонстрация удаляется при запуске новой: держать их незачем, а в списке
 * открытых игр они не появляются — партия сразу идёт.
 * <p>
 * <b>Силы сторон.</b> Флоты собираются из разных корпусов с разным оружием и считаются
 * теми же правилами, что и в игре, — <b>включая расу владельца</b>: её раздаёт жребий, а
 * бой без неё корабль не считает, и число на экране разошлось бы с числом в бою. Одна
 * сторона делается сильнее на
 * {@link #ADVANTAGE_PERCENT} процентов — ровно столько, чтобы бой не был предрешён, но
 * перевес читался. Точное число стволов подбирается перебором: сила считается целыми
 * числами, и попасть в проценты «в лоб» нельзя.
 */
@Service
public class DemoBattleService {

    private static final Logger log = LoggerFactory.getLogger(DemoBattleService.class);

    /** По этому названию демонстрация и находит свою прошлую партию, чтобы её убрать. */
    public static final String DEMO_GAME_NAME = "Демонстрационный бой";

    /** На сколько процентов вторая сторона сильнее первой. */
    public static final int ADVANTAGE_PERCENT = 10;

    /** Кораблей у стороны — не больше шести, как просили от демонстрации. */
    private static final int SHIPS_PER_SIDE = 6;

    /** Предел шагов подбора на корабль: больше стволов в корпус всё равно не влезет. */
    private static final int MAX_WEAPONS_PER_SHIP = 40;

    /** Имена сторон: в бою они называются так же, как игроки. */
    private static final String LEFT_NAME = "Флот Терран";
    private static final String RIGHT_NAME = "Флот Мрргов";

    /**
     * Строй первой стороны: разные корпуса и разное оружие — ради этого демонстрация и
     * затевается. Порядок важен: он же и порядок расстановки на поле.
     * <p>
     * У тяжёлых кораблей оружие тяжёлое не для красоты: щит гасит выстрел целиком, если
     * тот слабее его, и флот из одних масс-драйверов об дредноут со щитом просто
     * разбивается — бой встал бы на месте, а показывать нужно бой.
     */
    private static final List<Recipe> LEFT = List.of(
            new Recipe("Дредноут «Терра»", "battleship", "fusion-drive", "tritanium-armor",
                    null, "optronic-computer", "phasor", 4),
            new Recipe("Крейсер «Заря»", "cruiser", "fusion-drive", "titanium-armor",
                    "class-i-shield", "electronic-computer", "gauss-cannon", 4),
            new Recipe("Крейсер «Гроза»", "cruiser", "ion-drive", "titanium-armor",
                    null, "electronic-computer", "neutron-blaster", 4),
            new Recipe("Эсминец «Стриж»", "destroyer", "ion-drive", "titanium-armor",
                    null, "electronic-computer", "fusion-beam", 4),
            new Recipe("Фрегат «Игла»", "frigate", "ion-drive", "titanium-armor",
                    null, null, "mass-driver", 3),
            new Recipe("Фрегат «Оса»", "frigate", "fusion-drive", "titanium-armor",
                    null, null, "laser-cannon", 4));

    /**
     * Строй второй стороны: другие корпуса, другое оружие и перевес в силе.
     * <p>
     * Числа стволов здесь — только начальные: их подбирает {@link #balance}, чтобы
     * сторона вышла сильнее ровно на {@link #ADVANTAGE_PERCENT} процентов.
     */
    private static final List<Recipe> RIGHT = List.of(
            new Recipe("Дредноут «Мрраг»", "battleship", "ion-drive", "tritanium-armor",
                    null, "electronic-computer", "plasma-cannon", 3),
            new Recipe("Крейсер «Коготь»", "cruiser", "fusion-drive", "tritanium-armor",
                    "class-i-shield", "optronic-computer", "graviton-beam", 4),
            new Recipe("Крейсер «Клык»", "cruiser", "ion-drive", "titanium-armor",
                    null, "electronic-computer", "ion-pulse-cannon", 4),
            new Recipe("Эсминец «Тень»", "destroyer", "fusion-drive", "titanium-armor",
                    null, "optronic-computer", "merculite-missile", 3),
            new Recipe("Эсминец «Вихрь»", "destroyer", "ion-drive", "titanium-armor",
                    null, null, "mass-driver", 5),
            new Recipe("Фрегат «Коса»", "frigate", "fusion-drive", "titanium-armor",
                    null, null, "nuclear-missile", 2));

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final PlayerRoster playerRoster;
    private final ShipCatalog shipCatalog;
    private final ShipDesignRules shipDesignRules;
    private final ShipDesignRepository shipDesignRepository;
    private final ShipDesignComponentRepository shipDesignComponentRepository;
    private final TacticalBattleService battles;
    private final FleetService fleetService;
    private final RaceService raceService;

    public DemoBattleService(GameRepository gameRepository,
                             PlayerRepository playerRepository,
                             PlayerRoster playerRoster,
                             ShipCatalog shipCatalog,
                             ShipDesignRules shipDesignRules,
                             ShipDesignRepository shipDesignRepository,
                             ShipDesignComponentRepository shipDesignComponentRepository,
                             TacticalBattleService battles,
                             FleetService fleetService,
                             RaceService raceService) {
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.playerRoster = playerRoster;
        this.shipCatalog = shipCatalog;
        this.shipDesignRules = shipDesignRules;
        this.shipDesignRepository = shipDesignRepository;
        this.shipDesignComponentRepository = shipDesignComponentRepository;
        this.battles = battles;
        this.fleetService = fleetService;
        this.raceService = raceService;
    }

    /**
     * Один корабль демонстрации: корпус, начинка и сколько стволов основного оружия.
     * <p>
     * Оружие у каждого своё: демонстрация показывает, что бой считается по составу
     * корабля, а не по одному числу.
     */
    private record Recipe(String name, String hull, String engine, String armour, String shield,
                          String computer, String weapon, int weapons) {
    }

    /** Готовый демонстрационный бой и силы сторон — их показывает экран. */
    public record Demo(SpaceBattleEntity battle, GameEntity game,
                       PlayerEntity left, PlayerEntity right,
                       Integer leftPower, Integer rightPower) {
    }

    /** Заводит новую демонстрацию — п. 8. Прошлая при этом убирается. */
    @Transactional
    public Demo start() {
        gameRepository.findAllByName(DEMO_GAME_NAME).forEach(gameRepository::delete);
        gameRepository.flush();

        GameEntity game = demoGame();
        List<PlayerEntity> players = playerRoster.aiPlayers(game, List.of());
        PlayerEntity left = players.get(0);
        PlayerEntity right = players.get(1);
        left.setName(LEFT_NAME);
        right.setName(RIGHT_NAME);
        playerRepository.saveAll(List.of(left, right));
        playerRepository.flush();

        List<Design> leftFleet = build(game, left, LEFT);
        List<Design> rightFleet = build(game, right, RIGHT);
        // Вторая сторона должна быть сильнее ровно настолько, насколько задумано:
        // подгоняем числом стволов её последнего корабля.
        rightFleet = balance(game, right, RIGHT, power(leftFleet));

        SpaceBattleEntity battle = battles.startBetween(
                game.getId(), UUID.randomUUID(), game.getTurn(),
                left, counts(leftFleet), right, counts(rightFleet),
                game.getSeed());

        log.info("Демонстрационный бой {}: силы {} против {}",
                battle.getId(), power(leftFleet), power(rightFleet));
        return new Demo(battle, game, left, right, power(leftFleet), power(rightFleet));
    }

    /** Готовая демонстрация для экрана: поле и силы, с которыми стороны сошлись. */
    @Transactional
    public DemoBattleDto startDto() {
        Demo demo = start();
        Map<UUID, PlayerEntity> players = players(demo.game().getId());
        return new DemoBattleDto(
                toDto(battles.view(demo.battle().getId()), demo.battle(), players),
                demo.left().getName(),
                demo.right().getName(),
                demo.leftPower(),
                demo.rightPower(),
                ADVANTAGE_PERCENT);
    }

    /** Шаг демонстрации: сервер ходит за корабль, чья очередь. */
    @Transactional
    public BattleDto step(UUID battleId) {
        SpaceBattleEntity battle = requireDemo(battleId);
        Map<UUID, PlayerEntity> players = players(battle.getGameId());
        return toDto(battles.autoStep(battleId, players), battle, players);
    }

    /** Состояние демонстрации: тем же ответом, что и у настоящего боя. */
    @Transactional(readOnly = true)
    public BattleDto view(UUID battleId) {
        SpaceBattleEntity battle = requireDemo(battleId);
        Map<UUID, PlayerEntity> players = players(battle.getGameId());
        return toDto(battles.view(battleId), battle, players);
    }

    /** Ответ для экрана: смотрит демонстрация глазами первой стороны. */
    public BattleDto toDto(TacticalBattleService.BattleView view, SpaceBattleEntity battle,
                           Map<UUID, PlayerEntity> players) {
        PlayerEntity viewer = players.get(battle.getAttackerPlayerId());
        return battles.toDto(view, viewer, players, DEMO_GAME_NAME);
    }

    /**
     * Бой демонстрации, и только он.
     * <p>
     * Проверка нужна затем, что эндпоинты демонстрации открыты — пропуска игрока у них
     * нет. Без неё этим ходом можно было бы двигать чужие корабли в настоящей партии.
     */
    private SpaceBattleEntity requireDemo(UUID battleId) {
        SpaceBattleEntity battle = battles.require(battleId);
        GameEntity game = gameRepository.findById(battle.getGameId())
                .orElseThrow(() -> new NotFoundException("battle.gameNotFound"));
        if (!DEMO_GAME_NAME.equals(game.getName())) {
            throw new ForbiddenException("battle.notDemo");
        }
        return battle;
    }

    /** Партия-подложка: галактики, колоний и хода в ней нет — только игроки и корабли. */
    private GameEntity demoGame() {
        GameEntity game = new GameEntity();
        game.setName(DEMO_GAME_NAME);
        game.setGalaxySize(GalaxySize.SMALL);
        game.setWidthParsecs(GalaxySize.SMALL.getWidthParsecs());
        game.setHeightParsecs(GalaxySize.SMALL.getHeightParsecs());
        game.setStarCount(GalaxySize.SMALL.getStarCount());
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setTurn(1);
        game.setTotalPlayers(2);
        game.setMaxHumanPlayers(0);
        game.setSeed(System.nanoTime());
        game.setCreatedAt(OffsetDateTime.now());
        return gameRepository.saveAndFlush(game);
    }

    /** Проект демонстрации: сохранённый корабль и его характеристики. */
    private record Design(ShipDesignEntity design, ShipStats stats) {
    }

    /**
     * Собирает строй по рецептам и сохраняет проекты игроку.
     * <p>
     * Число стволов рецепта обрезается по месту корпуса ({@link #maxWeapons}) — ровно как у
     * второй стороны, которую считает {@link #balance}. Иначе правка размеров оружия в
     * справочнике молча выпускала бы на поле корабль, который в игре не собрать:
     * {@code save} состав не проверяет, а рецепт писался под прежние числа.
     */
    private List<Design> build(GameEntity game, PlayerEntity owner, List<Recipe> recipes) {
        RaceEffects race = raceService.effects(owner);
        List<Design> fleet = new ArrayList<>(recipes.size());
        int slot = 1;
        for (Recipe recipe : recipes.subList(0, Math.min(SHIPS_PER_SIDE, recipes.size()))) {
            int weapons = Math.min(recipe.weapons(), maxWeapons(recipe));
            fleet.add(save(game, owner, recipe, slot++, weapons, race));
        }
        return fleet;
    }

    /**
     * Подгоняет силу второй стороны под нужный перевес — п. 8.
     * <p>
     * Ручка одна на корабль: число стволов. Подбор идёт довеском — на каждом шаге
     * пробуется добавить или снять один ствол у любого корабля, и берётся тот шаг,
     * который сильнее приближает сумму к цели. Шагов больше нет — подбор кончен.
     * <p>
     * Так подгонка не зависит ни от порядка кораблей, ни от того, какое у кого оружие:
     * рецепты правятся свободно, а перевес остаётся заданным. Точное попадание в проценты
     * невозможно — сила считается целыми числами, а стволы добавляются шагами, поэтому
     * достигнутый перевес отдаётся числом и виден на экране.
     */
    private List<Design> balance(GameEntity game, PlayerEntity owner, List<Recipe> recipes,
                                 Integer targetBase) {
        int wanted = targetBase * (100 + ADVANTAGE_PERCENT) / 100;
        RaceEffects race = raceService.effects(owner);

        int[] counts = new int[recipes.size()];
        int[] limits = new int[recipes.size()];
        for (int index = 0; index < recipes.size(); index++) {
            limits[index] = maxWeapons(recipes.get(index));
            counts[index] = Math.min(recipes.get(index).weapons(), limits[index]);
        }

        int gap = Math.abs(total(recipes, counts, race) - wanted);
        for (int step = 0; step < recipes.size() * MAX_WEAPONS_PER_SHIP; step++) {
            int bestShip = -1;
            int bestDelta = 0;
            for (int index = 0; index < recipes.size(); index++) {
                for (int delta : new int[]{1, -1}) {
                    int next = counts[index] + delta;
                    if (next < 1 || next > limits[index]) {
                        continue;
                    }
                    counts[index] = next;
                    int candidate = Math.abs(total(recipes, counts, race) - wanted);
                    counts[index] -= delta;
                    if (candidate < gap) {
                        gap = candidate;
                        bestShip = index;
                        bestDelta = delta;
                    }
                }
            }
            if (bestShip < 0) {
                break;
            }
            counts[bestShip] += bestDelta;
        }

        List<Design> fleet = new ArrayList<>(recipes.size());
        for (int index = 0; index < recipes.size(); index++) {
            fleet.add(save(game, owner, recipes.get(index), index + 1, counts[index], race));
        }
        return fleet;
    }

    /** Сила строя при таком наборе стволов — без сохранения проектов. */
    private int total(List<Recipe> recipes, int[] counts, RaceEffects race) {
        int total = 0;
        for (int index = 0; index < recipes.size(); index++) {
            total += shipPower(recipes.get(index), counts[index], race);
        }
        return total;
    }

    /** Сколько стволов такого оружия влезает в корпус вместе с остальной начинкой. */
    private int maxWeapons(Recipe recipe) {
        ShipHull hull = shipCatalog.hull(recipe.hull());
        int used = 0;
        for (String code : new String[]{recipe.engine(), recipe.armour(), recipe.shield(), recipe.computer()}) {
            if (code != null) {
                used += shipCatalog.component(code).spaceOn(hull);
            }
        }
        int perWeapon = shipCatalog.component(recipe.weapon()).spaceOn(hull);
        return Math.max(1, (hull.space() - used) / perWeapon);
    }

    /** Сила одного корабля рецепта с таким числом стволов — теми же правилами, что в игре. */
    private Integer shipPower(Recipe recipe, int weapons, RaceEffects race) {
        return shipDesignRules.power(stats(recipe, weapons, race));
    }

    /**
     * Характеристики корабля рецепта — <b>с расой владельца</b>, как в настоящем бою.
     * <p>
     * Здесь стояло {@code RaceEffects.NONE}, и это был не выбор, а недосмотр: расы
     * игрокам демонстрации раздаёт жребий ({@link PlayerRoster#aiPlayers}), а бой считает
     * корабль с расовыми прибавками владельца ({@code TacticalBattleService.context}).
     * Экран поэтому показывал силу стороны ДВАЖДЫ и по-разному: «справа 1310» из этого
     * расчёта и «обороняющийся 1215» из самого боя, — и заодно перевес
     * {@link #ADVANTAGE_PERCENT} подбирался по числам, которых в бою нет.
     */
    private ShipStats stats(Recipe recipe, int weapons, RaceEffects race) {
        ShipHull hull = shipCatalog.hull(recipe.hull());
        return shipDesignRules.stats(hull, items(recipe, weapons), race);
    }

    private List<ShipDesignRules.Item> items(Recipe recipe, int weapons) {
        List<ShipDesignRules.Item> items = new ArrayList<>();
        for (String code : new String[]{recipe.engine(), recipe.armour(), recipe.shield(), recipe.computer()}) {
            if (code != null) {
                items.add(new ShipDesignRules.Item(shipCatalog.component(code), 1));
            }
        }
        ShipComponent weapon = shipCatalog.component(recipe.weapon());
        items.add(new ShipDesignRules.Item(weapon, weapons));
        return items;
    }

    /**
     * Сохраняет проект демонстрации.
     * <p>
     * Мимо {@code ShipDesignService.save}: тот проверяет изученные технологии, а у
     * игроков демонстрации их нет вовсе — им и исследовать нечего. Проверять здесь нечего
     * и незачем: состав задан рецептом в коде, а не игроком.
     */
    private Design save(GameEntity game, PlayerEntity owner, Recipe recipe, int slot, int weapons,
                        RaceEffects race) {
        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(owner.getId());
        design.setSlot(slot);
        design.setName(recipe.name());
        design.setHullCode(recipe.hull());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        shipDesignRepository.saveAndFlush(design);

        List<ShipDesignComponentEntity> parts = new ArrayList<>();
        int order = 0;
        for (ShipDesignRules.Item item : items(recipe, weapons)) {
            ShipDesignComponentEntity part = new ShipDesignComponentEntity();
            part.setDesignId(design.getId());
            part.setComponentCode(item.component().code());
            part.setCount(item.count());
            part.setSortOrder(order++);
            parts.add(part);
        }
        shipDesignComponentRepository.saveAll(parts);
        shipDesignComponentRepository.flush();

        return new Design(design, stats(recipe, weapons, race));
    }

    /** Сила строя: сумма боевых сил его кораблей — теми же правилами, что у флотов. */
    private Integer power(List<Design> fleet) {
        return fleet.stream().mapToInt(ship -> shipDesignRules.power(ship.stats())).sum();
    }

    /** Состав для расстановки: по кораблю на проект. */
    private List<TacticalBattleService.ShipCount> counts(List<Design> fleet) {
        return fleet.stream()
                .map(ship -> new TacticalBattleService.ShipCount(ship.design().getId(), 1))
                .toList();
    }

    private Map<UUID, PlayerEntity> players(UUID gameId) {
        return fleetService.playersById(playerRepository.findAllByGameIdOrderBySlotAsc(gameId));
    }
}
