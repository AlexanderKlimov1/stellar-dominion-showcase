package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.entity.SpyEntity;
import com.moo3.server.domain.enums.SpyMission;
import com.moo3.server.dto.AssignSpyRequest;
import com.moo3.server.dto.EspionageDto;
import com.moo3.server.dto.SpyDto;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.repository.SpyRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Шпионаж — п. 13.
 * <p>
 * Шпионов строят колонии (проект «Шпион», исследований не требует). Дальше агент либо
 * работает дома — и тогда прибавляет к общему запасу очков разведки империи, на которые
 * идут вылазки в чужие системы (п. 15), — либо отправляется к сопернику с заданием.
 * <p>
 * Отправленный агент копит очки каждый ход и, набрав на операцию, делает её и начинает
 * копить снова, как в MOO II: кража технологии забирает у соперника то, чего империя ещё
 * не знает, саботаж срывает стройку одной из его колоний. Удавшаяся операция — всегда
 * дипломатический скандал: доверие пострадавшего падает.
 * <p>
 * <b>Контрразведка.</b> Агент, оставленный на контрразведку, очков империи не приносит —
 * он ловит чужих: каждый ход отнимает очки у каждого вражеского агента, работающего
 * против его империи. Агент, у которого накопленное ушло в минус, пойман и исчезает, а
 * его хозяин получает дипломатический скандал: поимку помнят. Так контрразведка и
 * замедляет чужие операции, и в конце концов сводит их на нет.
 */
@Service
public class EspionageService {

    private static final Logger log = LoggerFactory.getLogger(EspionageService.class);

    /** Очков шпионажа за ход у империи без расовых поправок и шпионов. */
    private static final int BASE_POINTS_PER_TURN = 1;

    /** Сколько очков приносит шпион: дома — империи, у соперника — своей операции. */
    private static final int POINTS_PER_SPY = 1;

    /** Насколько падает доверие пострадавшей стороны после удавшейся операции. */
    private static final int INCIDENT_TRUST_PENALTY = 15;

    /**
     * Насколько падает доверие к хозяину пойманного агента.
     * <p>
     * Меньше, чем за удавшуюся операцию: пойманный шпион — обида, но не ущерб.
     */
    private static final int CAUGHT_TRUST_PENALTY = 10;

    private final Messages messages;
    private final SpyRepository spyRepository;
    private final EmpireActivityService activity;
    private final PlayerRepository playerRepository;
    private final PlayerTechnologyRepository technologyRepository;
    private final PlanetRepository planetRepository;
    private final RaceService raceService;
    private final DiplomacyService diplomacyService;
    private final LeaderBonusService leaderBonuses;

    public EspionageService(Messages messages,
                            SpyRepository spyRepository,
                            PlayerRepository playerRepository,
                            PlayerTechnologyRepository technologyRepository,
                            PlanetRepository planetRepository,
                            RaceService raceService,
                            DiplomacyService diplomacyService,
                            LeaderBonusService leaderBonuses,
                            EmpireActivityService activity) {
        this.messages = messages;
        this.leaderBonuses = leaderBonuses;
        this.activity = activity;
        this.spyRepository = spyRepository;
        this.playerRepository = playerRepository;
        this.technologyRepository = technologyRepository;
        this.planetRepository = planetRepository;
        this.raceService = raceService;
        this.diplomacyService = diplomacyService;
    }

    /**
     * Сколько очков разведки империя набирает за ход — п. 13.
     * <p>
     * Считаются только агенты дома: отправленный к сопернику работает на свою операцию,
     * а поставленный на контрразведку — ловит чужих. Ниже нуля не опускается: плохие
     * шпионы просто не приносят ничего, а не отнимают уже накопленное.
     * <p>
     * Лидер-шпион («Мастер шпионажа») добавляет проценты ко всему набранному, где бы он
     * ни служил, — п. 6: в MOO II его надбавка идёт всей империи, а не одной колонии.
     */
    public Integer pointsPerTurn(RaceEffects race, Integer spiesAtHome, Integer leaderPercent) {
        int base = Math.max(0, BASE_POINTS_PER_TURN + race.espionagePoints()
                + POINTS_PER_SPY * spiesAtHome);
        return base * (100 + leaderPercent) / 100;
    }

    /**
     * Сила контрразведки империи — п. 13: сколько очков она отнимает у каждого чужого
     * агента за ход.
     * <p>
     * Каждый агент на контрразведке отнимает столько же, сколько чужой агент набирает,
     * плюс расовую подготовку: раса великих шпионов и ловит лучше. Поэтому один
     * контрразведчик держит одного чужого агента на месте, а двое — ловят его.
     * <p>
     * Лидеры-контрразведчики («Убийца», «Телепат») добавляют проценты ко всей обороне
     * империи — п. 6. Пустой конторе они не помогают: процент от нуля — ноль, и в MOO II
     * защищаться без агентов офицер тоже не может.
     */
    public Integer defense(PlayerEntity player, List<SpyEntity> ownSpies, Integer leaderPercent) {
        long counters = ownSpies.stream().filter(spy -> spy.getMission() == SpyMission.COUNTER).count();
        RaceEffects race = raceService.effects(player);
        // Правительство добавляет только к обороне — п. 14: диктатура и объединение
        // крепче держат своё, демократия слабее, но воровать чужое это никому не помогает.
        // Ниже нуля контрразведка не опускается: слабый строй просто не мешает шпионам.
        int perCounter = POINTS_PER_SPY + Math.max(0, race.espionagePoints()) + race.espionageDefensePoints();
        return (int) counters * Math.max(0, perCounter) * (100 + leaderPercent) / 100;
    }

    /** Состояние разведки игрока для клиента — п. 13. */
    public EspionageDto state(PlayerEntity player) {
        RaceEffects race = raceService.effects(player);
        List<SpyEntity> spies = spyRepository.findAllByOwnerPlayerId(player.getId());
        Map<UUID, PlayerEntity> targets = targetsOf(spies);
        LeaderBonusService.Bonuses bonuses = leaderBonuses.of(player.getId());

        return new EspionageDto(
                player.getEspionagePoints(),
                pointsPerTurn(race, atHome(spies).size(), bonuses.empireValue("SPY_MASTER")),
                race.espionagePoints(),
                spies.size(),
                defense(player, spies, espionageDefence(bonuses)),
                spies.stream()
                        .sorted(Comparator.comparing(SpyEntity::getCreatedTurn))
                        .map(spy -> toDto(spy, targets))
                        .toList());
    }

    /**
     * Отправляет шпиона к сопернику или возвращает его домой — п. 13.
     * <p>
     * Отправить можно только к знакомой империи: о незнакомой известно лишь то, что она
     * существует, и адреса у агента нет.
     */
    @Transactional
    public SpyDto assign(PlayerEntity player, AssignSpyRequest request, Integer turn) {
        SpyEntity spy = spyRepository.findById(request.spyId())
                .orElseThrow(() -> new NotFoundException("spy.notFound", request.spyId()));
        if (!player.getId().equals(spy.getOwnerPlayerId())) {
            throw new ForbiddenException("spy.notYours");
        }

        // Дома и на контрразведке агент работает у себя: цель ему не нужна.
        if (request.mission() == SpyMission.HOME || request.mission() == SpyMission.COUNTER) {
            spy.setTargetPlayerId(null);
            spy.setMission(request.mission());
            spy.setPoints(0);
            spyRepository.save(spy);
            log.info("Игрок {} поставил шпиона на «{}»", player.getName(), request.mission().getLabel());
            return toDto(spy, Map.of());
        }

        if (request.targetPlayerId() == null) {
            throw new ConflictException("spy.targetRequired");
        }
        if (request.targetPlayerId().equals(player.getId())) {
            throw new ConflictException("spy.selfTarget");
        }
        // Знакомство обязательно: о незнакомой империи не известно даже, где её искать.
        diplomacyService.requireKnown(player.getId(), request.targetPlayerId());

        PlayerEntity target = playerRepository.findById(request.targetPlayerId())
                .orElseThrow(() -> new NotFoundException("empire.notFound", request.targetPlayerId()));

        // Смена задания начинает подготовку заново: наработки под кражу не годятся для саботажа.
        if (!request.targetPlayerId().equals(spy.getTargetPlayerId()) || spy.getMission() != request.mission()) {
            spy.setPoints(0);
        }
        spy.setTargetPlayerId(request.targetPlayerId());
        spy.setMission(request.mission());
        spyRepository.save(spy);

        log.info("Игрок {} отправил шпиона к {} с заданием {} на ходу {}",
                player.getName(), target.getName(), request.mission().getLabel(), turn);
        return toDto(spy, Map.of(target.getId(), target));
    }

    /**
     * Ход шпионов — п. 13: агенты дома пополняют запас империи, отправленные копят на
     * операцию и делают её, как только набрали.
     * <p>
     * Игроки и колонии берутся из контекста хода: галактика на ход вычитывается один раз,
     * и саботажу незачем читать её заново. Туда же уходят события — их увидят обе стороны:
     * и тот, чей агент сработал, и тот, кого обокрали.
     */
    @Transactional
    public void advance(TurnContext context) {
        List<PlayerEntity> players = context.players();
        Integer turn = context.turn();
        Map<UUID, PlayerEntity> byId = players.stream()
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        // Список ИЗМЕНЯЕМЫЙ: ниже из него вычёркиваются пойманные агенты
        // ({@code spies.removeAll(caught)}), а `Stream.toList` отдаёт неизменяемый.
        List<SpyEntity> spies = new ArrayList<>(
                spyRepository.findAllByOwnerPlayerIdIn(byId.keySet()).stream()
                        .sorted(GameOrder.SPIES)
                        .toList());
        Map<UUID, List<SpyEntity>> byOwner = spies.stream()
                .collect(Collectors.groupingBy(SpyEntity::getOwnerPlayerId));
        // Лидеры всех империй — одной выборкой: запрос «на игрока» изнутри посчитанного
        // хода заставляет Hibernate сбрасывать в базу всё, что ход успел изменить.
        Map<UUID, LeaderBonusService.Bonuses> officers = leaderBonuses.of(byId.keySet());

        // Контрразведка считается один раз на всех: она у империи общая — п. 13.
        Map<UUID, Integer> defenceByPlayer = players.stream()
                .collect(Collectors.toMap(PlayerEntity::getId,
                        player -> defense(player, byOwner.getOrDefault(player.getId(), List.of()),
                                espionageDefence(officers.getOrDefault(player.getId(),
                                        LeaderBonusService.Bonuses.empty())))));

        List<SpyEntity> caught = new ArrayList<>();
        for (PlayerEntity player : players) {
            List<SpyEntity> own = byOwner.getOrDefault(player.getId(), List.of());
            RaceEffects race = raceService.effects(player);

            LeaderBonusService.Bonuses bonuses = officers.getOrDefault(
                    player.getId(), LeaderBonusService.Bonuses.empty());
            player.setEspionagePoints(player.getEspionagePoints()
                    + pointsPerTurn(race, atHome(own).size(), bonuses.empireValue("SPY_MASTER")));
            // Число агентов империи держим в согласии с их строками: по нему считает
            // интерфейс и живут сохранения, снятые до появления заданий.
            player.setSpies(own.size());

            for (SpyEntity spy : own) {
                if (spy.getTargetPlayerId() == null || spy.getMission().getCost() == 0) {
                    continue;
                }
                Integer defence = defenceByPlayer.getOrDefault(spy.getTargetPlayerId(), 0);
                spy.setPoints(spy.getPoints() + POINTS_PER_SPY + Math.max(0, race.espionagePoints()) - defence);

                if (spy.getPoints() < 0) {
                    // Контрразведка вышла на агента: он пойман и больше не работает.
                    caught.add(spy);
                    diplomacyService.incident(spy.getTargetPlayerId(), player.getId(), CAUGHT_TRUST_PENALTY);
                    PlayerEntity catcher = byId.get(spy.getTargetPlayerId());
                    context.report().add(player.getId(), "ESPIONAGE",
                            new MessageKey("turn.espionage.caught", name(catcher)));
                    context.report().add(spy.getTargetPlayerId(), "ESPIONAGE",
                            new MessageKey("turn.espionage.caughtTheirs", player.getName()));
                    log.info("Контрразведка поймала агента игрока {} на ходу {}", player.getName(), turn);
                    continue;
                }
                if (spy.getPoints() >= spy.getMission().getCost()) {
                    spy.setPoints(spy.getPoints() - spy.getMission().getCost());
                    run(context, player, spy, byId.get(spy.getTargetPlayerId()));
                }
            }
        }

        spies.removeAll(caught);
        spyRepository.saveAll(spies);
        spyRepository.deleteAll(caught);
        for (SpyEntity lost : caught) {
            PlayerEntity owner = byId.get(lost.getOwnerPlayerId());
            owner.setSpies(Math.max(0, owner.getSpies() - 1));
        }
        playerRepository.saveAll(players);
    }

    /**
     * Надбавка лидеров к контрразведке — п. 6.
     * <p>
     * Способностей две («Убийца» и «Телепат»), и обе делают одно и то же: складываются,
     * а не спорят. Разными их держит справочник — в оригинале это разные лидеры.
     */
    private Integer espionageDefence(LeaderBonusService.Bonuses bonuses) {
        return bonuses.empireValue("ASSASSIN") + bonuses.empireValue("TELEPATH");
    }

    /** Новый агент, подготовленный колонией — п. 13. */
    @Transactional
    public SpyEntity recruit(UUID ownerPlayerId, Integer turn) {
        SpyEntity spy = new SpyEntity();
        spy.setOwnerPlayerId(ownerPlayerId);
        spy.setMission(SpyMission.HOME);
        spy.setPoints(0);
        spy.setCreatedTurn(turn);
        return spyRepository.save(spy);
    }

    /** Операция агента: кража технологии или саботаж — п. 13. */
    private void run(TurnContext context, PlayerEntity owner, SpyEntity spy, PlayerEntity target) {
        if (target == null) {
            return;
        }
        Boolean done = switch (spy.getMission()) {
            case STEAL_TECH -> stealTechnology(context, owner, target);
            case SABOTAGE -> sabotage(context, target);
            case HOME, COUNTER -> Boolean.FALSE;
        };
        if (Boolean.TRUE.equals(done)) {
            diplomacyService.incident(target.getId(), owner.getId(), INCIDENT_TRUST_PENALTY);
            // Счётчик стоит ЗДЕСЬ, а не внутри кражи: заданий два, а считался одно —
            // саботаж уходил мимо счёта, и прибор балансировки видел вдвое меньше работы
            // разведки, чем её было. Те же грабли, что с COLONIZED: счётчик молчит не
            // потому, что механика спит, а потому, что запись стоит не там.
            activity.record(context.game().getId(), owner.getId(), EmpireActivityService.ESPIONAGE);
        }
    }

    /**
     * Кража технологии: берётся та, которую соперник изучил, а империя — ещё нет.
     * Крадут самую раннюю по уровню: агент выносит то, что лежит ближе.
     */
    private Boolean stealTechnology(TurnContext context, PlayerEntity owner, PlayerEntity target) {
        Integer turn = context.turn();
        Set<String> known = technologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(owner.getId()).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());

        // ПОРЯДОК ЗДЕСЬ РЕШАЕТ, ЧТО УКРАДУТ: берётся первая из списка. Сортировка по одному
        // уровню его не задаёт — у игрока бывает НЕСКОЛЬКО технологий одного уровня одного
        // раздела (изобретательная раса забирает уровень целиком, обмен и кража приносят
        // чужие), и среди них порядок доставался базе, то есть случайным идентификаторам
        // строк. Две одинаковые партии крали РАЗНОЕ и дальше расходились целиком: официальная
        // проверка повторимости ловила это на 235-м ходу разницей в одну технологию.
        // Ключ полный и игровой: уровень, потом раздел, потом код самой технологии.
        List<PlayerTechnologyEntity> stealable = technologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(target.getId()).stream()
                .filter(technology -> !known.contains(technology.getOptionCode()))
                .sorted(Comparator.comparing(PlayerTechnologyEntity::getLevelOrder)
                        .thenComparing(PlayerTechnologyEntity::getCategoryCode)
                        .thenComparing(PlayerTechnologyEntity::getOptionCode))
                .toList();
        if (stealable.isEmpty()) {
            return Boolean.FALSE;
        }

        PlayerTechnologyEntity source = stealable.getFirst();
        PlayerTechnologyEntity stolen = new PlayerTechnologyEntity();
        stolen.setPlayerId(owner.getId());
        stolen.setCategoryCode(source.getCategoryCode());
        stolen.setLevelOrder(source.getLevelOrder());
        stolen.setOptionCode(source.getOptionCode());
        stolen.setAcquiredTurn(turn);
        technologyRepository.save(stolen);

        // Ссылкой, а не кодом: в отчёте стояло «технологию colony-ship» — код, годный
        // для базы и негодный для чтения (п. 3.5).
        context.report().add(owner.getId(), "ESPIONAGE",
                new MessageKey("turn.espionage.stole", target.getName(),
                        CatalogTexts.tech(source.getOptionCode())));
        context.report().add(target.getId(), "ESPIONAGE",
                new MessageKey("turn.espionage.stolenFromYou",
                        CatalogTexts.tech(source.getOptionCode())));
        log.info("Шпион игрока {} украл у {} технологию {} на ходу {}",
                owner.getName(), target.getName(), source.getOptionCode(), turn);
        return Boolean.TRUE;
    }

    /**
     * Саботаж: срывается стройка одной из колоний соперника — вложенные единицы
     * производства пропадают. Выбирается колония, вложившая больше всех: агент бьёт
     * туда, где потеря заметнее.
     */
    private Boolean sabotage(TurnContext context, PlayerEntity target) {
        List<PlanetEntity> colonies = context.coloniesOf(target.getId()).stream()
                .filter(planet -> planet.getProjectPoints() > 0)
                // При РАВНЫХ вложениях выбор должен быть определённым: без второго ключа
                // он достаётся порядку, в котором колонии пришли в ход, — а тот выведен из
                // названий и повторяется, но полагаться на это молча нельзя. Название и
                // есть ключ (см. GameOrder).
                .sorted(Comparator.comparing(PlanetEntity::getProjectPoints).reversed()
                        .thenComparing(PlanetEntity::getName))
                .toList();
        if (colonies.isEmpty()) {
            return Boolean.FALSE;
        }

        PlanetEntity colony = colonies.getFirst();
        Integer lost = colony.getProjectPoints();
        log.info("Шпион сорвал стройку колонии {}: пропало {} единиц производства",
                colony.getName(), lost);
        colony.setProjectPoints(0);
        planetRepository.save(colony);

        context.report().add(target.getId(), "ESPIONAGE",
                new MessageKey("turn.espionage.sabotage", colony.getName(), lost),
                context.systemOf(colony), colony.getId());
        return Boolean.TRUE;
    }

    /** Имя империи для журнала: пойманный агент мог не знать, кто именно его взял. */
    private String name(PlayerEntity player) {
        return player == null ? "соперника" : player.getName();
    }

    /**
     * Шпионы, пополняющие общий запас очков: только те, кто просто дома. Агент на
     * контрразведке очков не приносит — он ловит чужих.
     */
    private List<SpyEntity> atHome(List<SpyEntity> spies) {
        return spies.stream()
                .filter(spy -> spy.getMission() == SpyMission.HOME)
                .toList();
    }

    private Map<UUID, PlayerEntity> targetsOf(List<SpyEntity> spies) {
        List<UUID> ids = spies.stream()
                .map(SpyEntity::getTargetPlayerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty()
                ? Map.of()
                : playerRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
    }

    private SpyDto toDto(SpyEntity spy, Map<UUID, PlayerEntity> targets) {
        PlayerEntity target = spy.getTargetPlayerId() == null ? null : targets.get(spy.getTargetPlayerId());
        return new SpyDto(
                spy.getId(),
                spy.getMission().name(),
                messages.label(spy.getMission()),
                spy.getMission().getCost(),
                spy.getPoints(),
                spy.getTargetPlayerId(),
                target == null ? null : target.getName(),
                spy.getCreatedTurn());
    }
}
