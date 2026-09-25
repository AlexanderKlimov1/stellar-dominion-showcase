package com.moo3.server.service;

import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.dto.CreateGameRequest;
import com.moo3.server.dto.CreateGameResponse;
import com.moo3.server.dto.EndTurnRequest;
import com.moo3.server.dto.EndTurnResponse;
import com.moo3.server.dto.GalaxyMapDto;
import com.moo3.server.dto.GameDetailsDto;
import com.moo3.server.dto.GameSaveDto;
import com.moo3.server.dto.GameSummaryDto;
import com.moo3.server.dto.JoinGameRequest;
import com.moo3.server.dto.PlayerCredentialsDto;
import com.moo3.server.dto.SaveGameRequest;
import com.moo3.server.dto.TurnReportDto;
import com.moo3.server.dto.StartGameRequest;
import com.moo3.server.galaxy.GalaxyGenerator;
import com.moo3.server.galaxy.HomeworldAllocator;
import com.moo3.server.repository.GameRepository;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.StarSystemRepository;
import com.moo3.server.service.stub.TurnService;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Жизненный цикл партии — п. 3.1, 3.2 и генерация галактики по п. 4.2.
 * <p>
 * Игру создаёт произвольный игрок, она сразу попадает в список видимых игр.
 * До {@code maxHumanPlayers} человек могут присоединиться; при старте свободные
 * слоты добираются ИИ-игроками до {@code totalPlayers}.
 * <p>
 * Действия внутри партии — колонии, стройка, исследования — живут в своих сервисах и
 * сюда не заходят: здесь только сама партия, её ходы, карта и сохранения.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /**
     * Нужен ровно для одного — убрать за удалённой партией то, что связано с ней полем,
     * а не ссылкой (см. {@link #deleteBelongings}). Пятнадцать репозиториев ради
     * одиннадцати удалений завести можно, но читать их будет труднее, чем этот список.
     */
    @PersistenceContext
    private EntityManager entityManager;

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository starSystemRepository;
    private final GalaxyGenerator galaxyGenerator;
    private final HomeworldAllocator homeworldAllocator;
    private final GameMapper gameMapper;
    private final GameProperties gameProperties;
    private final GameAccess gameAccess;
    private final PlayerRoster playerRoster;
    private final ShipDesignService shipDesignService;
    private final ResearchService researchService;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final TurnService turnService;
    private final GameSaveService gameSaveService;
    private final ExplorationService explorationService;
    private final RaceService raceService;
    private final GameEventPublisher gameEvents;
    private final GameEventService gameEventService;

    /**
     * Сколько партий отдаётся списком. Партий в базе накапливаются сотни, а в лобби
     * смотрят на свежие: без предела список рос бы вместе с базой.
     */
    private static final int GAMES_PAGE = 50;

    public GameService(GameRepository gameRepository,
                       PlayerRepository playerRepository,
                       StarSystemRepository starSystemRepository,
                       GalaxyGenerator galaxyGenerator,
                       HomeworldAllocator homeworldAllocator,
                       GameMapper gameMapper,
                       GameProperties gameProperties,
                       GameAccess gameAccess,
                       PlayerRoster playerRoster,
                       ShipDesignService shipDesignService,
                       ResearchService researchService,
                       PlanetBuildingRepository planetBuildingRepository,
                       TurnService turnService,
                       GameSaveService gameSaveService,
                       ExplorationService explorationService,
                       RaceService raceService,
                       GameEventPublisher gameEvents,
                       GameEventService gameEventService) {
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.starSystemRepository = starSystemRepository;
        this.galaxyGenerator = galaxyGenerator;
        this.homeworldAllocator = homeworldAllocator;
        this.gameMapper = gameMapper;
        this.gameProperties = gameProperties;
        this.gameAccess = gameAccess;
        this.playerRoster = playerRoster;
        this.shipDesignService = shipDesignService;
        this.researchService = researchService;
        this.planetBuildingRepository = planetBuildingRepository;
        this.turnService = turnService;
        this.gameSaveService = gameSaveService;
        this.explorationService = explorationService;
        this.raceService = raceService;
        this.gameEvents = gameEvents;
        this.gameEventService = gameEventService;
    }

    /**
     * Список игр, видимых всем, кто видит IP сервера — п. 3.1.
     * <p>
     * Два запроса на весь список: партии страницей и все их игроки разом. Раньше игроки
     * запрашивались на каждую партию, и список из сотни игр стоил сотни запросов.
     */
    @Transactional(readOnly = true)
    public List<GameSummaryDto> listGames(Boolean openOnly) {
        PageRequest page = PageRequest.of(0, GAMES_PAGE);
        List<GameEntity> games = Boolean.TRUE.equals(openOnly)
                ? gameRepository.findAllByStatusOrderByCreatedAtDesc(GameStatus.LOBBY, page)
                : gameRepository.findAllByOrderByCreatedAtDesc(page);
        if (games.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<PlayerEntity>> playersByGame = playerRepository
                .findAllByGameIdInOrderBySlotAsc(games.stream().map(GameEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(player -> player.getGame().getId()));

        return games.stream()
                .map(game -> gameMapper.toSummary(game, playersByGame.getOrDefault(game.getId(), List.of())))
                .toList();
    }

    /** Создание игры — п. 3.1. Создатель занимает слот 1 и становится хостом. */
    @Transactional
    public CreateGameResponse createGame(CreateGameRequest request) {
        GalaxySize galaxySize = request.galaxySizeOrDefault();

        GameEntity game = new GameEntity();
        game.setName(gameName(request));
        game.setGalaxySize(galaxySize);
        game.setWidthParsecs(galaxySize.getWidthParsecs());
        game.setHeightParsecs(galaxySize.getHeightParsecs());
        game.setStarCount(galaxySize.getStarCount());
        game.setStatus(GameStatus.LOBBY);
        game.setTurn(0);
        game.setTotalPlayers(request.totalPlayersOr(gameProperties.totalPlayers()));
        game.setMaxHumanPlayers(gameProperties.maxHumanPlayers());
        game.setSeed(request.seed() == null ? new Random().nextLong() : request.seed());
        // Случайные события — п. 11.1: переключатель окна новой игры, как в MOO II.
        game.setGalacticEvents(request.galacticEventsOrDefault());
        game.setCouncil(request.councilOrDefault());
        game.setCreatedAt(OffsetDateTime.now());

        PlayerEntity host = playerRoster.human(request.playerName(), 1, request.raceCode(), Set.of());
        host.setHomeStarName(playerRoster.homeStarName(request.homeStarName()));
        playerRoster.applyRaceDesign(host, request.raceName(), request.raceTraits());
        game.addPlayer(host);

        // Партия без людей — п. 3.2: создатель остаётся хозяином партии и держит пропуск,
        // но империю за него ведёт ИИ. Ждать такую партию не приходится ни от кого
        // (`waitingFor` считает только людей), поэтому каждый конец хода считает ход сразу.
        if (Boolean.TRUE.equals(request.observerOrDefault())) {
            host.setPlayerType(PlayerType.AI);
            playerRoster.giveCharacter(host, game, host.getSlot());
        }

        gameRepository.saveAndFlush(game);
        game.setHostPlayerId(host.getId());

        log.info("Создана игра {} ({}), хост {}{}", game.getName(), galaxySize, host.getName(),
                Boolean.TRUE.equals(request.observerOrDefault()) ? " (наблюдатель)" : "");
        return joinedResponse(game, host);
    }

    @Transactional(readOnly = true)
    public GameDetailsDto getGame(UUID gameId) {
        return details(gameAccess.requireGame(gameId));
    }

    /** Присоединение игрока — п. 3.2, не более {@code maxHumanPlayers} человек. */
    @Transactional
    public CreateGameResponse joinGame(UUID gameId, JoinGameRequest request) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameAccess.requireLobby(game);

        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        if (players.size() >= game.getMaxHumanPlayers()) {
            throw new ConflictException("game.humansFull", game.getMaxHumanPlayers());
        }

        Set<String> takenRaces = players.stream().map(PlayerEntity::getRaceCode).collect(Collectors.toSet());
        PlayerEntity player = playerRoster.human(
                request.playerName(), playerRoster.nextFreeSlot(players), request.raceCode(), takenRaces);
        player.setHomeStarName(playerRoster.homeStarName(request.homeStarName()));
        playerRoster.applyRaceDesign(player, request.raceName(), request.raceTraits());
        player.setGame(game);
        playerRepository.saveAndFlush(player);
        gameEvents.playerJoined(game, player);
        log.info("Игрок {} присоединился к игре {}", player.getName(), game.getName());
        return joinedResponse(game, player);
    }

    /**
     * Старт игры — п. 3.2. Стартует только создатель, при любом количестве
     * присоединившихся игроков, в том числе без них.
     */
    @Transactional
    public GameDetailsDto startGame(UUID gameId, StartGameRequest request) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameAccess.requireLobby(game);
        gameAccess.requireHost(game, request.accessToken());

        List<PlayerEntity> humans = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        playerRepository.saveAll(playerRoster.aiPlayers(game, humans, request.aiEmpires()));
        playerRepository.flush();

        List<StarSystemEntity> systems = starSystemRepository.saveAll(galaxyGenerator.generate(game));
        starSystemRepository.flush();

        List<PlayerEntity> allPlayers = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        homeworldAllocator.allocate(systems, allPlayers, playerRoster.homeClimatesByRace(),
                playerRoster.raceEffectsByPlayer(allPlayers),
                new Random(game.getSeed()));
        playerRepository.flush();

        game.setStatus(GameStatus.IN_PROGRESS);
        game.setStartedAt(OffsetDateTime.now());
        game.setTurn(1);
        gameRepository.saveAndFlush(game);

        // Каждая империя входит в партию с тремя первыми уровнями дерева — п. 9. Они
        // названы в самом описании дерева, и без них кораблестроения у империи нет вовсе:
        // оно требует Power и Chemistry разом, а ИИ, чьё устремление не любит химию, за
        // неё не брался никогда — см. ResearchService.grantStarting.
        allPlayers.forEach(player -> researchService.grantStarting(player, game.getTurn()));

        // Каждая империя входит в партию со стартовым проектом корабля — п. 8. Без него
        // колонии нечего строить в первый же ход, а окно дизайна открывалось бы пустым.
        allPlayers.forEach(player -> {
            shipDesignService.ensureAutoDesigns(game.getId(), player, game.getTurn());
            // Звёздная база родного мира вооружена с первого хода — п. 8, п. 11.
            shipDesignService.ensurePlatformDesigns(game.getId(), player, game.getTurn());
        });

        // Родной мир начинает со звёздной базой на орбите и с казармами — п. 8 и п. 12.
        //
        // Звёздная база в MOO II это верфь («star dock capable of building ships larger than
        // destroyers»): без неё колония поднимает только два наименьших корпуса, и с первого
        // дня империя должна уметь строить всё, что изучит.
        //
        // Казармы морской пехоты в оригинале не требуют исследований вовсе и стоят на родном
        // мире с начала партии: колонию защищают обученные бойцы, а не жители с винтовками.
        // Строятся они и на всякой новой колонии — технологии у них нет, и список стройки
        // предлагает их с первого хода (resources/Buildings/buildings.json).
        planetBuildingRepository.saveAll(systems.stream()
                .flatMap(system -> system.getPlanets().stream())
                .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                .flatMap(planet -> Stream.of(
                        building(planet.getId(), ShipDesignRules.STAR_BASE, game.getTurn()),
                        building(planet.getId(), MARINE_BARRACKS, game.getTurn())))
                .toList());

        gameEvents.gameStarted(game);
        log.info("Игра {} стартовала: {} игроков, {} звёздных систем",
                game.getName(), allPlayers.size(), systems.size());
        return details(game);
    }

    /**
     * Казармы морской пехоты — п. 12. Код здания из
     * {@code resources/Buildings/buildings.json}; технологии у них нет, поэтому строить их
     * можно с первого хода, а родной мир получает их готовыми.
     */
    private static final String MARINE_BARRACKS = "marine-barracks";

    /** Постройка на планете — строка построенного здания. */
    private PlanetBuildingEntity building(UUID planetId, String code, Integer turn) {
        PlanetBuildingEntity building = new PlanetBuildingEntity();
        building.setPlanetId(planetId);
        building.setBuildingCode(code);
        building.setBuiltTurn(turn);
        return building;
    }

    /**
     * Конец хода игрока — п. 11.1.
     * <p>
     * Игроки ходят одновременно, каждый в своём темпе: игрок объявляет, что закончил, и
     * ждёт остальных. Когда закончили все люди партии, галактика считается один раз —
     * фазами {@link TurnService}: рост населения (п. 4.1.1), производство и доход (п. 10),
     * исследования (п. 9), шпионаж (п. 13). ИИ ждать не заставляет: своих решений он пока
     * не принимает.
     * <p>
     * Партия берётся под замок на запись: пересчёт трогает всю галактику, и делать это
     * одновременно нельзя. Замок стоит на строке партии, поэтому игроки соседних партий
     * друг друга не ждут.
     * <p>
     * Повторный конец хода в том же ходу ничего не ломает: игрок уже отмечен, и ответ
     * скажет, кого ещё ждут.
     */
    @Transactional
    public EndTurnResponse endTurn(UUID gameId, EndTurnRequest request) {
        // Пропуск проверяем запросом по самому игроку: игру до замка не читаем совсем.
        // Прочитанная заранее игра попадала бы в контекст со старой версией, и своя же
        // запись падала бы на проверке версии, хотя замок к тому времени уже наш.
        UUID playerId = gameAccess.requireToken(gameId, request.accessToken());

        // С этого места ход партии считается в одиночку: остальные ждут на замке.
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);

        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();

        player.setEndedTurn(game.getTurn());
        playerRepository.save(player);
        gameEvents.playerReady(game, player);

        List<UUID> waiting = waitingFor(game, players);
        if (!waiting.isEmpty()) {
            log.info("Игрок {} закончил ход {} в игре {}: ждём ещё {}",
                    player.getName(), game.getTurn(), game.getName(), waiting.size());
            return new EndTurnResponse(details(game, players), Boolean.FALSE, waiting, null);
        }

        Integer completedTurn = game.getTurn();
        TurnReport report = turnService.endTurn(game);
        gameRepository.saveAndFlush(game);

        // Отчёты ложатся в базу и уходят подписчикам: тот, кто закончил первым, узнает
        // о пересчёте только отсюда — его собственный запрос ответил ожиданием.
        gameEvents.turnAdvanced(game, players, report);

        log.info("Игра {}: ход {} посчитан, наступил {}", game.getName(), completedTurn, game.getTurn());
        return new EndTurnResponse(details(game, players), Boolean.TRUE, List.of(),
                gameEventService.shown(report.forPlayer(player.getId())));
    }

    /**
     * Кого ещё ждёт партия: люди, не объявившие конец текущего хода.
     * <p>
     * ИИ в список не попадает — он не решает и ждать себя не заставляет. Отключившийся
     * игрок-человек партию задержит, и это видно в интерфейсе: кого ждут, там названо.
     */
    private List<UUID> waitingFor(GameEntity game, List<PlayerEntity> players) {
        return players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .filter(player -> player.getEndedTurn() < game.getTurn())
                .sorted(Comparator.comparing(PlayerEntity::getSlot))
                .map(PlayerEntity::getId)
                .toList();
    }

    /** Что изменилось за прошлый ход — п. 11.1: клиент забирает отчёт после перезагрузки. */
    @Transactional(readOnly = true)
    public TurnReportDto turnReport(UUID gameId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return gameEventService.lastReport(player.getId());
    }

    /**
     * Сохранение партии по команде игрока — «Игра» → «Сохранить».
     * <p>
     * В слепок идёт состояние на конец последнего завершённого хода: расчётов внутри
     * хода MVP не ведёт, поэтому текущее состояние партии ему и равно.
     */
    @Transactional
    public GameSaveDto saveGame(UUID gameId, SaveGameRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());

        GameSaveDto save = gameSaveService.save(game, game.getTurn() - 1);
        log.info("Игрок {} сохранил партию {}", player.getName(), game.getName());
        return save;
    }

    /**
     * Загрузка сохранения — «Игра» → «Загрузить».
     * <p>
     * Из слепка поднимается новая партия, а клиент, который её загрузил, получает пропуск
     * первого игрока-человека: за остальных людей в загруженной партии играет ИИ, пока
     * они не присоединятся заново.
     */
    @Transactional
    public CreateGameResponse loadSave(UUID saveId) {
        GameEntity game = gameSaveService.restore(saveId);
        PlayerEntity player = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId()).stream()
                .filter(candidate -> candidate.getPlayerType() == PlayerType.HUMAN)
                .findFirst()
                .orElseThrow(() -> new ConflictException("save.noHuman"));
        return joinedResponse(game, player);
    }

    /**
     * Карта галактики для отрисовки клиентом — п. 11.3.
     * <p>
     * Звёзды отдаются все: положение светила известно из любой точки галактики. Подробности
     * — название, планеты, владелец — только по разведанным системам, а разведку определяет
     * {@link ExplorationService}: свои системы и те, куда игрок отправлял шпиона (п. 15).
     * Флаг {@code revealAll} — «Показать галактику» в интерфейсе — раскрывает подробности
     * по всей галактике.
     */
    @Transactional(readOnly = true)
    public GalaxyMapDto getMap(UUID gameId, String accessToken, Boolean revealAll) {
        GameEntity game = gameAccess.requireGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);

        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(gameId).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        if (systems.isEmpty()) {
            throw new ConflictException("game.noGalaxy");
        }

        Set<UUID> explored = Boolean.TRUE.equals(revealAll)
                ? systems.stream().map(StarSystemEntity::getId).collect(Collectors.toUnmodifiableSet())
                : explorationService.exploredBy(systems, player);

        // Сканеры добавляют к неразведанным системам один факт: есть ли там кто-то — п. 15.
        Set<UUID> scanned = explorationService.scannedBy(systems, player);
        Set<UUID> occupied = explorationService.occupiedBy(systems, player, scanned);

        // Всевидящая раса (п. 7) видит и сторожей систем, куда не летала, — п. 11.1:
        // в этом и состоит её сторона, знать галактику, не летая.
        return gameMapper.toMap(game, systems, explored, scanned, occupied,
                gameProperties.minStarDistanceParsecs(),
                raceService.effects(player).omniscient());
    }

    /**
     * Удаление игры доступно только её создателю. Сохранения партии уходят вместе с ней:
     * без партии слепок никому не нужен, а раньше они оставались в базе навсегда.
     */
    @Transactional
    public void deleteGame(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameAccess.requireHost(game, accessToken);
        gameSaveService.deleteByGame(gameId);
        deleteBelongings(gameId);
        gameRepository.delete(game);
        log.info("Игра {} удалена вместе со своими сохранениями", game.getName());
    }

    /**
     * Всё, что принадлежит партии, но связано с ней ОДНИМ ПОЛЕМ, а не ссылкой, — п. 3.
     * <p>
     * <b>Почему это приходится делать руками.</b> У летописи, счётчиков механик, флотов,
     * проектов кораблей, боёв, событий и перевозок поле {@code game_id} объявлено простой
     * колонкой, а не связью: выборки внутри хода ходят по идентификаторам, и связь тут была
     * бы лишней загрузкой. Внешние ключи с каскадом им ставит Liquibase — и ставит их
     * только там, где changelog прошёл, то есть в Postgres. На H2, где схему строит
     * Hibernate по сущностям (режим балансового прогона), таких ключей нет НИ ОДНОГО, и
     * удаление партии оставляло в памяти всё её хозяйство.
     * <p>
     * <b>Чем это обернулось.</b> Круг 5 замедлялся по ходу дела: первые партии шли по
     * тридцать секунд, к трёхсотой — по шестьдесят, а куча к тому времени держала МИЛЛИОНЫ
     * строк H2 (гистограмма: 5,1 млн ValueUuid, 3,3 млн Sparse) при восьми живых партиях.
     * Каждая партия оставляла после себя четыре тысячи строк одной только летописи
     * (восемь империй на пятьсот ходов), и к концу прогона их набиралось два миллиона.
     * Постгресу это было не видно: там каскад отрабатывал.
     * <p>
     * Порядок удаления — от внуков к детям: у {@code fleet_ship}, {@code battle_ship} и
     * {@code ship_design_component} свои ключи на флот, бой и проект, и в Postgres они
     * настоящие.
     */
    private void deleteBelongings(UUID gameId) {
        for (String statement : List.of(
                "delete from fleet_ship where fleet_id in (select id from fleet where game_id = ?1)",
                "delete from battle_ship where battle_id in "
                        + "(select id from space_battle where game_id = ?1)",
                "delete from ship_design_component where design_id in "
                        + "(select id from ship_design where game_id = ?1)",
                "delete from space_battle where game_id = ?1",
                "delete from fleet_encounter where game_id = ?1",
                "delete from fleet where game_id = ?1",
                "delete from ship_design where game_id = ?1",
                "delete from population_transfer where game_id = ?1",
                "delete from player_event where game_id = ?1",
                "delete from empire_activity where game_id = ?1",
                "delete from empire_history where game_id = ?1",
                "delete from turn_report where game_id = ?1",
                "delete from council_vote where game_id = ?1",
                // Постройки колоний: у планеты своего списка построек нет (ни одного
                // @OneToMany), и Hibernate за ней их не уносит. С новым ИИ это самая
                // толстая из оставшихся куч — зданий на партию идут тысячи.
                "delete from planet_building where planet_id in (select p.id from planet p "
                        + "join star_system s on s.id = p.star_system_id where s.game_id = ?1)",
                // То же и у игрока: изученное, разведанное, агенты, лидеры и отношения
                // висят на player_id простой колонкой.
                "delete from player_technology where player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from player_explored_system where player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from player_leader where player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from spy where owner_player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from diplomacy_relation where player_id in "
                        + "(select id from player where game_id = ?1)")) {
            entityManager.createNativeQuery(statement).setParameter(1, gameId).executeUpdate();
        }
    }

    /**
     * Убрать партию распоряжением администратора — без пропуска её хозяина.
     * <p>
     * Нужно ровно для одного: БРОШЕННЫХ партий. Прогон, оборвавшийся посередине (упал
     * скрипт, остановили сервер), оставляет партии, пропуск хозяина которых не знает уже
     * никто, — и убрать их нечем, хотя копятся они быстро: в базе однажды набралось восемь с
     * половиной сотен. Это хозяйство сервера, а не своя партия, поэтому право здесь такое
     * же, как у удаления сохранений: у брошенной партии хозяина нет, и решает администратор.
     */
    @Transactional
    public void deleteByAdmin(UUID gameId) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameSaveService.deleteByGame(gameId);
        deleteBelongings(gameId);
        gameRepository.delete(game);
        log.info("Игра {} убрана администратором вместе со своими сохранениями", game.getName());
    }

    private CreateGameResponse joinedResponse(GameEntity game, PlayerEntity player) {
        GameDetailsDto details = details(game);
        return new CreateGameResponse(details.game(), details.players(), credentials(game, player));
    }

    private GameDetailsDto details(GameEntity game) {
        return details(game, playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId()));
    }

    /** Состав уже загружен: конец хода читает игроков один раз и переиспользует список. */
    private GameDetailsDto details(GameEntity game, List<PlayerEntity> players) {
        return gameMapper.toDetails(game, players, playerRoster.raceNames());
    }

    private PlayerCredentialsDto credentials(GameEntity game, PlayerEntity player) {
        return new PlayerCredentialsDto(
                game.getId(),
                player.getId(),
                player.getAccessToken(),
                player.getId().equals(game.getHostPlayerId()));
    }

    private String gameName(CreateGameRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return "Игра " + request.playerName();
        }
        return request.name();
    }
}
