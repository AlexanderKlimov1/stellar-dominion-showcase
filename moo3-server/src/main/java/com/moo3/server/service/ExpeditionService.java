package com.moo3.server.service;

import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.FleetShipEntity;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.enums.ShipRole;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Что гражданские корабли делают, долетев, — п. 4.1 и п. 12.
 * <p>
 * До сих пор империя не выходила за пределы родной системы: колониальная база селится
 * только на соседней орбите, а десант отправляла соседняя же планета. Галактика из
 * восьми таких империй не могла кончиться ничем — каждая сидела в своём углу и лишь
 * переписывалась с остальными.
 * <p>
 * Теперь у неё три корабля MOO II. <b>Колониальный корабль</b> увозит поселенцев и
 * основывает колонию там, куда долетел; <b>корабль-застава</b> ставит заставу на любой
 * планете, даже негодной для жизни, и тем раздвигает дальность империи; <b>транспорт</b>
 * привозит четырёх бойцов к чужой колонии. Все трое одноразовые: колониальный корабль
 * разбирается на месте и становится частью новой колонии, застава остаётся заставой,
 * транспорт высаживает десант и назад не возвращается — так и в оригинале.
 * <p>
 * Проверки здесь только те, которых не может быть в другом месте: свой ли флот, стоит ли
 * он там, куда высаживаются, и есть ли в нём нужный корабль. Всё остальное — свободна ли
 * планета, можно ли нападать, чем кончился наземный бой — спрашивается у тех, чьё это
 * дело: {@link ColonyService} и {@link GroundCombatService}.
 */
@Service
public class ExpeditionService {

    /**
     * Наименьший корпус, которым телепаты подчиняют колонию, — крейсер (третий по счёту
     * в справочнике корпусов, п. 7).
     */
    /**
     * Корпус, с которого телепаты дотягиваются до чужой колонии, — крейсер (п. 7).
     * Открыт наружу потому, что это же условие проверяет ИИ, прежде чем звать подчинение:
     * решать «попробовать и поймать отказ» он не вправе — отказ прилетает изнутри
     * посчитанного хода.
     */
    public static final int MIND_CONTROL_HULL_SIZE = 3;

    /**
     * Способность лидера, которая держит мысленный щит над его колонией, — п. 6, п. 7.
     * <p>
     * Код тот же, что у прибавки к контрразведке ({@code TELEPATH} в
     * {@code leaders.json}): в оригинале это одна и та же способность одного и того же
     * лидера, и заводить вторую строку справочника ради второго её действия незачем.
     */
    private static final String MIND_SHIELD_ABILITY = "TELEPATH";

    private static final Logger log = LoggerFactory.getLogger(ExpeditionService.class);

    private final FleetService fleetService;
    private final EmpireActivityService activity;
    private final ShipDesignService shipDesignService;
    private final GroundCombatService groundCombatService;
    private final DiplomacyService diplomacyService;
    private final PopulationCalculator populationCalculator;
    private final PlanetRepository planetRepository;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final OrbitalDefenceRules orbitalDefenceRules;
    private final LeaderBonusService leaderBonuses;
    private final RaceService raceService;
    private final PlayerRepository playerRepository;
    private final PlayerEventService playerEvents;

    public ExpeditionService(FleetService fleetService,
                             ShipDesignService shipDesignService,
                             GroundCombatService groundCombatService,
                             DiplomacyService diplomacyService,
                             PopulationCalculator populationCalculator,
                             PlanetRepository planetRepository,
                             PlanetBuildingRepository planetBuildingRepository,
                             OrbitalDefenceRules orbitalDefenceRules,
                             LeaderBonusService leaderBonuses,
                             RaceService raceService,
                             PlayerRepository playerRepository,
                             PlayerEventService playerEvents,
                             EmpireActivityService activity) {
        this.planetBuildingRepository = planetBuildingRepository;
        this.orbitalDefenceRules = orbitalDefenceRules;
        this.raceService = raceService;
        this.activity = activity;
        this.playerRepository = playerRepository;
        this.playerEvents = playerEvents;
        this.leaderBonuses = leaderBonuses;
        this.fleetService = fleetService;
        this.shipDesignService = shipDesignService;
        this.groundCombatService = groundCombatService;
        this.diplomacyService = diplomacyService;
        this.populationCalculator = populationCalculator;
        this.planetRepository = planetRepository;
    }

    /**
     * Высадка колонии с корабля — п. 4.1.
     * <p>
     * Новая колония начинается с одного жителя и с товаров в стройке — с того же, с чего
     * начинается колония от колониальной базы: способ добраться разный, а колония выходит
     * одна и та же.
     */
    @Transactional
    public PlanetEntity colonize(PlayerEntity player, UUID fleetId, UUID targetPlanetId) {
        FleetEntity fleet = requireStandingFleet(player, fleetId);
        PlanetEntity target = requirePlanetHere(fleet, targetPlanetId);

        // Своя же застава колонии не мешает: в MOO II колония, поставленная на месте
        // заставы, начинается с казарм — п. 8. Чужая планета и живая колония — мешают.
        if (target.getPopulation() > 0
                || (target.getOwnerPlayerId() != null
                && !target.getOwnerPlayerId().equals(player.getId()))) {
            throw new ConflictException("planet.taken", target.getName());
        }
        if (!Boolean.TRUE.equals(target.getClimate().getColonizable())) {
            throw new ConflictException("planet.uninhabitable", target.getName(), target.getClimate());
        }
        // Система под сторожем закрыта — п. 11.1: селиться, пока чудище живо, нельзя.
        // Это и есть цена системы с чудищем: хорошая планета за спиной у дракона стоит
        // дороже такой же в чистом поле, и брать её приходится боем.
        if (target.getStarSystem() != null
                && Boolean.TRUE.equals(target.getStarSystem().hasLiveMonster())) {
            throw new ConflictException("planet.guardedSettle", target.getStarSystem().getName(), target.getStarSystem().getMonster());
        }

        FleetShipEntity row = requireRole(player, fleet, ShipRole.COLONY,
                "fleet.noColonyShip");
        Integer settlers = Math.max(ColonyProject.COLONY_SHIP_SETTLERS,
                row.getColonists() / Math.max(1, row.getShips()));

        target.setOwnerPlayerId(player.getId());
        target.setPopulation(settlers);
        target.setJobs(populationCalculator.defaultJobs(target.getClimate(), settlers));
        target.setProjectCode(ColonyProject.TRADE_GOODS);
        target.setProjectPoints(0);
        target.setColonyBaseReady(Boolean.FALSE);
        planetRepository.save(target);

        fleetService.discharge(fleet, row, 1);

        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.COLONIZED);
        log.info("Игрок {} основал колонию на {} колониальным кораблём",
                player.getName(), target.getName());
        return target;
    }

    /**
     * Застава на планете — п. 8.
     * <p>
     * Ставится на что угодно: на пояс астероидов, на газовый гигант, на ядовитый мир —
     * жить там некому, а топливо от заставы меряется так же, как от колонии. Этим империя
     * и выбирается из своего угла галактики, когда вокруг нет ни одной планеты, годной
     * под колонию.
     */
    @Transactional
    public PlanetEntity outpost(PlayerEntity player, UUID fleetId, UUID targetPlanetId) {
        FleetEntity fleet = requireStandingFleet(player, fleetId);
        PlanetEntity target = requirePlanetHere(fleet, targetPlanetId);

        if (target.getOwnerPlayerId() != null) {
            throw new ConflictException("planet.taken", target.getName());
        }
        // Застава под сторожем не ставится по той же причине, что и колония (п. 11.1).
        if (target.getStarSystem() != null
                && Boolean.TRUE.equals(target.getStarSystem().hasLiveMonster())) {
            throw new ConflictException("planet.guardedOutpost", target.getStarSystem().getName(), target.getStarSystem().getMonster());
        }

        FleetShipEntity row = requireRole(player, fleet, ShipRole.OUTPOST,
                "fleet.noOutpostShip");

        target.setOwnerPlayerId(player.getId());
        target.setPopulation(0);
        target.setJobs(PopulationJobs.NONE);
        target.setProjectCode(null);
        target.setProjectPoints(0);
        planetRepository.save(target);

        fleetService.discharge(fleet, row, 1);

        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.OUTPOST);
        log.info("Игрок {} поставил заставу на {}", player.getName(), target.getName());
        return target;
    }

    /**
     * Высадка десанта с транспортов — п. 12.
     * <p>
     * Высаживаются все транспорты флота разом: приказ «напасть» в MOO II отдаётся не
     * кораблю, а планете, и делить десант на волны там незачем — вторая волна опоздала бы
     * на ход и попала бы под уже восстановленную оборону.
     */
    @Transactional
    public GroundCombatService.Outcome invade(PlayerEntity player, UUID fleetId,
                                              UUID targetPlanetId, Integer turn, UUID gameId) {
        FleetEntity fleet = requireStandingFleet(player, fleetId);
        PlanetEntity target = requirePlanetHere(fleet, targetPlanetId);

        if (target.getOwnerPlayerId() == null || target.getPopulation() <= 0) {
            throw new ConflictException("invasion.noColony", target.getName());
        }
        if (player.getId().equals(target.getOwnerPlayerId())) {
            throw new ConflictException("colony.alreadyYours", target.getName());
        }
        // Пакт о ненападении и союз держат руки связанными — та же проверка, что у
        // соседского десанта и у боя флотов (п. 15).
        diplomacyService.requireAttackAllowed(player.getId(), target.getOwnerPlayerId());

        FleetShipEntity row = requireRole(player, fleet, ShipRole.TRANSPORT,
                "fleet.noTransports");
        Integer troops = Math.max(1, row.getColonists());
        Integer transports = row.getShips();

        fleetService.discharge(fleet, row, transports);
        // Десант ведёт офицер того флота, что его привёз: «Коммандос» и «Начальник
        // охраны» приписаны к флоту, а не к империи (п. 6).
        LeaderBonusService.Bonuses officers = leaderBonuses.of(player.getId());
        Integer attackerLeader = officers.fleetValue(fleet.getId(), "COMMANDO")
                + officers.fleetValue(fleet.getId(), "SECURITY");
        GroundCombatService.Outcome outcome =
                groundCombatService.land(player, target, troops, turn, gameId, attackerLeader);

        // Счётчики высадки и захвата пишет сам GroundCombatService.land: высадок в игре
        // две дороги, и считаться они должны одинаково — см. комментарий там.
        log.info("Игрок {} высадил десант {} транспортов ({} бойцов) на {}",
                player.getName(), transports, troops, target.getName());
        return outcome;
    }

    /**
     * Подчинение колонии телепатами — п. 7, п. 12.
     * <p>
     * Правило MOO II: «вместо бомбёжки и вторжения раса может взять всё население планеты
     * под свой контроль, если на орбите есть хотя бы один корабль класса не ниже
     * крейсера». Десанта при этом нет вовсе: ни боя, ни потерь — колония вместе с
     * жителями просто меняет хозяина, и поэтому крупный корабль здесь не оружие, а
     * усилитель: чем больше корпус, тем дальше достаёт разум команды.
     * <p>
     * <b>Условий у оригинала ТРИ, а не одно, и двух у нас не было</b> (журнал, п. 3.92).
     * Крейсер брал колонию со звёздной крепостью на орбите, и противоядия не существовало
     * вовсе, — а подчинение и без того выгоднее десанта по каждой строке: платит ничего,
     * забирает всё население сразу своей расой, повторяется тем же кораблём каждый ход.
     * Замер на пятистах ходах намерил телепатам 43,3 силы при цене 6 — шестьдесят два
     * честных очка при бюджете двадцать, то есть цену, которой нельзя выразить. Причина
     * была не в цене: снежный ком катился без единого тормоза из тех, что стоят в
     * оригинале. Добавлены оба — {@link #requireDefenceDown} и {@link #requireNoMindShield}.
     * <p>
     * Что остаётся как при захвате: занятия жителей сбрасываются, стройка начинается
     * заново, а бывший хозяин узнаёт об этом в итогах хода. Договор о ненападении держит
     * телепата так же, как десант: подчинение — то же нападение.
     */
    @Transactional
    public PlanetEntity mindControl(PlayerEntity player, UUID fleetId, UUID targetPlanetId,
                                    Integer turn, UUID gameId) {
        if (!Boolean.TRUE.equals(raceService.effects(player).telepathic())) {
            throw new ConflictException("mindControl.telepathsOnly");
        }

        FleetEntity fleet = requireStandingFleet(player, fleetId);
        PlanetEntity target = requirePlanetHere(fleet, targetPlanetId);
        if (target.getOwnerPlayerId() == null || target.getPopulation() <= 0) {
            throw new ConflictException("mindControl.noColony", target.getName());
        }
        if (player.getId().equals(target.getOwnerPlayerId())) {
            throw new ConflictException("colony.alreadyYours", target.getName());
        }
        diplomacyService.requireAttackAllowed(player.getId(), target.getOwnerPlayerId());
        requireBigShip(player, fleet);
        requireDefenceDown(target);
        requireNoMindShield(target);

        PlayerEntity victim = playerRepository.findById(target.getOwnerPlayerId())
                .orElseThrow(() -> new NotFoundException("colony.ownerNotFound"));

        target.setOwnerPlayerId(player.getId());
        target.setJobs(PopulationJobs.NONE);
        target.setProjectCode(ColonyProject.TRADE_GOODS);
        target.setProjectPoints(0);
        target.setHomeworld(Boolean.FALSE);
        planetRepository.save(target);

        playerEvents.record(gameId, victim.getId(), turn, "MIND_CONTROL",
                new MessageKey("turn.mindControl.lost", player.getName(), target.getName()),
                target.getStarSystem().getId(), target.getId());

        activity.record(gameId, player.getId(), EmpireActivityService.MIND_CONTROL);
        // И ТЕ ЖЕ ДВА СЧЁТЧИКА, ЧТО У ЗАХВАТА ДЕСАНТОМ, — этап 1 балансировки.
        // Колония перешла из рук в руки, а наземное мерило (этап 2) считает ровно это:
        // взятые минус отнятые. Пока здесь стоял один MIND_CONTROL, мерило было слепо к
        // механике, которая переводит колоний больше всех, — и слепо с ОБЕИХ сторон:
        // телепату переход не засчитывался, жертве не засчитывалась потеря. Шестой случай,
        // когда счётчик молчал не потому, что механика спала, а потому, что запись стояла
        // не там (см. CLAUDE.md). Высадкой (INVASION) подчинение при этом не считается:
        // десанта в нём нет вовсе, и мерить им высадки значило бы считать то, чего не было.
        activity.record(gameId, player.getId(), EmpireActivityService.CAPTURE);
        activity.record(gameId, victim.getId(), EmpireActivityService.COLONY_LOST);
        log.info("Игрок {} подчинил колонию {} ({} жителей) телепатией",
                player.getName(), target.getName(), target.getPopulation());
        return target;
    }

    /**
     * Можно ли подчинить эту колонию — п. 7: оборона подавлена и щита над ней нет.
     * <p>
     * Заведено для ИИ: он проверяет условия ЗАРАНЕЕ, а не ловит отказом, — отказ прилетел
     * бы изнутри посчитанного хода и уронил бы весь ход (так уже бывало). Правило при этом
     * остаётся здесь, в одном месте: у ИИ своей копии нет, он зовёт этот же предикат.
     * <p>
     * Обе проверки ходят в базу, и это допустимо ровно по той же причине, по какой ходит
     * туда наземный бой: подчинение — событие редкое (единицы за партию), а не выборка в
     * каждой фазе. Спрашивается оно лишь тогда, когда телепат с крейсером уже стоит над
     * чужой колонией, с которой воюет.
     */
    public Boolean controllable(PlanetEntity target) {
        return !defended(target) && !mindShielded(target);
    }

    /**
     * Подчинять можно только колонию, у которой не осталось обороны, — п. 7, п. 11.
     * <p>
     * Условие оригинала: подчинение идёт ВМЕСТО вторжения, а вторжению предшествует бой за
     * орбиту. Без него крейсер забирал колонию со звёздной крепостью, наземными батареями
     * и щитом — платформы даже не успевали выстрелить.
     * <p>
     * «Осталась оборона» — это ровно то же, что решает, быть ли бою за колонию
     * ({@code EncounterService}): список платформ, которые она выставит. Так у правила
     * один источник: подавить оборону значит разбить те самые платформы, и после боя
     * подчинение открывается само. Механику это не запирает — колоний без обороны в
     * партии большинство (в мирное время ИИ строит её последней), а воюющую сперва
     * придётся вскрыть.
     */
    private void requireDefenceDown(PlanetEntity target) {
        if (defended(target)) {
            throw new ConflictException("mindControl.defended", target.getName());
        }
    }

    /**
     * Лидер-телепат жертвы не даёт подчинить свою колонию — п. 6, п. 7.
     * <p>
     * Противоядие оригинала: «leaders with Telepath skills protect colonies from mind
     * control» (руководство патча 1.50). Носителей способности в справочнике четыре, и все
     * четверо колониальные — щит выходит редким и точным: он закрывает одну систему, ту,
     * где лидер служит, а не империю целиком. Без него у стороны за шесть очков не было
     * противоядия ВОВСЕ, и это половина причины, по которой она мерилась шестьюдесятью
     * двумя очками.
     */
    private void requireNoMindShield(PlanetEntity target) {
        if (mindShielded(target)) {
            throw new ConflictException("mindControl.telepathLeader", target.getName());
        }
    }

    /** Выставит ли колония хоть одну платформу обороны — п. 8, п. 11. */
    private Boolean defended(PlanetEntity target) {
        List<String> built = planetBuildingRepository.findAllByPlanetId(target.getId()).stream()
                .map(PlanetBuildingEntity::getBuildingCode)
                .toList();
        return !orbitalDefenceRules.platformsOf(built).isEmpty();
    }

    /** Служит ли в системе колонии лидер-телепат её хозяина — п. 6, п. 7. */
    private Boolean mindShielded(PlanetEntity target) {
        return leaderBonuses.guardsSystem(target.getOwnerPlayerId(),
                target.getStarSystem().getId(), MIND_SHIELD_ABILITY);
    }

    /**
     * Корабль не ниже крейсера в этом флоте — п. 7: у телепатов он и есть условие
     * подчинения. Проекты берутся все, включая вытесненные: важен корпус, а не год
     * постройки.
     */
    private void requireBigShip(PlayerEntity player, FleetEntity fleet) {
        Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(player);
        boolean big = fleetService.shipsOf(fleet.getId()).stream()
                .filter(row -> row.getShips() > 0)
                .map(row -> designs.get(row.getDesignId()))
                .filter(design -> design != null)
                .anyMatch(design -> shipDesignService.hullSize(design) >= MIND_CONTROL_HULL_SIZE);
        if (!big) {
            throw new ConflictException("mindControl.hullTooSmall");
        }
    }

    /** Свой флот, который стоит в системе: с флота в пути не высаживаются — п. 8. */
    private FleetEntity requireStandingFleet(PlayerEntity player, UUID fleetId) {
        FleetEntity fleet = fleetService.require(fleetId);
        if (!fleet.getOwnerPlayerId().equals(player.getId())) {
            throw new ConflictException("fleet.notYours");
        }
        if (Boolean.TRUE.equals(fleet.isInFlight())) {
            throw new ConflictException("fleet.inFlightLanding");
        }
        return fleet;
    }

    /** Планета той системы, где стоит флот: дотянуться до соседней звезды нельзя. */
    private PlanetEntity requirePlanetHere(FleetEntity fleet, UUID planetId) {
        PlanetEntity planet = planetRepository.findById(planetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", planetId));
        if (!planet.getStarSystem().getId().equals(fleet.getStarSystemId())) {
            throw new ConflictException("fleet.otherSystemLanding");
        }
        return planet;
    }

    /**
     * Строка состава с кораблями нужной роли. Проекты берутся все, включая вытесненные:
     * гражданский корабль постройки прошлого века возит ровно то же самое.
     */
    private FleetShipEntity requireRole(PlayerEntity player, FleetEntity fleet,
                                        ShipRole role, String absent) {
        Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(player);
        List<FleetShipEntity> rows = fleetService.shipsOf(fleet.getId());
        return rows.stream()
                .filter(row -> row.getShips() > 0)
                .filter(row -> {
                    ShipDesignEntity design = designs.get(row.getDesignId());
                    return design != null && design.getRole() == role;
                })
                .findFirst()
                .orElseThrow(() -> new ConflictException(absent));
    }
}
