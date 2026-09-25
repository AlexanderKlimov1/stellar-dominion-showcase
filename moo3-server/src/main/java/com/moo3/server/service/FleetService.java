package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.FleetShipEntity;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.enums.ShipRole;
import com.moo3.server.domain.enums.SpaceMonster;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.dto.FleetGroupDto;
import com.moo3.server.dto.FleetShipDto;
import com.moo3.server.dto.FleetShipOrder;
import com.moo3.server.dto.FleetWeaponDto;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.FleetShipRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.repository.StarSystemRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Флоты империй — п. 8.
 * <p>
 * Корабль строит колония выбранным проектом (п. 8), а встаёт он в флот своей системы: флот
 * у игрока один на систему. Флот знает, из чего собран: {@link FleetShipEntity} держит
 * корабли по проектам, а общее их число остаётся в самом флоте — по нему считают бой,
 * разведка и слепок партии. Обе величины меняются здесь и только вместе.
 * <p>
 * <b>Сила флота</b> — сумма боевых сил его кораблей, а сила корабля приходит из его
 * проекта ({@code ShipDesignRules.power}). Поэтому десять фрегатов больше не равны десяти
 * дредноутам, как было, пока корабль был безымянным.
 * <p>
 * <b>Инициатива</b> решает, кто при встрече выбирает первым: атаковать или разойтись.
 * Сила флота, помноженная на скорость его кораблей: быстрый успевает навязать бой,
 * сильный — его выдержать. Реконструкция: в MOO II очередь хода задаёт тактическая сцена,
 * которой в игре ещё нет. Формула правится здесь, остальному коду видно только число.
 * <p>
 * <b>Перелёт мгновенный</b> — времени в пути нет. Это упрощение: расстояния и скорости
 * встанут сюда же, а встречи флотов и бой от этого не изменятся.
 * <p>
 * <b>Разведка — дело флота</b> (п. 15). Лететь можно к любой звезде: светило видно из
 * любой точки галактики, а вот что в системе — неизвестно, пока туда не пришёл корабль.
 * Приход флота и открывает систему: её название, планеты и хозяев, а заодно знакомит с
 * этими хозяевами. Сканеры сюда не вмешиваются — они показывают присутствие, но не состав.
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    private final FleetRepository fleetRepository;
    private final EmpireActivityService activity;
    private final FleetShipRepository fleetShipRepository;
    private final StarSystemRepository starSystemRepository;
    private final RaceService raceService;
    private final ExplorationService explorationService;
    private final PlayerEventService playerEvents;
    private final ShipDesignService shipDesignService;
    /**
     * Договоры — п. 15: минное поле своих и союзников не трогает.
     * <p>
     * Зависимость односторонняя: дипломатия о флотах ничего не знает, и кольца не выходит.
     */
    private final DiplomacyService diplomacyService;
    private final ShipDesignRules shipDesignRules;
    private final ShipCatalog shipCatalog;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final FlightRules flightRules;
    /** Награда за разведку системы с находкой — п. 4.1: артефакты платят нашедшему. */
    private final PlanetFindReward findReward;
    private final LeaderBonusService leaderBonuses;

    public FleetService(FleetRepository fleetRepository,
                        FleetShipRepository fleetShipRepository,
                        StarSystemRepository starSystemRepository,
                        RaceService raceService,
                        ExplorationService explorationService,
                        PlayerEventService playerEvents,
                        ShipDesignService shipDesignService,
                        ShipDesignRules shipDesignRules,
                        ShipCatalog shipCatalog,
                        PlayerTechnologyRepository playerTechnologyRepository,
                        FlightRules flightRules,
                        LeaderBonusService leaderBonuses,
                        EmpireActivityService activity,
                        DiplomacyService diplomacyService,
                        PlanetFindReward findReward) {
        this.diplomacyService = diplomacyService;
        this.findReward = findReward;
        this.leaderBonuses = leaderBonuses;
        this.activity = activity;
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.starSystemRepository = starSystemRepository;
        this.raceService = raceService;
        this.explorationService = explorationService;
        this.playerEvents = playerEvents;
        this.shipDesignService = shipDesignService;
        this.shipDesignRules = shipDesignRules;
        this.shipCatalog = shipCatalog;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.flightRules = flightRules;
    }

    /**
     * Флоты игрока — п. 8: и стоящие в системах, и те, что сейчас в пути.
     * <p>
     * Летящие приходят вместе со стоящими: игрок должен видеть на карте, куда ушли его
     * корабли, — иначе отданный приказ исчезал бы до самого прибытия.
     */
    @Transactional(readOnly = true)
    public List<FleetGroupDto> fleetsOf(PlayerEntity player) {
        List<FleetEntity> fleets = fleetRepository.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .toList();
        if (fleets.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> names = systemNames(player.getGame().getId());
        Combat combat = combat(player);
        Map<UUID, List<FleetShipEntity>> composition = composition(fleets);

        return fleets.stream()
                .map(fleet -> toDto(fleet, composition.getOrDefault(fleet.getId(), List.of()), combat, names))
                .sorted(Comparator.comparing(FleetGroupDto::systemName))
                .toList();
    }

    /** Флот для клиента: состав, боевые числа и маршрут, если флот в пути. */
    private FleetGroupDto toDto(FleetEntity fleet, List<FleetShipEntity> rows, Combat combat,
                                Map<UUID, String> names) {
        Integer power = combat.power(rows, fleet.getShips());
        return new FleetGroupDto(
                fleet.getId(),
                fleet.getStarSystemId(),
                names.getOrDefault(fleet.getStarSystemId(), "неизвестная система"),
                fleet.getShips(),
                initiative(power, combat.speed(rows)),
                power,
                combat.speed(rows),
                ships(rows, combat),
                fleet.getTargetSystemId(),
                fleet.getTargetSystemId() == null ? null : names.get(fleet.getTargetSystemId()),
                fleet.getDepartureTurn(),
                fleet.getArrivalTurn());
    }

    /**
     * Характеристики проектов одного игрока — всё, что нужно, чтобы посчитать силу его
     * флотов. Берётся один раз на игрока: состав флотов читается пачкой, по флоту за
     * выборку не ходим.
     */
    private Combat combat(PlayerEntity player) {
        // Составы вычитываются ОДИН раз и отдаются обоим, кому они нужны: прежде
        // statsOf ходил за ними сам, а следом за ними же шёл itemsOf — два одинаковых
        // запроса на каждую сборку боевого контекста, а собирается он у всякого флота.
        Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(player);
        Map<UUID, java.util.List<ShipDesignRules.Item>> items =
                shipDesignService.itemsOf(designs.values());
        RaceEffects race = raceService.effects(player);
        return new Combat(shipDesignService.statsFromItems(designs.values(), items, race),
                designs, items, shipCatalog, shipDesignRules, race);
    }

    /**
     * Проекты игрока с их характеристиками: по ним считаются сила и скорость флота.
     * Проект корабля мог быть вытеснен новым — корабли по нему всё равно летают, поэтому
     * здесь и действующие, и вытесненные.
     */
    private record Combat(Map<UUID, ShipStats> stats,
                          Map<UUID, ShipDesignEntity> designs,
                          Map<UUID, List<ShipDesignRules.Item>> items,
                          ShipCatalog catalog,
                          ShipDesignRules rules,
                          RaceEffects race) {

        /**
         * Сила флота: сумма боевых сил его кораблей.
         * <p>
         * Состава может не быть вовсе — партия загружена из слепка, снятого до появления
         * проектов. Тогда корабль весит единицу, как весил безымянный корабль раньше:
         * флот без известного состава всё равно должен что-то значить в бою.
         */
        Integer power(List<FleetShipEntity> rows, Integer ships) {
            if (rows.isEmpty()) {
                return ships;
            }
            int total = 0;
            for (FleetShipEntity row : rows) {
                // Гражданский корабль силы флоту не добавляет: в MOO II он в бой не выходит
                // вовсе — п. 8. Флот из одних колониальных кораблей стоит ноль, и сосед,
                // сравнивая силы, видит именно это.
                if (!Boolean.TRUE.equals(combatShip(row.getDesignId()))) {
                    continue;
                }
                total += shipPower(row.getDesignId()) * row.getShips();
            }
            return total;
        }

        /**
         * Боевой ли это корабль. Проекта может не быть у партии из старого слепка — тогда
         * корабль считается боевым: гражданских в ней и не было.
         */
        Boolean combatShip(UUID designId) {
            ShipDesignEntity design = designs.get(designId);
            return design == null || Boolean.TRUE.equals(design.getRole().isCombat());
        }

        /**
         * Скорость флота — по самому медленному кораблю: строй идёт со скоростью
         * отстающего, как и положено флоту.
         * <p>
         * Надпространственная раса (п. 7) прибавляет свои парсеки уже к скорости строя,
         * а не к каждому кораблю: сама она складывает пространство, и складывает его
         * для всего флота разом.
         */
        Integer speed(List<FleetShipEntity> rows) {
            int speed = Integer.MAX_VALUE;
            for (FleetShipEntity row : rows) {
                ShipStats value = stats.get(row.getDesignId());
                if (value != null) {
                    speed = Math.min(speed, value.speed());
                }
            }
            int slowest = speed == Integer.MAX_VALUE ? 1 : speed;
            return Math.max(1, slowest + race.shipSpeedParsecs());
        }

        /**
         * Сила одного корабля проекта. Проекта может не оказаться у партии, начатой до
         * появления подсистемы: тогда корабль весит единицу — столько же, сколько весил
         * безымянный корабль раньше.
         */
        Integer shipPower(UUID designId) {
            ShipStats value = stats.get(designId);
            return value == null ? 1 : rules.power(value);
        }

        String designName(UUID designId) {
            ShipDesignEntity design = designs.get(designId);
            return design == null ? "неизвестный проект" : design.getName();
        }

        Boolean obsolete(UUID designId) {
            ShipDesignEntity design = designs.get(designId);
            return design != null && Boolean.TRUE.equals(design.getObsolete());
        }

        /**
         * Корпус проекта; {@code null} — проекта нет вовсе. Так бывает у партии, начатой
         * до появления проектов кораблей: корабль в строю есть, а чертежа под ним нет.
         */
        ShipHull hull(UUID designId) {
            ShipDesignEntity design = designs.get(designId);
            return design == null ? null : catalog.hull(design.getHullCode());
        }

        /** Компоненты проекта в порядке справочника; пусто — проект неизвестен. */
        List<ShipDesignRules.Item> componentsOf(UUID designId) {
            return items.getOrDefault(designId, List.of());
        }

        /**
         * Щит корабля — карточка экрана флота пишет его название, а не число: в MOO II
         * там стоит «Class III Shield» либо «No Shield». Число щита и без того входит
         * в общую живучесть корабля.
         */
        String shield(UUID designId) {
            return componentsOf(designId).stream()
                    .filter(item -> item.component().slot() == ShipComponentSlot.SHIELD)
                    .map(item -> item.component().name())
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Вооружение корабля строками «сколько — чем — на какой урон» (п. 8).
         * <p>
         * Урон строки считается тем же произведением, что копится в залп проекта: урон
         * ствола на число выстрелов и на число стволов. Приборы и раса сюда не входят —
         * они уже учтены в залпе всего корабля, и в строке дали бы двойной счёт.
         */
        List<FleetWeaponDto> weapons(UUID designId) {
            return componentsOf(designId).stream()
                    .filter(item -> item.component().slot() == ShipComponentSlot.WEAPON)
                    .map(item -> new FleetWeaponDto(
                            item.component().name(),
                            item.count(),
                            item.component().amount(ShipEffectType.WEAPON_DAMAGE)
                                    * item.component().amount(ShipEffectType.WEAPON_SHOTS)
                                    * item.count()))
                    .toList();
        }

        /** Особые модули корабля: боевые отсеки, усиленный корпус, десант. */
        List<String> specials(UUID designId) {
            return componentsOf(designId).stream()
                    .filter(item -> item.component().slot() == ShipComponentSlot.SPECIAL)
                    .map(item -> item.count() > 1
                            ? item.component().name() + " × " + item.count()
                            : item.component().name())
                    .toList();
        }
    }

    /** Состав флота для экрана: какие корабли и сколько их. */
    private List<FleetShipDto> ships(List<FleetShipEntity> rows, Combat combat) {
        List<FleetShipDto> ships = new ArrayList<>(rows.size());
        for (FleetShipEntity row : rows) {
            ShipStats stats = combat.stats().get(row.getDesignId());
            ShipDesignEntity design = combat.designs().get(row.getDesignId());
            ShipHull hull = combat.hull(row.getDesignId());
            ships.add(new FleetShipDto(
                    row.getDesignId(),
                    combat.designName(row.getDesignId()),
                    design == null ? null : design.getHullCode(),
                    hull == null ? null : hull.name(),
                    hull == null ? null : hull.sortOrder(),
                    row.getShips(),
                    stats == null ? 0 : stats.attack(),
                    stats == null ? 0 : stats.defense(),
                    combat.shield(row.getDesignId()),
                    combat.weapons(row.getDesignId()),
                    combat.specials(row.getDesignId()),
                    combat.obsolete(row.getDesignId()),
                    design == null ? ShipRole.WARSHIP.name() : design.getRole().name(),
                    row.getColonists()));
        }
        return ships;
    }

    /** Состав нескольких флотов — одной выборкой. */
    public Map<UUID, List<FleetShipEntity>> composition(List<FleetEntity> fleets) {
        List<UUID> ids = fleets.stream().map(FleetEntity::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<FleetShipEntity>> composition = new HashMap<>(ids.size());
        for (FleetShipEntity row : fleetShipRepository.findAllByFleetIdIn(ids)) {
            composition.computeIfAbsent(row.getFleetId(), key -> new ArrayList<>()).add(row);
        }
        return composition;
    }

    /**
     * Ставит построенный корабль в строй — вызывается фазой производства (п. 10).
     * Флот заводится сам, если в этой системе его ещё не было.
     * <p>
     * Корабль встаёт в строй со своим проектом: строка состава либо находится, либо
     * заводится. Общее число кораблей флота растёт вместе с ней — эти две величины
     * расходиться не должны.
     */
    @Transactional
    public FleetEntity addShip(UUID gameId, UUID ownerPlayerId, UUID starSystemId, Integer turn,
                               UUID designId) {
        return addShip(gameId, ownerPlayerId, starSystemId, turn, designId, 0);
    }

    /**
     * Тот же новый корабль, но с грузом жителей — п. 4.1, п. 12: поселенцы колониального
     * корабля и десант транспорта. Груз копится на строке состава: два транспорта в одной
     * строке везут вдвое больше бойцов.
     */
    @Transactional
    public FleetEntity addShip(UUID gameId, UUID ownerPlayerId, UUID starSystemId, Integer turn,
                               UUID designId, Integer colonists) {
        FleetEntity fleet = fleetRepository
                .findByOwnerPlayerIdAndStarSystemIdAndTargetSystemIdIsNull(ownerPlayerId, starSystemId)
                .orElseGet(() -> {
                    FleetEntity created = new FleetEntity();
                    created.setGameId(gameId);
                    created.setOwnerPlayerId(ownerPlayerId);
                    created.setStarSystemId(starSystemId);
                    created.setShips(0);
                    created.setCreatedTurn(turn);
                    return created;
                });
        fleet.setShips(fleet.getShips() + 1);
        FleetEntity saved = fleetRepository.saveAndFlush(fleet);

        FleetShipEntity row = fleetShipRepository.findByFleetIdAndDesignId(saved.getId(), designId)
                .orElseGet(() -> {
                    FleetShipEntity created = new FleetShipEntity();
                    created.setFleetId(saved.getId());
                    created.setDesignId(designId);
                    created.setShips(0);
                    return created;
                });
        row.setShips(row.getShips() + 1);
        row.setColonists(row.getColonists() + colonists);
        fleetShipRepository.save(row);
        return saved;
    }

    /**
     * Перелёт флота — п. 8: флот уходит в другую систему и оказывается там не сразу.
     * <p>
     * Приказ задаёт курс: флот покидает свою систему и идёт по прямой к цели столько ходов,
     * сколько выходит по расстоянию и скорости самого медленного его корабля. Пока он в
     * пути, его нет нигде — он не держит систему вылета и не разведывает систему
     * назначения; и то и другое случится в конце хода прибытия ({@link #arrive}).
     * <p>
     * Лететь можно к любой звезде партии, в том числе к неразведанной: разведать её
     * иначе нечем — п. 15. Но не дальше, чем позволяет топливо: дальность меряется от
     * ближайшей своей колонии — {@link com.moo3.server.domain.enums.FuelTech}.
     * <p>
     * <b>Лететь может часть флота.</b> Пустой список кораблей — это весь флот; список —
     * отобранные игроком корабли по проектам. Отправить весь состав поимённо — то же самое,
     * что отправить флот целиком, поэтому такой запрос и считается целым перелётом: у
     * покинутой системы не должно оставаться пустого флота.
     * <p>
     * <b>Флоту в пути приказ отдать нельзя</b>, пока империя не изучит Hyperspace
     * Communications: связи с кораблями в подпространстве нет — п. 8.
     *
     * @param order сколько кораблей какого проекта уходит; пусто — весь флот
     */
    @Transactional
    public FleetGroupDto move(GameEntity game, PlayerEntity player, UUID fleetId,
                              UUID targetSystemId, List<FleetShipOrder> order) {
        FleetEntity fleet = fleetRepository.findById(fleetId)
                .orElseThrow(() -> new NotFoundException("fleet.notFound", fleetId));
        if (!fleet.getOwnerPlayerId().equals(player.getId())) {
            throw new ConflictException("fleet.notYours");
        }

        StarSystemEntity target = starSystemRepository.findById(targetSystemId)
                .orElseThrow(() -> new NotFoundException("system.notFound", targetSystemId));
        if (!target.getGame().getId().equals(game.getId())) {
            throw new ConflictException("system.otherGame");
        }
        if (targetSystemId.equals(fleet.getTargetSystemId())) {
            throw new ConflictException("fleet.alreadyHeading");
        }
        if (!Boolean.TRUE.equals(fleet.isInFlight()) && fleet.getStarSystemId().equals(targetSystemId)) {
            throw new ConflictException("fleet.alreadyThere");
        }

        Set<String> technologies = technologies(player);
        requireInRange(game, player, target, technologies);

        Combat combat = combat(player);
        if (Boolean.TRUE.equals(fleet.isInFlight())) {
            return redirect(game, fleet, target, technologies, combat);
        }

        Map<UUID, Integer> taken = split(fleet, order);
        FleetEntity flying = taken.isEmpty()
                ? depart(game, fleet, target, combat)
                : detach(game, player, fleet, target, taken, combat);

        log.info("Игрок {} отправил {} кораблей в систему {}: в пути до хода {}",
                player.getName(), flying.getShips(), target.getName(), flying.getArrivalTurn());
        return toDto(flying, fleetShipRepository.findAllByFleetIdOrderByIdAsc(flying.getId()), combat,
                systemNames(game.getId()));
    }

    /**
     * Списание кораблей со службы — п. 8, кнопка SCRAP окна Fleet Operations MOO II.
     * <p>
     * В оригинале это единственное место, откуда корабль убирают из строя, и убирают его
     * насовсем: устаревший фрегат не переделать в дредноут, его можно только списать.
     * Освободившееся место — не производство и не кредиты, а очки командования империи:
     * MOO II берёт по 10 кредитов за ход с каждого корабля сверх них, и списание лишнего
     * этот побор и снимает.
     * <p>
     * <b>Возврата за списанный корабль здесь нет</b>, и в оригинале его тоже нет: корабль
     * не разбирают на части, он просто перестаёт числиться. <i>Точка расширения:</i> очков
     * командования игра ещё не считает — {@code ShipStats.command()} у корабля есть, но
     * содержания флота за них не берут, — поэтому пока списание только освобождает флот
     * от балласта. Когда содержание появится, оно встанет в фазу производства, а это
     * действие не изменится.
     * <p>
     * Списывать можно и флот в пути: корабли уходят из строя, где бы ни были, — приказ
     * о роспуске это не смена курса, связь для него не нужна.
     *
     * @param order сколько кораблей какого проекта уходит из строя; пустым не бывает
     * @return флоты игрока заново: списанный целиком флот из списка исчезает
     */
    @Transactional
    public List<FleetGroupDto> scrap(PlayerEntity player, UUID fleetId, List<FleetShipOrder> order) {
        FleetEntity fleet = fleetRepository.findById(fleetId)
                .orElseThrow(() -> new NotFoundException("fleet.notFound", fleetId));
        if (!fleet.getOwnerPlayerId().equals(player.getId())) {
            throw new ConflictException("fleet.notYours");
        }

        Map<UUID, Integer> scrapped = requireShips(fleet, order);
        Integer removed = scrapped.values().stream().mapToInt(Integer::intValue).sum();

        for (Map.Entry<UUID, Integer> entry : scrapped.entrySet()) {
            FleetShipEntity row = fleetShipRepository
                    .findByFleetIdAndDesignId(fleet.getId(), entry.getKey())
                    .orElseThrow(() -> new ConflictException("fleet.noSuchDesign"));
            Integer left = row.getShips() - entry.getValue();
            if (left > 0) {
                row.setShips(left);
                fleetShipRepository.save(row);
            } else {
                fleetShipRepository.delete(row);
            }
        }

        fleet.setShips(fleet.getShips() - removed);
        if (fleet.getShips() <= 0) {
            // Флота без кораблей быть не должно: пустая строка осталась бы значком на
            // карте и живой целью для встреч.
            fleetRepository.delete(fleet);
        } else {
            fleetRepository.saveAndFlush(fleet);
        }

        log.info("Игрок {} списал {} кораблей флота {}", player.getName(), removed, fleetId);
        return fleetsOf(player);
    }

    /** Состав флота: строки по проектам кораблей — их спрашивают высадка и десант. */
    @Transactional(readOnly = true)
    public List<FleetShipEntity> shipsOf(UUID fleetId) {
        return fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleetId);
    }

    /**
     * Списывает с флота корабли, сделавшие своё дело, — п. 4.1, п. 12.
     * <p>
     * Колониальный корабль в MOO II разбирается на месте и становится частью новой
     * колонии, транспорт высаживает десант и назад не возвращается. Обратно во флот они
     * не встают, поэтому строка состава просто худеет, а вместе с ней уходит и увезённый
     * груз. Опустевший флот удаляется целиком: пустая строка осталась бы значком на карте
     * и живой целью для встреч.
     *
     * @param ships сколько кораблей этой строки высадилось
     */
    @Transactional
    public void discharge(FleetEntity fleet, FleetShipEntity row, Integer ships) {
        Integer left = row.getShips() - ships;
        if (left > 0) {
            // Груз уходит вместе с кораблями: у оставшихся он свой, посчитанный на корабль.
            Integer perShip = row.getColonists() / Math.max(1, row.getShips());
            row.setShips(left);
            row.setColonists(perShip * left);
            fleetShipRepository.save(row);
        } else {
            fleetShipRepository.delete(row);
        }

        fleet.setShips(fleet.getShips() - ships);
        if (fleet.getShips() <= 0) {
            fleetRepository.delete(fleet);
        } else {
            fleetRepository.saveAndFlush(fleet);
        }
    }

    /**
     * Смена курса на полпути — п. 8: доступна только с Hyperspace Communications.
     * <p>
     * Путь считается заново от системы вылета: координат у флота в пути нет, есть только
     * маршрут и сроки. Это упрощение — приказ как будто догоняет корабли в точке входа в
     * подпространство, и оттуда они уходят к новой цели.
     */
    private FleetGroupDto redirect(GameEntity game, FleetEntity fleet, StarSystemEntity target,
                                   Set<String> technologies, Combat combat) {
        if (!Boolean.TRUE.equals(flightRules.canRedirect(technologies))) {
            throw new ConflictException("fleet.noCourseChange");
        }
        FleetEntity flying = depart(game, fleet, target, combat);
        log.info("Флот {} перенаправлен в систему {}", fleet.getId(), target.getName());
        return toDto(flying, fleetShipRepository.findAllByFleetIdOrderByIdAsc(flying.getId()), combat,
                systemNames(game.getId()));
    }

    /**
     * Задаёт курс всему флоту — п. 8.
     * <p>
     * Система вылета остаётся в {@code starSystemId}: по ней рисуется линия полёта, и по
     * ней же считается путь, если курс сменят на полпути.
     */
    private FleetEntity depart(GameEntity game, FleetEntity fleet, StarSystemEntity target, Combat combat) {
        setCourse(game, fleet, target, fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId()), combat,
                navigator(fleet.getOwnerPlayerId(), fleet.getId()));
        return fleetRepository.saveAndFlush(fleet);
    }

    /**
     * Что из флота отправляют, если отправляют не всё.
     * <p>
     * Возвращает пустую карту, когда лететь должен весь флот: и если списка нет вовсе, и
     * если в списке оказался весь состав — тогда дробить нечего, и работает обычный
     * перелёт целиком.
     */
    private Map<UUID, Integer> split(FleetEntity fleet, List<FleetShipOrder> order) {
        if (order.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> taken = requireShips(fleet, order);
        Integer total = taken.values().stream().mapToInt(Integer::intValue).sum();
        if (total.equals(fleet.getShips())) {
            // Отобрали весь состав — это перелёт целиком, а не дробление.
            return Map.of();
        }
        return taken;
    }

    /**
     * Отобранные из флота корабли: проверяет, что такие в нём есть, и сводит вместе
     * повторы одного проекта.
     * <p>
     * Общая проверка для всех действий над частью флота — перелёта и списания: и там, и
     * там игрок отбирает корабли по проектам, и правило «больше, чем есть, не взять» у
     * них одно. Само решение, что делать с отобранным, остаётся за вызывающим.
     */
    private Map<UUID, Integer> requireShips(FleetEntity fleet, List<FleetShipOrder> order) {
        Map<UUID, Integer> available = fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId()).stream()
                .collect(Collectors.toMap(FleetShipEntity::getDesignId, FleetShipEntity::getShips));

        Map<UUID, Integer> taken = new LinkedHashMap<>();
        for (FleetShipOrder row : order) {
            Integer have = available.get(row.designId());
            if (have == null) {
                throw new ConflictException("fleet.noSuchDesign");
            }
            Integer want = taken.merge(row.designId(), row.ships(), Integer::sum);
            if (want > have) {
                throw new ConflictException("fleet.notEnoughShips", want, have);
            }
        }
        return taken;
    }

    /**
     * Отделяет отобранные корабли и отправляет их в путь — п. 8.
     * <p>
     * Отправивший флот остаётся на месте с тем, что не улетело, а ушедшие корабли заводят
     * свой флот — сразу в пути. Так и получаются два флота одного игрока в одной системе:
     * один стоит, второй уходит. Числа флота и его состав правятся вместе: расходиться им
     * нельзя.
     * <p>
     * Курс новому флоту ставится до первой записи в базу: пока цель не проставлена, флот
     * считается стоящим, а стоящий флот игрока в системе может быть только один.
     */
    private FleetEntity detach(GameEntity game, PlayerEntity player, FleetEntity fleet,
                               StarSystemEntity target, Map<UUID, Integer> taken, Combat combat) {
        Integer moved = taken.values().stream().mapToInt(Integer::intValue).sum();

        List<FleetShipEntity> leaving = new ArrayList<>(taken.size());
        for (Map.Entry<UUID, Integer> entry : taken.entrySet()) {
            FleetShipEntity row = fleetShipRepository
                    .findByFleetIdAndDesignId(fleet.getId(), entry.getKey())
                    .orElseThrow(() -> new ConflictException("fleet.noSuchDesign"));
            Integer left = row.getShips() - entry.getValue();
            if (left > 0) {
                row.setShips(left);
                fleetShipRepository.save(row);
            } else {
                fleetShipRepository.delete(row);
            }
            FleetShipEntity gone = new FleetShipEntity();
            gone.setDesignId(entry.getKey());
            gone.setShips(entry.getValue());
            leaving.add(gone);
        }
        fleet.setShips(fleet.getShips() - moved);
        fleetRepository.saveAndFlush(fleet);

        FleetEntity flying = new FleetEntity();
        flying.setGameId(game.getId());
        flying.setOwnerPlayerId(player.getId());
        flying.setStarSystemId(fleet.getStarSystemId());
        flying.setShips(moved);
        flying.setCreatedTurn(game.getTurn());
        // Штурман остаётся с тем флотом, к которому приписан: отделившийся отряд уходит
        // без него, и складывать пространство ему некому — п. 6.
        setCourse(game, flying, target, leaving, combat, 0);
        FleetEntity saved = fleetRepository.saveAndFlush(flying);

        for (FleetShipEntity row : leaving) {
            row.setFleetId(saved.getId());
            fleetShipRepository.save(row);
        }
        return saved;
    }

    /**
     * Курс и сроки: сколько ходов флот идёт от своей системы до цели — п. 8.
     * <p>
     * Скорость берётся по самому медленному кораблю уходящего состава, а не всего флота:
     * летят именно эти корабли, и равняться на отставших, которые остались дома, не за что.
     *
     * @param navigatorParsecs надбавка «Штурмана» к скорости флота — п. 6: он служит на
     *                         флоте, и его парсеки прибавляются самому медленному кораблю
     */
    private void setCourse(GameEntity game, FleetEntity fleet, StarSystemEntity target,
                           List<FleetShipEntity> leaving, Combat combat,
                           Integer navigatorParsecs) {
        StarSystemEntity origin = starSystemRepository.findById(fleet.getStarSystemId())
                .orElseThrow(() -> new NotFoundException("system.notFound", fleet.getStarSystemId()));
        Double distance = flightRules.distanceParsecs(origin.getXParsec(), origin.getYParsec(),
                target.getXParsec(), target.getYParsec());
        Integer turns = flightRules.travelTurns(distance,
                combat.speed(leaving) + navigatorParsecs);

        fleet.setOriginSystemId(origin.getId());
        fleet.setTargetSystemId(target.getId());
        fleet.setDepartureTurn(game.getTurn());
        fleet.setArrivalTurn(game.getTurn() + turns);
    }

    /**
     * Надбавка «Штурмана» этому флоту, парсеков за ход, — п. 6.
     * <p>
     * До места службы офицер добирается пять ходов, и пока не добрался — не считается:
     * это уже решено в {@link LeaderBonusService}, здесь остаётся только спросить.
     */
    private Integer navigator(UUID ownerPlayerId, UUID fleetId) {
        return leaderBonuses.of(ownerPlayerId).fleetValue(fleetId, "NAVIGATOR");
    }

    /**
     * Скорость каждого проекта партии — п. 8: одной выборкой на весь ход.
     * <p>
     * Нужна приказам ИИ (п. 15): их отдаётся несколько за ход, а спрашивать скорость по
     * флоту — та же выборка «на игрока» внутри посчитанного хода, от которой ушёл
     * {@code TurnContext}.
     *
     * @param races расы владельцев: надпространственная раса складывает пространство
     *              для всего своего флота — п. 7
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> designSpeeds(UUID gameId, Map<UUID, RaceEffects> races) {
        Collection<ShipDesignEntity> designs = shipDesignService.designsOfGame(gameId).values();
        Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs);

        Map<UUID, Integer> speeds = new HashMap<>(designs.size());
        for (ShipDesignEntity design : designs) {
            RaceEffects race = races.getOrDefault(design.getOwnerPlayerId(), RaceEffects.NONE);
            Integer speed = shipDesignRules.stats(
                    shipCatalog.hull(design.getHullCode()),
                    items.getOrDefault(design.getId(), List.of()),
                    race).speed();
            speeds.put(design.getId(), Math.max(1, speed + race.shipSpeedParsecs()));
        }
        return speeds;
    }

    /**
     * Приказ флоту из конца хода — п. 15: тем же курсом, что и приказ игрока, но по уже
     * загруженным данным.
     * <p>
     * Проверок дальности и чужого флота здесь нет: их делает тот, кто отдаёт приказ, — у
     * ИИ это {@code AiEmpireService}, и делает он это по той же карте, что уже загружена
     * ходом. Ходить в базу за системой вылета и за составом флота ради каждого приказа
     * значило бы сбрасывать туда весь посчитанный ход.
     *
     * @param speedParsecs скорость самого медленного корабля флота, парсеков за ход
     */
    @Transactional
    public void dispatch(GameEntity game, FleetEntity fleet, StarSystemEntity origin,
                         StarSystemEntity target, Integer speedParsecs) {
        Double distance = flightRules.distanceParsecs(origin.getXParsec(), origin.getYParsec(),
                target.getXParsec(), target.getYParsec());
        Integer turns = flightRules.travelTurns(distance,
                speedParsecs + navigator(fleet.getOwnerPlayerId(), fleet.getId()));

        fleet.setOriginSystemId(origin.getId());
        fleet.setTargetSystemId(target.getId());
        fleet.setDepartureTurn(game.getTurn());
        fleet.setArrivalTurn(game.getTurn() + turns);
        fleetRepository.save(fleet);
        activity.record(game.getId(), fleet.getOwnerPlayerId(), EmpireActivityService.FLIGHT);
    }

    /**
     * Флоты, которым пора прийти, — п. 8. Вызывается фазой прибытия в конце хода, до фазы
     * встреч: пришедший флот должен встретить чужой в той же системе тем же ходом.
     * <p>
     * Сравнение идёт со <b>следующим</b> ходом: фаза работает в конце хода N, а игрок
     * увидит её работу на ходу N + 1 — на него и назначено прибытие ближайшего перелёта.
     */
    @Transactional
    public void arrive(TurnContext context) {
        List<FleetEntity> flying =
                fleetRepository.findAllByGameIdAndTargetSystemIdIsNotNull(context.game().getId());
        if (flying.isEmpty()) {
            return;
        }

        Integer arrivedBy = context.turn() + 1;
        Map<UUID, StarSystemEntity> systems = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, Function.identity()));
        Map<UUID, PlayerEntity> players = playersById(context.players());

        for (FleetEntity fleet : flying) {
            if (fleet.getArrivalTurn() > arrivedBy) {
                continue;
            }
            StarSystemEntity target = systems.get(fleet.getTargetSystemId());
            PlayerEntity owner = players.get(fleet.getOwnerPlayerId());
            if (target == null || owner == null) {
                continue;
            }
            if (Boolean.TRUE.equals(monster(context, fleet, target, owner))) {
                continue;
            }
            if (Boolean.TRUE.equals(mines(context, fleet, target, owner))) {
                continue;
            }
            land(context, fleet, target, owner);
        }
    }


    /**
     * Космическое чудище встречает флот — п. 11.1.
     * <p>
     * В MOO II чудище сидит в системе и нападает на всякий вошедший флот. Оно никуда не
     * летит и ничего не захватывает: это препятствие, а не империя. Система с чудищем
     * закрыта для расселения, пока оно живо, — оттого хорошая планета за спиной у дракона
     * и стоит дороже такой же в чистом поле.
     * <p>
     * <b>Здесь — только империи ИИ.</b> У человека чудище выходит на тактическое поле
     * ({@code MonsterBattleService}, фаза прибытия зовёт его сразу после этой выборки):
     * встреча с драконом, ради которой игрок и копил флот, не должна проходить мимо него
     * строкой отчёта. Соседям поле боя ни к чему — они решают свои бои быстрым счётом, и
     * с чудищем тоже, — а сила у обоих путей одна и та же, так что исход не зависит от
     * того, кто пришёл.
     * <p>
     * <b>Быстрый счёт.</b> Чья сила больше, тот и победил, а
     * проигравший платит по силе противника. Флот сильнее сторожа — сторож гибнет, а флот
     * теряет корабли соразмерно его силе; слабее — флот гибнет целиком, но рану оставляет,
     * и следующий придёт на ослабевшего. Так система с драконом однажды всё же
     * открывается, а с амёбой — с первого раза.
     * <p>
     * Потери считаются <b>слабейшими кораблями</b>: чудище рвёт то, что подвернулось, а
     * различать корпуса в бою без поля нечем. Это та же реконструкция, что у мин.
     *
     * @return {@code true} — флот погиб целиком и садиться больше нечему
     */
    private Boolean monster(TurnContext context, FleetEntity fleet, StarSystemEntity target,
                            PlayerEntity owner) {
        if (!Boolean.TRUE.equals(target.hasLiveMonster())) {
            return Boolean.FALSE;
        }
        if (owner.getPlayerType() == PlayerType.HUMAN) {
            // Флот человека садится в системе и дерётся сам: бой заводит фаза прибытия
            // сразу после этого — п. 8, п. 11.1.
            return Boolean.FALSE;
        }
        SpaceMonster kind = target.getMonster();
        int guard = target.getMonsterStrength();
        int power = power(fleet, owner);

        if (power > guard) {
            // Сторож убит. Флот платит по его силе: чем крупнее чудище, тем дороже победа.
            int lost = losses(fleet, owner, guard, power);
            target.setMonster(null);
            target.setMonsterStrength(null);
            context.report().add(owner.getId(), "MONSTER",
                    new MessageKey("turn.monster.killed", kind, target.getName(), lost),
                    target.getId(), null);
            activity.record(context.game().getId(), owner.getId(), EmpireActivityService.MONSTER);
            log.info("Флот империи {} убил чудище {} в системе {}, потеряв {} кораблей",
                    owner.getName(), kind.getLabel(), target.getName(), lost);
            return fleet.getShips() <= 0;
        }

        // Флот слабее: он гибнет, но сторож слабеет на его силу.
        int left = Math.max(1, guard - power);
        target.setMonsterStrength(left);
        int lost = losses(fleet, owner, power, power);
        context.report().add(owner.getId(), "MONSTER",
                new MessageKey("turn.monster.repelled", kind, target.getName(), lost),
                target.getId(), null);
        log.info("Чудище {} в системе {} отбило флот империи {} (осталось силы {})",
                kind.getLabel(), target.getName(), owner.getName(), left);
        return fleet.getShips() <= 0;
    }

    /** Сколько кораблей флот теряет, заплатив долей своей силы: слабейшими и по одному. */
    private Integer losses(FleetEntity fleet, PlayerEntity owner, int paid, int power) {
        if (power <= 0 || fleet.getShips() <= 0) {
            return 0;
        }
        int share = Math.min(fleet.getShips(), Math.max(1, fleet.getShips() * paid / power));
        int lost = 0;
        for (int i = 0; i < share; i++) {
            lost += loseWeakest(fleet, owner);
        }
        return lost;
    }

    /**
     * Сеть Артемиды — п. 11: минное поле вокруг всей системы бьёт флот НА ПОДЛЁТЕ.
     * <p>
     * «A gigantic spherical network of high-yield mines that surrounds an entire planetary
     * system. When a ship collides with a mine it suffers damage to its armor and internal
     * systems» — и в оригинале флот проходит через неё ДО боя, а не в бою.
     * <p>
     * <b>Реконструкция — мера урона.</b> Урона по корпусу вне боя игра не считает вовсе:
     * флот хранится числом кораблей по проектам, и «поцарапанного» корабля в нём нет.
     * Поэтому мины выражены гибелью ОДНОГО корабля за приход — слабейшего из тех, что
     * пришли. Так сохранено главное: минное поле — это потеря, а не задержка, и большой
     * флот проходит его легче, чем горстка фрегатов.
     * <p>
     * Своих мины не трогают, союзников тоже: сеть ставят вокруг своей системы, и правило
     * тут то же, что у нападения, — договор держит руки связанными.
     *
     * @return {@code true} — флот погиб целиком и садиться больше нечему
     */
    private Boolean mines(TurnContext context, FleetEntity fleet, StarSystemEntity target,
                          PlayerEntity owner) {
        UUID minerId = context.colonies().stream()
                .filter(colony -> target.getId().equals(context.systemOf(colony)))
                .filter(colony -> colony.getOwnerPlayerId() != null
                        && !colony.getOwnerPlayerId().equals(owner.getId()))
                .filter(colony -> context.colonyContext().buildings(colony).stream()
                        .anyMatch(building -> OrbitalDefenceRules.ARTEMIS_NET.equals(building.code())))
                .map(PlanetEntity::getOwnerPlayerId)
                .findFirst()
                .orElse(null);
        if (minerId == null || Boolean.TRUE.equals(peaceBound(owner.getId(), minerId))) {
            return Boolean.FALSE;
        }

        Integer lost = loseWeakest(fleet, owner);
        if (lost <= 0) {
            return Boolean.FALSE;
        }
        context.report().add(owner.getId(), "MINES",
                new MessageKey("turn.mines.hit", target.getName(), lost), target.getId(), null);
        context.report().add(minerId, "MINES",
                new MessageKey("turn.mines.caught", target.getName(), lost), target.getId(), null);
        log.info("Минное поле системы {} уничтожило {} корабль флота империи {}",
                target.getName(), lost, owner.getName());
        return fleet.getShips() <= 0;
    }

    /**
     * Флот пришёл: встаёт в строй со своим же флотом, если тот в системе уже был, и
     * разведывает систему — п. 15. О прибытии рассказывается в итогах хода: приказ был
     * отдан несколько ходов назад, и без строчки в отчёте игрок заметил бы его исполнение
     * разве что случайно, по значку на карте.
     */
    private void land(TurnContext context, FleetEntity fleet, StarSystemEntity target, PlayerEntity owner) {
        Integer ships = fleet.getShips();
        FleetEntity standing = fleetRepository
                .findByOwnerPlayerIdAndStarSystemIdAndTargetSystemIdIsNull(owner.getId(), target.getId())
                .orElse(null);
        if (standing == null) {
            fleet.land(target.getId());
            fleetRepository.saveAndFlush(fleet);
        } else {
            standing.setShips(standing.getShips() + ships);
            fleetRepository.saveAndFlush(standing);
            merge(fleet, standing);
            fleetRepository.delete(fleet);
        }

        explore(context.game(), owner, target);
        context.report().add(owner.getId(), "FLEET_ARRIVED",
                new MessageKey("turn.fleet.arrived", ships, target.getName()),
                target.getId(), null);
        log.info("Флот игрока {} ({} кораблей) прибыл в систему {}",
                owner.getName(), ships, target.getName());
    }

    /**
     * Приход в неизвестную систему — единственный способ её разведать (п. 15). Знакомство
     * с хозяевами колоний случается там же.
     */
    private void explore(GameEntity game, PlayerEntity player, StarSystemEntity target) {
        if (Boolean.TRUE.equals(explorationService.isExplored(player, target.getId()))) {
            return;
        }
        explorationService.exploreBy(player, target, game.getTurn());
        playerEvents.record(game.getId(), player.getId(), game.getTurn(), "EXPLORATION",
                new MessageKey("turn.fleet.explored", target.getName()), target.getId(), null);
        // Наследие ушедшей цивилизации достаётся тому, кто ДОЛЕТЕЛ, а не тому, кто
        // поселился (п. 4.1), поэтому награда стоит здесь — в единственном месте, где
        // система становится разведанной.
        findReward.claim(game, player, target);
    }

    /** Переносит состав улетающего флота в тот, к которому он присоединился. */
    private void merge(FleetEntity from, FleetEntity into) {
        for (FleetShipEntity row : fleetShipRepository.findAllByFleetIdOrderByIdAsc(from.getId())) {
            FleetShipEntity target = fleetShipRepository
                    .findByFleetIdAndDesignId(into.getId(), row.getDesignId())
                    .orElse(null);
            if (target == null) {
                row.setFleetId(into.getId());
                fleetShipRepository.save(row);
                continue;
            }
            target.setShips(target.getShips() + row.getShips());
            fleetShipRepository.save(target);
            fleetShipRepository.delete(row);
        }
    }

    /**
     * Инициатива флота — п. 8: кто при встрече решает первым.
     * <p>
     * Сила флота, помноженная на скорость его кораблей. Реконструкция: очередь хода в бою
     * MOO II задаёт тактическая сцена, которой в игре ещё нет, а решать очередь встречи
     * чем-то надо. Быстрый флот навязывает бой, сильный его выдерживает — обе стороны
     * дела попадают в одно число.
     */
    public Integer initiative(Integer power, Integer speed) {
        return power * Math.max(1, speed);
    }

    /**
     * Сила флота игрока — п. 8: сумма боевых сил его кораблей. Спрашивают встречи флотов
     * и бой.
     */
    @Transactional(readOnly = true)
    public Integer power(FleetEntity fleet, PlayerEntity owner) {
        return combat(owner).power(fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId()), fleet.getShips());
    }

    /**
     * Совокупная боевая сила империи — п. 8: сумма сил всех её флотов.
     * <p>
     * По ней ИИ решает, нападать ли (п. 15): в MOO II агрессивный правитель «нападёт, как
     * только окажется в выгодном положении», а выгоду он меряет флотом. Летящие флоты
     * считаются наравне со стоящими — они тоже сила империи, просто в пути.
     * <p>
     * Считается одним проходом: характеристики проектов берутся раз на игрока, а состав
     * всех его флотов — одной выборкой.
     */
    @Transactional(readOnly = true)
    public Integer empirePower(PlayerEntity player) {
        List<FleetEntity> fleets = fleetRepository.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .toList();
        if (fleets.isEmpty()) {
            return 0;
        }
        Combat combat = combat(player);
        Map<UUID, List<FleetShipEntity>> composition = composition(fleets);
        return fleets.stream()
                .mapToInt(fleet -> combat.power(
                        composition.getOrDefault(fleet.getId(), List.of()), fleet.getShips()))
                .sum();
    }

    /**
     * Сила флотов всех переданных империй — одной выборкой на партию (п. 11.1).
     * <p>
     * Конец хода спрашивает её у каждого игрока: и дипломатия ИИ, и летопись сравнивают
     * империи между собой. Запросом на игрока это восемь походов в базу за ход, и каждый
     * из них сбрасывал туда всё, что ход успел изменить. Флоты читаются разом, а
     * характеристики проектов поднимаются только у тех, у кого флот есть.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> empirePowerByPlayer(UUID gameId, List<PlayerEntity> players) {
        Map<UUID, List<FleetEntity>> byOwner = fleetRepository.findAllByGameId(gameId).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .collect(Collectors.groupingBy(FleetEntity::getOwnerPlayerId));

        Map<UUID, Integer> power = new HashMap<>();
        for (PlayerEntity player : players) {
            List<FleetEntity> fleets = byOwner.getOrDefault(player.getId(), List.of());
            if (fleets.isEmpty()) {
                power.put(player.getId(), 0);
                continue;
            }
            Combat combat = combat(player);
            Map<UUID, List<FleetShipEntity>> composition = composition(fleets);
            power.put(player.getId(), fleets.stream()
                    .mapToInt(fleet -> combat.power(
                            composition.getOrDefault(fleet.getId(), List.of()), fleet.getShips()))
                    .sum());
        }
        return power;
    }

    /**
     * Инициатива флота одним заходом — п. 8: сила и скорость сразу.
     * <p>
     * Заведено ради конца хода. Сила и скорость по отдельности строят боевой контекст
     * игрока КАЖДАЯ — а он это выборка всех проектов империи вместе с их составом и
     * прибавками офицеров, — и каждая же заново вычитывает состав флота. На инициативу,
     * которая спрашивается у всякого встреченного флота, уходило вдвое больше работы, чем
     * нужно. Выборка стеков на круге 7 показала `ShipDesignService` почти третью всего
     * хода, и это одно из мест, откуда он туда попадал.
     */
    @Transactional(readOnly = true)
    public Integer initiativeOf(FleetEntity fleet, PlayerEntity owner) {
        Combat combat = combat(owner);
        List<FleetShipEntity> rows = fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId());
        return initiative(combat.power(rows, fleet.getShips()), combat.speed(rows));
    }

    /** Скорость флота игрока — по самому медленному кораблю. */
    @Transactional(readOnly = true)
    public Integer speed(FleetEntity fleet, PlayerEntity owner) {
        return combat(owner).speed(fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId()));
    }

    /**
     * Сколько командных очков занимают флоты каждой империи — п. 8.
     * <p>
     * Одной выборкой на партию: это считается в конце хода, а выборка на игрока там
     * стоит целого хода. Летящие флоты считаются наравне со стоящими — корабль занимает
     * место в строю и в пути.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> commandUsedByPlayer(UUID gameId) {
        List<FleetEntity> fleets = fleetRepository.findAllByGameId(gameId).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .toList();
        if (fleets.isEmpty()) {
            return Map.of();
        }

        Map<UUID, ShipDesignEntity> designs = shipDesignService.designsOfGame(gameId);
        Map<UUID, List<FleetShipEntity>> composition = composition(fleets);

        Map<UUID, Integer> used = new HashMap<>();
        for (FleetEntity fleet : fleets) {
            int points = composition.getOrDefault(fleet.getId(), List.of()).stream()
                    .mapToInt(row -> {
                        ShipDesignEntity design = designs.get(row.getDesignId());
                        return design == null
                                ? 0
                                : shipCatalog.hull(design.getHullCode()).command() * row.getShips();
                    })
                    .sum();
            used.merge(fleet.getOwnerPlayerId(), points, Integer::sum);
        }
        return Map.copyOf(used);
    }

    /** Расовая прибавка к атаке кораблей — п. 7. */
    public Integer shipPower(PlayerEntity player) {
        return raceService.effects(player).shipAttackPercent();
    }

    /** Названия систем партии — для флотов и встреч. */
    public Map<UUID, String> systemNames(UUID gameId) {
        return starSystemRepository.findAllByGameIdWithPlanets(gameId).stream()
                .sorted(GameOrder.SYSTEMS)
                .collect(Collectors.toMap(StarSystemEntity::getId, StarSystemEntity::getName,
                        (first, second) -> first));
    }

    /**
     * Флоты партии по системам — нужны фазе встреч.
     * <p>
     * Летящие в выборку не попадают: флот в пути не стоит в системе вылета и встретиться
     * там ни с кем не может — п. 8.
     */
    public Map<UUID, List<FleetEntity>> bySystem(UUID gameId) {
        return fleetRepository.findAllByGameId(gameId).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .collect(Collectors.groupingBy(FleetEntity::getStarSystemId));
    }

    public FleetEntity require(UUID fleetId) {
        return fleetRepository.findById(fleetId)
                .orElseThrow(() -> new NotFoundException("fleet.notFound", fleetId));
    }

    /** Стоящий флот игрока в системе; пусто — флота там нет. Летящий не в счёт — п. 8. */
    public FleetEntity fleetAt(UUID playerId, UUID starSystemId) {
        return fleetRepository
                .findByOwnerPlayerIdAndStarSystemIdAndTargetSystemIdIsNull(playerId, starSystemId)
                .orElse(null);
    }

    /**
     * Куда флоты империи вообще могут долететь — п. 8.
     * <p>
     * Дальность даёт топливо, но меряется она не от цели до цели, а <b>от опорных точек
     * империи</b>: в MOO II флот летает внутри облака вокруг своих миров, и расширяют это
     * облако колонии, а не двигатели. Родная система считается всегда — с неё империя и
     * начинает, даже потеряв все колонии.
     * <p>
     * <b>Опоры — только свои миры.</b> Раньше опорой считалась и система, где просто
     * стоит свой флот: колонизировать чужие звёзды игра не умела, и по правилам оригинала
     * империя осталась бы запертой в пузыре вокруг родной звезды навсегда. Теперь есть
     * колониальный корабль (п. 4.1), пузырь растёт вместе с колониями, и стоянки флотов
     * из опор убраны — как в MOO II, где дальность даёт колония или застава, а не сам
     * факт, что корабль куда-то долетел.
     * <p>
     * Список уходит клиенту целиком: по нему на карте рисуется зелёная линия к достижимой
     * звезде и красная — к недостижимой, ещё до того, как приказ отдан.
     */
    @Transactional(readOnly = true)
    public Set<UUID> reachableSystems(GameEntity game, PlayerEntity player) {
        return reachableSystems(game, player, technologies(player));
    }

    private Set<UUID> reachableSystems(GameEntity game, PlayerEntity player, Set<String> technologies) {
        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(game.getId()).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        double range = flightRules.rangeParsecs(technologies);
        double squared = range * range;

        List<StarSystemEntity> bases = systems.stream()
                .filter(system -> owns(system, player.getId())
                        || system.getId().equals(player.getHomeSystemId()))
                .toList();
        if (bases.isEmpty()) {
            return Set.of();
        }

        return systems.stream()
                .filter(system -> bases.stream()
                        .anyMatch(base -> distanceSquared(base, system) <= squared))
                .map(StarSystemEntity::getId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Дальность империи в парсеках — её показывает экран флота. */
    @Transactional(readOnly = true)
    public Integer rangeParsecs(PlayerEntity player) {
        return flightRules.rangeParsecs(technologies(player));
    }

    /** Умеет ли империя менять курс флота в полёте — п. 8. */
    @Transactional(readOnly = true)
    public Boolean canRedirect(PlayerEntity player) {
        return flightRules.canRedirect(technologies(player));
    }

    /**
     * Проверка дальности перед отправкой: то же правило, что показывает линия на карте.
     * Клиент её рисует, но решает сервер — приказ мог прийти и мимо интерфейса.
     */
    private void requireInRange(GameEntity game, PlayerEntity player, StarSystemEntity target,
                                Set<String> technologies) {
        if (reachableSystems(game, player, technologies).contains(target.getId())) {
            return;
        }
        throw new ConflictException("fleet.outOfRange", target.getName(), flightRules.rangeParsecs(technologies));
    }

    /** Коды изученных игроком технологий: от них зависят и дальность, и смена курса. */
    private Set<String> technologies(PlayerEntity player) {
        return playerTechnologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
    }

    /** Есть ли у игрока колония в этой системе: из таких и меряется дальность полёта. */
    private Boolean owns(StarSystemEntity system, UUID playerId) {
        return system.getPlanets().stream()
                .anyMatch(planet -> playerId.equals(planet.getOwnerPlayerId()));
    }

    /** Квадрат расстояния в парсеках: корень для сравнения с дальностью не нужен. */
    private double distanceSquared(StarSystemEntity first, StarSystemEntity second) {
        double dx = first.getXParsec() - second.getXParsec();
        double dy = first.getYParsec() - second.getYParsec();
        return dx * dx + dy * dy;
    }

    /**
     * Потери боя: корабли списываются, пустой флот исчезает — п. 8.
     * <p>
     * Списываются слабейшие корабли первыми. Реконструкция: настоящего боя MOO II в игре
     * ещё нет (см. {@code stub/SpaceBattleService}), а какие именно корабли погибли,
     * теперь спрашивается — состав флота стал известен. Слабейший гибнет первым потому,
     * что он и держит меньше всех; когда появится тактическая сцена, потери придут из неё.
     */
    @Transactional
    public void applyLosses(FleetEntity fleet, Integer losses, PlayerEntity owner) {
        if (fleet == null || losses <= 0) {
            return;
        }
        fleet.setShips(Math.max(0, fleet.getShips() - losses));
        if (fleet.getShips() == 0) {
            fleetShipRepository.deleteAllByFleetId(fleet.getId());
            fleetRepository.delete(fleet);
            return;
        }
        fleetRepository.save(fleet);

        // Владельца может уже не быть в партии — тогда все корабли равны, и порядок
        // списания не важен.
        Combat combat = owner == null
                ? new Combat(Map.of(), Map.of(), Map.of(), shipCatalog, shipDesignRules, RaceEffects.NONE)
                : combat(owner);
        List<FleetShipEntity> rows = new ArrayList<>(fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId()));
        rows.sort(Comparator.comparing(row -> combat.shipPower(row.getDesignId())));

        int left = losses;
        for (FleetShipEntity row : rows) {
            if (left <= 0) {
                break;
            }
            int killed = Math.min(left, row.getShips());
            left -= killed;
            row.setShips(row.getShips() - killed);
            if (row.getShips() == 0) {
                fleetShipRepository.delete(row);
            } else {
                fleetShipRepository.save(row);
            }
        }
    }

    /**
     * Потери тактического боя — п. 8: погибшие корабли списываются поимённо, по проектам.
     * <p>
     * В отличие от {@link #applyLosses}, гадать, кто именно погиб, не нужно: бой считал
     * корабли по одному и знает, какого проекта каждый. Пустой флот исчезает — уводить
     * с поля некого.
     */
    @Transactional
    public void applyBattleLosses(FleetEntity fleet, Map<UUID, Integer> lostByDesign) {
        if (fleet == null || lostByDesign.isEmpty()) {
            return;
        }

        int lost = 0;
        for (FleetShipEntity row : fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId())) {
            Integer killed = lostByDesign.get(row.getDesignId());
            if (killed == null || killed <= 0) {
                continue;
            }
            int actual = Math.min(killed, row.getShips());
            lost += actual;
            row.setShips(row.getShips() - actual);
            if (row.getShips() == 0) {
                fleetShipRepository.delete(row);
            } else {
                fleetShipRepository.save(row);
            }
        }

        fleet.setShips(Math.max(0, fleet.getShips() - lost));
        if (fleet.getShips() == 0) {
            fleetShipRepository.deleteAllByFleetId(fleet.getId());
            fleetRepository.delete(fleet);
            return;
        }
        fleetRepository.save(fleet);
    }

    /**
     * Уносит из флота слабейший корабль — п. 11: так считается потеря на минном поле.
     * <p>
     * Слабейший, а не первый попавшийся: мины бьют по броне и внутренностям, и первым
     * разваливается тот, у кого их меньше. Выбор при этом определён до последнего числа —
     * партия обязана повторяться (этап 0 балансировки).
     *
     * @return сколько кораблей потеряно: ноль — флот пуст или во флоте нечего терять
     */
    private Integer loseWeakest(FleetEntity fleet, PlayerEntity owner) {
        Map<UUID, ShipStats> stats = shipDesignService.statsOf(owner);
        FleetShipEntity weakest = fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId())
                .stream()
                .filter(row -> row.getShips() > 0)
                .min(Comparator
                        .comparing((FleetShipEntity row) -> {
                            ShipStats value = stats.get(row.getDesignId());
                            return value == null ? 0 : value.structure() + value.armour();
                        })
                        .thenComparing(FleetShipEntity::getId))
                .orElse(null);
        if (weakest == null) {
            return 0;
        }
        applyBattleLosses(fleet, Map.of(weakest.getDesignId(), 1));
        return 1;
    }

    /**
     * Держит ли договор руки связанными — п. 15: та же проверка, что у нападения.
     * <p>
     * Минное поле своих и союзников не трогает: сеть ставят вокруг СВОЕЙ системы, и рвать
     * ею договор было бы нападением без объявления.
     */
    private Boolean peaceBound(UUID visitor, UUID owner) {
        try {
            diplomacyService.requireAttackAllowed(owner, visitor);
            return Boolean.FALSE;
        } catch (ConflictException forbidden) {
            return Boolean.TRUE;
        }
    }

    /** Игроки партии по идентификатору — общая мелочь для встреч и отчётов. */
    public Map<UUID, PlayerEntity> playersById(List<PlayerEntity> players) {
        return players.stream().collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
    }
}
