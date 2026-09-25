package com.moo3.server.service;

import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.GroundCombatTech;
import com.moo3.server.dto.InvadeRequest;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Наземный бой — п. 12: захват чужой колонии десантом.
 * <p>
 * <b>Правило боя.</b> Сила стороны это её десант со всеми надбавками:
 * {@code сила = бойцы * 10 * (100 + процентов) / 100}, где проценты складываются — раса,
 * офицер (п. 6), изученные винтовки, броня, снаряжение и батлоиды
 * ({@link com.moo3.server.domain.enums.GroundCombatTech}), а у обороняющегося ещё и
 * казармы колонии и домашняя прибавка подземной расы. Побеждает большая сила,
 * проигравший теряет весь десант, победитель — долю, равную отношению сил. Так бой
 * решается за один расчёт и остаётся понятным: вдвое сильнейший теряет половину того,
 * что привёл.
 * <p>
 * <b>Слагаемые — из формулы оригинала</b>, а не подобраны: MOO II считает пехотинца как
 * {@code 0,5 + винтовка + корабельная броня + снаряжение + раса}, и наша сотня процентов —
 * это его «база 0,5». Числа и их перевод в проценты разобраны в javadoc
 * {@link com.moo3.server.domain.enums.GroundCombatTech}; там же названо и то немногое, что
 * пришлось реконструировать.
 * <p>
 * <b>Реконструкция — сам розыгрыш.</b> В оригинале каждая единица кидает случайный
 * множитель от 1 до 1,5, и стороны сравнивают суммы; здесь силы сравниваются напрямую, без
 * жребия. Причина не в лени: партия балансировки должна повторяться до последнего числа
 * (см. «Случайность выводится из зерна партии» в CLAUDE.md), а лишний жребий в редком
 * событии — это шум там, где и так мало наблюдений. Потери считаются отношением сил, чего
 * оригинал не публикует вовсе.
 * <p>
 * <b>Две дороги у десанта.</b> С соседней планеты своей системы его отправляет колония —
 * {@link #invade}: лететь там некуда, и десантом становятся её же жители. В чужую
 * систему десант привозят транспорты — {@code ExpeditionService}: корабль везёт четырёх
 * бойцов, как в MOO II, и обратно уже не возвращается. Бой в обоих случаях один и тот
 * же — {@link #land}.
 */
@Service
public class GroundCombatService {

    private static final Logger log = LoggerFactory.getLogger(GroundCombatService.class);

    /** Сила одного жителя в десанте до расовых поправок. */
    private static final int STRENGTH_PER_COLONIST = 10;

    private final PlanetRepository planetRepository;
    private final PlayerRepository playerRepository;
    private final RaceService raceService;
    private final DiplomacyService diplomacyService;
    private final PlayerEventService playerEvents;
    private final ColonyService colonyService;
    private final LeaderBonusService leaderBonuses;
    private final AssimilationRules assimilationRules;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final EmpireActivityService activity;
    /** Оборона колонии — п. 11: планетарный барьер не пускает десант, пока цел. */
    private final OrbitalDefenceRules orbitalDefenceRules;

    public GroundCombatService(PlanetRepository planetRepository,
                               PlayerRepository playerRepository,
                               RaceService raceService,
                               DiplomacyService diplomacyService,
                               PlayerEventService playerEvents,
                               ColonyService colonyService,
                               LeaderBonusService leaderBonuses,
                               AssimilationRules assimilationRules,
                               PlayerTechnologyRepository playerTechnologyRepository,
                               EmpireActivityService activity,
                               OrbitalDefenceRules orbitalDefenceRules) {
        this.orbitalDefenceRules = orbitalDefenceRules;
        this.assimilationRules = assimilationRules;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.activity = activity;
        this.leaderBonuses = leaderBonuses;
        this.planetRepository = planetRepository;
        this.playerRepository = playerRepository;
        this.raceService = raceService;
        this.diplomacyService = diplomacyService;
        this.playerEvents = playerEvents;
        this.colonyService = colonyService;
    }

    /**
     * Сила десанта: жители, умноженные на расовую подготовку — п. 12.
     * <p>
     * Одинаково считается и для нападения, и для обороны: в MOO II подготовка расы
     * работает в обе стороны.
     */
    public Integer strength(Integer colonists, RaceEffects race) {
        return strength(colonists, race, 0);
    }

    /**
     * То же, но с лидером — п. 6: «Коммандос» и «Начальник охраны» прибавляют к силе
     * десанта столько же процентов, сколько прибавляет расовая подготовка.
     * <p>
     * Складываются они с расой, а не умножаются на неё: в MOO II надбавки наземного боя
     * идут одной строкой, и раса-воин с офицером получает сумму двух надбавок, а не их
     * произведение.
     */
    public Integer strength(Integer colonists, RaceEffects race, Integer leaderPercent) {
        return strength(colonists, race, leaderPercent, Boolean.FALSE);
    }

    /**
     * То же, но со стороной боя — п. 12: у обороняющегося считается ещё и та подготовка,
     * которая работает только дома.
     * <p>
     * В MOO II подземная раса получает свою прибавку, «защищая колонию»: рыть норы
     * в чужом мире некогда, а в своём они уже вырыты.
     */
    public Integer strength(Integer colonists, RaceEffects race, Integer leaderPercent,
                            Boolean defending) {
        return strength(colonists, race, leaderPercent, defending, 0);
    }

    /**
     * То же, но с надбавкой оружия и построек — п. 12.
     * <p>
     * {@code extraPercent} — это изученные винтовки, броня, снаряжение и батлоиды
     * ({@link GroundCombatTech}), а у обороняющегося ещё и его казармы. Складывается всё
     * одной строкой с расой и офицером: в MOO II надбавки наземного боя идут слагаемыми
     * к одной базе, а не множителями друг на друга, — см. javadoc {@link GroundCombatTech}.
     */
    public Integer strength(Integer colonists, RaceEffects race, Integer leaderPercent,
                            Boolean defending, Integer extraPercent) {
        int base = Math.max(0, colonists) * STRENGTH_PER_COLONIST;
        int percent = race.groundCombatPercent() + leaderPercent + extraPercent
                + (Boolean.TRUE.equals(defending) ? race.groundDefencePercent() : 0);
        // Ниже нуля сила не опускается: отряд бывает никуда не годным, но не отрицательным.
        return Math.max(0, base + base * percent / 100);
    }

    /**
     * Считает бой между высаженным десантом и защитниками колонии.
     * <p>
     * Возвращает исход и то, сколько жителей осталось у победителя: проигравшая сторона
     * теряет всех, победитель — долю по отношению сил, но не меньше одного жителя, иначе
     * захватывать колонию было бы некому.
     */
    public Outcome resolve(Integer attackers, RaceEffects attackerRace,
                           Integer defenders, RaceEffects defenderRace) {
        return resolve(attackers, attackerRace, 0, defenders, defenderRace, 0);
    }

    /**
     * Тот же бой, но с офицерами обеих сторон — п. 6: у нападающего это лидер флота,
     * привёзшего десант (или колонии, с которой он высадился), у обороняющегося — лидер
     * системы, где стоит колония.
     */
    public Outcome resolve(Integer attackers, RaceEffects attackerRace, Integer attackerLeader,
                           Integer defenders, RaceEffects defenderRace, Integer defenderLeader) {
        return resolve(attackers, attackerRace, attackerLeader, 0,
                defenders, defenderRace, defenderLeader, 0);
    }

    /**
     * Тот же бой со всем, что игра о нём знает, — п. 12: раса, офицер, оружие и казармы.
     *
     * @param attackerExtra надбавка нападающего: его изученные винтовки, броня и техника
     * @param defenderExtra надбавка обороняющегося: то же самое плюс казармы колонии
     */
    public Outcome resolve(Integer attackers, RaceEffects attackerRace, Integer attackerLeader,
                           Integer attackerExtra,
                           Integer defenders, RaceEffects defenderRace, Integer defenderLeader,
                           Integer defenderExtra) {
        int attackPower = strength(attackers, attackerRace, attackerLeader, Boolean.FALSE, attackerExtra);
        int defencePower = strength(defenders, defenderRace, defenderLeader, Boolean.TRUE, defenderExtra);

        if (attackPower <= defencePower) {
            int survivors = defencePower == 0 ? 0
                    : (int) Math.round(defenders * (1.0 - (double) attackPower / defencePower));
            return new Outcome(Boolean.FALSE, Math.max(defenders > 0 ? 1 : 0, survivors),
                    attackPower, defencePower);
        }

        int survivors = (int) Math.round(attackers * (1.0 - (double) defencePower / attackPower));
        return new Outcome(Boolean.TRUE, Math.max(1, survivors), attackPower, defencePower);
    }

    /**
     * Высадка десанта с одной своей колонии на чужую в той же системе — п. 12.
     * <p>
     * Десант это жители: сколько отправлено, столько колония и теряет. Всех жителей
     * отправить нельзя — колония, оставшаяся без населения, перестала бы существовать.
     */
    @Transactional
    public Outcome invade(PlayerEntity player, Integer turn, UUID planetId, InvadeRequest request) {
        PlanetEntity from = planetRepository.findById(planetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", planetId));
        if (!player.getId().equals(from.getOwnerPlayerId())) {
            throw new ForbiddenException("planet.notYours", from.getName());
        }

        PlanetEntity target = planetRepository.findById(request.targetPlanetId())
                .orElseThrow(() -> new NotFoundException("planet.notFound", request.targetPlanetId()));
        if (!target.getStarSystem().getId().equals(from.getStarSystem().getId())) {
            // Дальше своей системы жителей колонии не отправить: для этого есть транспорты,
            // и высаживает их флот, а не соседняя планета — п. 8.
            throw new ConflictException("invasion.sameSystemOnly");
        }
        if (target.getOwnerPlayerId() == null || target.getPopulation() <= 0) {
            throw new ConflictException("invasion.noColony", target.getName());
        }
        if (player.getId().equals(target.getOwnerPlayerId())) {
            throw new ConflictException("colony.alreadyYours", target.getName());
        }
        // Пакт о ненападении и союз держат руки связанными — п. 15.
        diplomacyService.requireAttackAllowed(player.getId(), target.getOwnerPlayerId());

        Integer troops = request.troops();
        if (troops <= 0 || troops >= from.getPopulation()) {
            throw new ConflictException("invasion.troopsRange", (from.getPopulation() - 1));
        }

        from.setPopulation(from.getPopulation() - troops);
        planetRepository.save(from);
        // Десант идёт с соседней планеты той же системы, поэтому его ведёт лидер этой же
        // системы — тот самый, что командует и обороной, если она тоже здесь (п. 6).
        Integer attackerLeader = leaderBonuses.of(player.getId())
                .systemValue(from.getStarSystem().getId(), "COMMANDO");
        return land(player, target, troops, turn, player.getGame().getId(), attackerLeader);
    }

    /**
     * Сама высадка — п. 12: бой десанта с жителями колонии и его последствия.
     * <p>
     * Отсюда высаживаются оба десанта игры: соседский, с планеты той же системы, и
     * привезённый транспортами из другой системы (п. 8). Бой у них один и тот же — разной
     * бывает только дорога, — поэтому правило живёт в одном месте, а откуда прилетел
     * десант, здесь уже не важно.
     *
     * @param troops        сколько бойцов высадилось; откуда они взялись, решает вызывающий
     * @param attackerLeader надбавка офицера нападающего — п. 6: он приписан к флоту или
     *                      колонии, откуда пришёл десант, и знает об этом вызывающий
     */
    @Transactional
    public Outcome land(PlayerEntity player, PlanetEntity target, Integer troops,
                        Integer turn, UUID gameId, Integer attackerLeader) {
        PlayerEntity defender = playerRepository.findById(target.getOwnerPlayerId())
                .orElseThrow(() -> new NotFoundException("colony.ownerNotFound"));

        // Обороной командует лидер той системы, где стоит колония: до места он добирается
        // пять ходов, и пока не добрался — не считается (п. 6).
        Integer defenderLeader = leaderBonuses.of(defender.getId())
                .systemValue(target.getStarSystem().getId(), "COMMANDO");

        // Оружие обеих сторон и казармы колонии — п. 12. Контекст колонии собирается один
        // раз на всю высадку: он же потом скажет, сколько на планете места для победителя.
        //
        // Запрос в базу внутри посчитанного хода здесь допустим — в отличие от выборок
        // «на игрока» из фаз (см. «Грабли» в CLAUDE.md), высадка это редкое событие: две-пять
        // на партию из полутора сотен ходов, а не на каждого игрока каждый ход.
        ColonyService.ColonyContext colony = colonyService.context(target);

        // Планетарный барьер не пускает десант вовсе — п. 11, п. 12. Правило оригинала
        // прямое: «As long as the barrier shield is in place, neither ground Marines nor
        // biological weapons can enter the planet's atmosphere». Сбить его можно только в
        // бою, как всякую платформу обороны, — и десант возвращается ни с чем.
        List<String> built = colony.buildings(target).stream()
                .map(com.moo3.server.domain.Building::code)
                .toList();
        if (Boolean.TRUE.equals(orbitalDefenceRules.blocksLanding(built))) {
            throw new ConflictException("invasion.barrierShield", target.getName());
        }

        Map<UUID, Set<String>> known = technologies(player.getId(), defender.getId());
        Integer attackerExtra = GroundCombatTech.attackPercent(
                known.getOrDefault(player.getId(), Set.of()));
        Integer defenderExtra = GroundCombatTech.defencePercent(
                known.getOrDefault(defender.getId(), Set.of()))
                + barracksPercent(colony, target);

        Outcome outcome = resolve(troops, raceService.effects(player), attackerLeader, attackerExtra,
                target.getPopulation(), raceService.effects(defender), defenderLeader, defenderExtra);

        RaceEffects defenderRace = raceService.effects(defender);
        if (Boolean.TRUE.equals(outcome.captured())) {
            // Захваченная колония достаётся победителю вместе с уцелевшим десантом;
            // здания остаются на планете, как в MOO II, а стройка начинается заново.
            //
            // Десант, которому на планете негде жить, на ней и не остаётся: планета
            // держит столько жителей, сколько позволяют её размер, климат и постройки
            // (п. 4.1), и захват — не повод её переполнить. Без этой обрезки транспорты,
            // взявшие крошечный мир, оставляли на нём больше жителей, чем он вмещает.
            // Колония достаётся с людьми: уцелевшие жители переходят к победителю
            // подданными и работают по правилам своей прежней расы, пока не
            // ассимилируются (п. 7, п. 12). Феодальная империя своих не удерживает вовсе:
            // «потерянные колонии сразу же ассимилируются противником».
            Integer subjects = Boolean.TRUE.equals(defenderRace.instantAssimilation())
                    ? 0
                    : assimilationRules.subjects(target.getPopulation(),
                            outcome.defencePower(), outcome.attackPower());

            // Место на планете достаётся сперва её жителям, а уж потом десанту: подданные
            // тут живут, а победитель пришёл. Иначе на тесной колонии от прежнего
            // населения не оставалось бы никого — и ассимилировать было бы некого.
            Integer room = capacity(target, colony);
            subjects = Math.min(subjects, room);
            Integer own = Math.min(outcome.survivors(), Math.max(0, room - subjects));

            target.setOwnerPlayerId(player.getId());
            target.setPopulation(own + subjects);
            target.setAlienPopulation(subjects);
            target.setAlienOwnerPlayerId(subjects > 0 ? defender.getId() : null);
            target.setAssimilationPoints(0);
            target.setJobs(com.moo3.server.domain.PopulationJobs.NONE);
            target.setProjectCode(ColonyProject.TRADE_GOODS);
            target.setProjectPoints(0);
            target.setHomeworld(Boolean.FALSE);
        } else {
            target.setPopulation(outcome.survivors());
        }
        planetRepository.save(target);

        // Счётчики механик — этап 1 балансировки. Стоят они ЗДЕСЬ, а не у вызывающего,
        // потому что здесь и происходит событие: высадок в игре две дороги (соседская с
        // планеты своей системы и привезённая транспортами), и пока запись стояла в
        // ExpeditionService, соседская не считалась вовсе — ни высадкой, ни захватом.
        // Те же грабли, на которых счётчики молчали уже четырежды (см. CLAUDE.md).
        activity.record(gameId, player.getId(), EmpireActivityService.INVASION);
        if (Boolean.TRUE.equals(outcome.captured())) {
            activity.record(gameId, player.getId(), EmpireActivityService.CAPTURE);
            // И зеркало захвата — у того, кто колонию потерял. Без него мерило наземного
            // боя видит только нападение: оборона в нём не считается ничем.
            activity.record(gameId, defender.getId(), EmpireActivityService.COLONY_LOST);
        }

        // Хозяин колонии узнаёт о нападении в итогах хода: десант высаживают, не спросив.
        playerEvents.record(gameId, defender.getId(), turn, "INVASION",
                captureMessage(player, target, outcome),
                target.getStarSystem().getId(), target.getId());

        log.info("Игрок {} высадил {} жителей на {}: {} (силы {} против {})",
                player.getName(), troops, target.getName(),
                Boolean.TRUE.equals(outcome.captured()) ? "колония захвачена" : "десант отбит",
                outcome.attackPower(), outcome.defencePower());
        return outcome;
    }

    /**
     * Что сказать хозяину колонии — п. 3.5: ключ с подстановками, а не готовая строка.
     * <p>
     * Событие ждёт конца хода, и язык читателя в этот миг неизвестен. Оставшиеся подданные
     * — свой ключ, а не приписка к первому: приписка означала бы склейку двух предложений,
     * а в другом языке порядок частей бывает иным.
     */
    private MessageKey captureMessage(PlayerEntity player, PlanetEntity target, Outcome outcome) {
        if (!Boolean.TRUE.equals(outcome.captured())) {
            return new MessageKey("turn.invasion.repelled", player.getName(), target.getName());
        }
        return target.getAlienPopulation() > 0
                ? new MessageKey("turn.invasion.colonyLostSubjects",
                        player.getName(), target.getName(), target.getAlienPopulation())
                : new MessageKey("turn.invasion.colonyLost", player.getName(), target.getName());
    }

    /**
     * Сколько жителей держит планета со всем, что на ней построено, — п. 4.1.
     * <p>
     * Считается тем же способом, что и при подвозе жителей грузовиками: биосферы и
     * особенности расы вместимость поднимают, и захватчику она достаётся такой же, какой
     * была у прежнего хозяина.
     */
    private Integer capacity(PlanetEntity planet, ColonyService.ColonyContext context) {
        return colonyService.maxPopulation(planet, context.effects(planet), context.race(planet));
    }

    /**
     * Надбавка казарм колонии к её обороне — п. 12.
     * <p>
     * Только обороняющемуся: казармы стоят на планете и в чужой десант не садятся. Берётся
     * она из тех же построек, что и всё остальное действие колонии, — казармы описаны
     * данными в {@code resources/Buildings/buildings.json} эффектом
     * {@link BuildingEffectType#GROUND_DEFENCE_PERCENT}, и своего свода правил у них нет.
     */
    private Integer barracksPercent(ColonyService.ColonyContext context, PlanetEntity planet) {
        return context.effects(planet).amounts()
                .getOrDefault(BuildingEffectType.GROUND_DEFENCE_PERCENT, 0);
    }

    /**
     * Изученное обеими сторонами боя — одной выборкой на высадку, а не двумя.
     * <p>
     * Нападающий приносит своё оружие с собой, обороняющийся вооружён своим: в MOO II
     * винтовки и броня работают в обе стороны, и спрашиваются они у обеих империй.
     */
    private Map<UUID, Set<String>> technologies(UUID attacker, UUID defender) {
        return playerTechnologyRepository.findAllByPlayerIdIn(List.of(attacker, defender)).stream()
                .collect(Collectors.groupingBy(PlayerTechnologyEntity::getPlayerId,
                        Collectors.mapping(PlayerTechnologyEntity::getOptionCode, Collectors.toSet())));
    }

    /**
     * Исход боя — п. 12.
     *
     * @param captured  колония захвачена
     * @param survivors сколько жителей осталось у победителя
     */
    public record Outcome(Boolean captured, Integer survivors, Integer attackPower, Integer defencePower) {
    }

    /**
     * Можно ли вообще высаживаться на эту колонию — п. 11, п. 12.
     * <p>
     * Заведено для ИИ, и по той же причине, по какой заведён
     * {@code ExpeditionService.controllable}: он обязан спрашивать ЗАРАНЕЕ, а не ловить
     * отказом. Отказ прилетает изнутри посчитанного хода и роняет ход целиком — а вместе с
     * ним всю партию и весь балансовый прогон. Так и случилось на круге 7: замер оборвался
     * на 140-й партии из 500 словами «Колонию Sargas I прикрывает планетарный барьер».
     * Пока империи ИИ были мельче, до барьерных щитов они попросту не доживали, и
     * дыра эта молчала.
     * <p>
     * Правило остаётся здесь, в одном месте: у ИИ своей копии нет, он зовёт этот же
     * предикат. Постройки берутся из контекста хода — своей выборки предикат не делает.
     */
    public Boolean landable(PlanetEntity target, ColonyService.ColonyContext colonies) {
        return !orbitalDefenceRules.blocksLanding(colonies.buildings(target).stream()
                .map(com.moo3.server.domain.Building::code)
                .toList());
    }

}
