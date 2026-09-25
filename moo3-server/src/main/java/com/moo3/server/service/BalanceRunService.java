package com.moo3.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.domain.entity.history.BalanceCombinationEntity;
import com.moo3.server.domain.entity.history.BalanceRunEntity;
import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.dto.AdvanceTurnsRequest;
import com.moo3.server.dto.BalanceCombinationDto;
import com.moo3.server.dto.BalanceRunDto;
import com.moo3.server.dto.BalanceRunRequest;
import com.moo3.server.dto.BalanceTelemetryDto;
import com.moo3.server.dto.CreateGameRequest;
import com.moo3.server.dto.CreateGameResponse;
import com.moo3.server.dto.StartGameRequest;
import com.moo3.server.repository.history.BalanceCombinationRepository;
import com.moo3.server.repository.history.BalanceRunRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Балансовый прогон, заказанный из пульта администратора — этап 2 плана
 * (`balance-metrics-works.txt`).
 * <p>
 * До сих пор прогоны жили скриптом в {@code tools/}: это удобно разработчику и никак не
 * доступно хозяину игры. Здесь то же самое делает сервер: администратор описывает прогон
 * (галактика, сколько империй и чем играют, сколько партий и по сколько ходов), сервер
 * играет их в фоне и возвращает не сырые замеры, а <b>приговор каждой цене</b>.
 * <p>
 * <b>Цены снимаются до первой партии.</b> Прогон идёт десятки минут, а цены правятся с
 * соседнего экрана и прямо в файле; без снимка через день нельзя сказать, какие именно
 * цены он мерил. Снимок лежит в самой записи прогона.
 * <p>
 * <b>Прогон идёт одним потоком и по одному за раз.</b> Каждая партия — это сотни ходов,
 * каждый ход своей транзакцией; пускать их пачкой значило бы уронить отзывчивость сервера
 * для тех, кто в это время играет. Скрипт в {@code tools/} остаётся для длинных прогонов
 * в несколько потоков — там сервер занят только этим.
 */
@Service
public class BalanceRunService {

    private static final Logger log = LoggerFactory.getLogger(BalanceRunService.class);

    /** Род прогона: приговоры ценам (этап 2) и поиск сильнейших сборок (этап 3). */
    public static final String MEASURE = "MEASURE";

    public static final String ORACLE = "ORACLE";

    /** Сколько сборок в первом поколении поиска, если хозяин прогона не назвал своё число. */
    private static final int DEFAULT_POPULATION = 24;

    public static final String RUNNING = "RUNNING";
    public static final String FINISHED = "FINISHED";
    public static final String FAILED = "FAILED";

    /**
     * Прогон приостановлен хозяином — п. 2 этапа 2.
     * <p>
     * Пауза МЯГКАЯ: новых партий прогон не начинает, а начатые доигрывает. Обрывать партию
     * посередине нельзя — её замер пропал бы, а в прогоне на пятьсот партий каждая на счету.
     * Поэтому пауза вступает в силу не мгновенно, а когда доиграются те, что уже в работе:
     * до нескольких минут на партию.
     * <p>
     * Живёт пауза только в памяти сервера, и это ограничение настоящее: партии прогона
     * лежат в H2 (п. 3.90), замеры копятся в списке, и пережить перезапуск им нечем.
     * Поэтому приостановленный прогон закрывается при старте вместе с идущими.
     */
    public static final String PAUSED = "PAUSED";

    /**
     * Прогоны, поставленные на паузу. Ключ — прогон, значок — сам себе замок: потоки
     * партий ждут на нём и просыпаются, когда паузу снимут.
     */
    private final Set<UUID> paused = ConcurrentHashMap.newKeySet();

    /** Имя игрока-наблюдателя: партию заводит он, а империю его ведёт ИИ. */
    private static final String OBSERVER = "Наблюдатель";

    /** Сколько партий считать разом: настройка по времени суток — п. 2 этапа 2. */
    private final Messages messages;
    private final BalanceLoad load;

    private final BalanceRunRepository repository;
    private final BalanceCombinationRepository combinations;
    private final GameService gameService;
    private final TurnBatchService turnBatchService;
    private final BalanceTelemetryService telemetryService;
    private final RaceTraitCatalog raceTraits;
    private final RaceBuildGenerator builds;
    private final BalanceVerdictRules verdictRules;
    private final BalanceRunProgress progress;
    private final ObjectMapper objectMapper;
    private final BalanceGameRunner games;
    private final BalanceOracleService oracle;
    private final BalanceOracleRules oracleRules;

    /**
     * Сколько партий прогона играется разом.
     * <p>
     * Партия прогона — это сотни ходов подряд, и по одной за раз сто двадцать партий идут
     * полтора часа. Партии друг от друга не зависят вовсе, поэтому счёт идёт настолько
     * вширь, насколько хватает машины.
     * <p>
     * <b>Здесь стояло четыре</b> — с объяснением, что ходы упираются в базу и пятый поток
     * уже не ускоряет. ЭТО ОКАЗАЛОСЬ НЕПРАВДОЙ, и проверяется оно за полминуты: на
     * двенадцатиядерной машине посреди прогона занято было ЯДРО С ЧЕТВЕРТЬЮ (сервер 0,78,
     * Postgres 0,21). База не была узким местом ни на сколько — узким местом был сам
     * предел в четыре потока.
     * <p>
     * <b>На итог замера число потоков не влияет ничем</b>, и это не «шум последнего
     * знака», а устройство сбора: партии засеяны порознь ({@code seed + game}), а
     * результаты складываются обходом задач В ПОРЯДКЕ ЗАПУСКА ({@code future.get()} ждёт
     * именно свою партию), а не по мере готовности. Те же партии, тот же порядок строк,
     * та же регрессия — прогоны остаются сравнимы между собой.
     * <p>
     * <b>ЧИСЛО ПОТОКОВ НИЧЕГО НЕ РЕШАЕТ, и это измерено.</b> Один и тот же заказ (40 партий
     * по 150 ходов, Huge, восемь империй) при разном числе потоков:
     * <pre>
     *    6 потоков  168 с, 178 с
     *    8 потоков  168 с, 180 с, 150 с
     *   10 потоков  164 с, 169 с, 173 с, 177 с, 179 с
     *   12 потоков  177 с
     *   18 потоков  177 с
     * </pre>
     * Всё лежит в 164-180 секунд, то есть в пределах разброса самого замера. Загрузка ядер
     * при этом растёт исправно — 79 % на десяти потоках, 93 % на восемнадцати, — а
     * успевает прогон столько же: лишние проценты процессора уходят на переключение
     * контекста и спор за соединения, а не на работу. Та же примета, что в журнале про
     * «ускорение, от которого работы стало меньше».
     * <p>
     * Прежнее объяснение — «десять, а не двенадцать: пара ядер оставлена живым игрокам» —
     * держалось не на замере: живых игроков во время балансового прогона не бывает вовсе,
     * пульт для того и закрыт администратору.
     * <p>
     * <b>Где предел на самом деле.</b> Не в ядрах (79 %), не в сборщике мусора (0,82 с на
     * шестьдесят секунд прогона, полтора процента одного потока), не в памяти (живой кучи
     * 80 МБ при зарезервированных четырёх гигабайтах, ни одной полной сборки). Остаётся
     * база: поток ждёт её на каждой фиксации хода и на каждой выборке, и это ожидание
     * потоками не закрывается — оно у всех общее. Прямое доказательство: одна настройка
     * Postgres {@code synchronous_commit = off} дала 21 % (партия в 500 ходов 201,8 -> 159,0 с),
     * чего не дало никакое число потоков.
     * <p>
     * Берётся две трети ядра на поток: меньше спора за соединения при той же скорости.
     * Предел пула соединений тоже реален ({@code maximum-pool-size: 24}) — потоков сверх
     * него ставить нельзя, они встанут в очередь за соединением.
     * <p>
     * У оракула свой предел, и трогать его смысла нет — там поле сжимается отсевом до двух
     * партий на поколение, и потоку нечего брать.
     */
    private static final int GAME_WORKERS = Integer.getInteger(
            "moo3.balance.workers",
            Math.min(16, Math.max(4, Runtime.getRuntime().availableProcessors() * 2 / 3)));

    /**
     * Один поток на все прогоны: два прогона разом отняли бы сервер у живых игроков, а
     * очередь из них — это ровно то, чего от пульта и ждут. Партии внутри прогона при
     * этом играются пачкой — см. {@link #GAME_WORKERS}.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "balance-run");
        thread.setDaemon(true);
        return thread;
    });

    public BalanceRunService(Messages messages,
                             BalanceRunRepository repository,
                             BalanceCombinationRepository combinations,
                             GameService gameService,
                             TurnBatchService turnBatchService,
                             BalanceTelemetryService telemetryService,
                             RaceTraitCatalog raceTraits,
                             RaceBuildGenerator builds,
                             BalanceVerdictRules verdictRules,
                             BalanceRunProgress progress,
                             BalanceGameRunner games,
                             BalanceOracleService oracle,
                             BalanceOracleRules oracleRules,
                             ObjectMapper objectMapper,
                              BalanceLoad load) {
        this.messages = messages;
        this.load = load;
        this.repository = repository;
        this.combinations = combinations;
        this.gameService = gameService;
        this.turnBatchService = turnBatchService;
        this.telemetryService = telemetryService;
        this.raceTraits = raceTraits;
        this.builds = builds;
        this.verdictRules = verdictRules;
        this.progress = progress;
        this.games = games;
        this.oracle = oracle;
        this.oracleRules = oracleRules;
        this.objectMapper = objectMapper;
    }

    /** Прогоны свежими сверху — список пульта. */
    public List<BalanceRunDto> runs() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    public BalanceRunDto run(UUID runId) {
        return toDto(repository.findById(runId)
                .orElseThrow(() -> new NotFoundException("balance.runNotFound", runId)));
    }

    /**
     * Новый приговор старому прогону: замеры те же, правила нынешние.
     * <p>
     * Правила чтения замера меняются чаще самих замеров — за один вечер порядок оценки
     * правился трижды. Переигрывать ради этого двести партий по сорок минут значило бы
     * чинить прибор вслепую: пока считаешь, забываешь, что именно проверял. Цены при этом
     * берутся из СНИМКА прогона, а не из нынешнего файла: прогон мерил те цены, и судить
     * его по другим было бы подлогом.
     */
    // Транзакция ИСТОРИИ, а не игры: эти строки живут в своём источнике данных
    // (PersistenceConfig, HistoryPersistenceConfig). Без имени менеджера Spring взял бы
    // главный — игровой, — и запись прогона уехала бы не туда, а в режиме прогона на H2
    // и вовсе в другую базу.
    @Transactional("historyTransactionManager")
    public BalanceRunDto reassess(UUID runId) {
        BalanceRunEntity run = repository.findById(runId)
                .orElseThrow(() -> new NotFoundException("balance.runNotFound", runId));
        if (ORACLE.equals(run.getKind())) {
            return reassessOracle(run);
        }
        if (run.getMeasurements() == null) {
            throw new ConflictException("balance.noSamples");
        }
        List<BalanceVerdictRules.Empire> measured =
                read(run.getMeasurements(), new TypeReference<>() { });
        Map<String, Integer> prices = read(run.getTraitCosts(), new TypeReference<>() { });
        List<String> combination = run.getCombinationTraits() == null ? List.<String>of()
                : read(run.getCombinationTraits(), new TypeReference<>() { });
        BalanceVerdictRules.Assessment assessment =
                verdictRules.assess(measured, prices, combination);
        progress.reassessed(runId, assessment);
        // Пересуженный прогон обновляет и память о связке: правила чтения менялись, и
        // запомненный ответ должен быть тем же, что видно в пульте.
        remember(runId, combination, assessment);
        log.info("Балансовый прогон {} переоценён нынешними правилами", runId);
        return run(runId);
    }

    /**
     * Новый приговор прогону оракула — тем же ладдером, нынешними правилами чтения.
     * <p>
     * Переигрывать поиск ради поправленного правила незачем: в ладдере у каждой сборки уже
     * записаны её партии, сила и ошибка, а правило решает лишь, кого пускать в верхушку и
     * что считать доминированием. Это ровно тот же довод, по которому хранятся сырые замеры
     * прогона цен (п. 2.10): правила чтения меняются чаще самих замеров.
     */
    private BalanceRunDto reassessOracle(BalanceRunEntity run) {
        if (run.getLadder() == null) {
            throw new ConflictException("balance.noLadder");
        }
        BalanceOracleRules.Search was = oracle.read(run.getLadder());
        BalanceOracleRules.Search now = oracleRules.read(was.ladder(), prices().keySet(),
                was.generations());
        progress.searched(run.getId(), oracle.write(now));
        log.info("Оракул сборок {} пересужен нынешними правилами", run.getId());
        return run(run.getId());
    }

    /**
     * Заводит прогон и отдаёт его сразу: играть его будет фоновый поток, а пульт покажет,
     * сколько партий уже сыграно.
     */
    @Transactional("historyTransactionManager")
    public BalanceRunDto start(BalanceRunRequest request) {
        if (!repository.findAllByStatus(RUNNING).isEmpty()) {
            throw new ConflictException("balance.runInProgress");
        }

        long seed = request.seed() == null ? System.nanoTime() : request.seed();
        String kind = kind(request);
        // Оракул сам решает, чем играют империи: он их и ищет. Места заказа ему ни к чему,
        // и спрашивать их было бы обманом — прогон всё равно сыграет своими сборками.
        List<Slot> slots = ORACLE.equals(kind) ? List.of() : slots(request, seed);
        List<String> combination = ORACLE.equals(kind) ? List.<String>of()
                : combination(request, seed);

        BalanceRunEntity entity = new BalanceRunEntity();
        entity.setId(UUID.randomUUID());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setStatus(RUNNING);
        entity.setGalaxySize(galaxySize(request).name());
        entity.setEmpires(request.empires());
        entity.setGames(request.games());
        entity.setTurns(request.turns());
        entity.setSeed(seed);
        entity.setPlayed(0);
        entity.setRaceDesigns(write(slots));
        entity.setTraitCosts(write(prices()));
        entity.setCombinationTraits(combination.isEmpty() ? null : write(combination));
        entity.setKind(kind);
        if (ORACLE.equals(kind)) {
            entity.setPopulation(request.population() == null
                    ? DEFAULT_POPULATION : request.population());
        }
        repository.save(entity);

        UUID runId = entity.getId();
        // Прогон пускается ПОСЛЕ фиксации транзакции, а не отсюда. Фоновый поток первым
        // делом читает запись прогона, и, запущенный из середины незакоммиченной
        // транзакции, он её попросту не находит: прогон молча замирает со словами «идёт»
        // и нулём сыгранных партий, а отметить отказ тоже некуда — записи ещё нет. Те же
        // грабли, что с рассылкой событий подписчикам до коммита.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                worker.submit(() -> {
                    if (ORACLE.equals(kind)) {
                        search(runId);
                    } else {
                        play(runId);
                    }
                });
            }
        });
        log.info("Балансовый прогон {}: {} партий по {} ходов, империй {}",
                runId, request.games(), request.turns(), request.empires());
        return toDto(entity);
    }

    /**
     * Проверяемая связка — п. 2.14 этапа 2: две стороны, которые прогон подсадит в сборки
     * нарочно.
     * <p>
     * Отказ приходит НА ЗАКАЗЕ, а не через час игры пустыми расами. Проверяется всё, из-за
     * чего связка окажется неизмеримой: сторон должно быть ровно две, они должны быть в
     * справочнике, уживаться друг с другом (одна группа-переключатель или взаимный запрет
     * — и четверть «обе сразу» осталась бы пустой) и умещаться в бюджет со сборкой вокруг.
     */
    private List<String> combination(BalanceRunRequest request, long seed) {
        List<String> combination = request.combination() == null ? List.<String>of()
                : request.combination().stream()
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (combination.isEmpty()) {
            return List.of();
        }
        if (combination.size() > 3) {
            throw new ConflictException("balance.plantSize", combination.size());
        }
        combination.forEach(raceTraits::require);
        // Собираемость проверяется на ПОЛНОМ бюджете: если подсаженное не влезает даже в
        // него, доля «все сразу» будет пустой, и мерить окажется нечего.
        if (builds.build(raceTraits.picksBudget(), raceTraits.antiPicksBudget(),
                new Random(seed), combination, List.of()).isEmpty()) {
            throw new ConflictException(combination.size() == 1
                    ? "balance.plantOneImpossible"
                    : "balance.plantManyImpossible");
        }
        return combination;
    }

    /**
     * Что прогон ответил о проверяемой связке — в память связок (п. 2.16 этапа 2).
     * <p>
     * Связка, которую проверяли нарочно, заводится в памяти сама, если её там ещё не было:
     * иначе проверенное и запомненное разъезжаются, а разъехавшись — перепроверяются по
     * кругу.
     */
    @Transactional("historyTransactionManager")
    public void remember(UUID runId, List<String> combination,
                         BalanceVerdictRules.Assessment assessment) {
        // Одна подсаженная сторона — не связка, и в памяти связок ей не место: ответ про
        // неё лежит в приговорах ценам, а не в таблице пар.
        if (combination.size() < 2) {
            return;
        }
        BalanceVerdictRules.Synergy answer = assessment.synergies().stream()
                .filter(one -> one.members().size() == combination.size()
                        && one.members().containsAll(combination))
                .findFirst()
                .orElse(null);
        BalanceCombinationEntity known = combinations.findAllByOrderByCreatedAtDesc().stream()
                .filter(one -> same(read(one.getTraits(), new TypeReference<List<String>>() { }),
                        combination))
                .findFirst()
                .orElseGet(() -> {
                    BalanceCombinationEntity fresh = new BalanceCombinationEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setCreatedAt(OffsetDateTime.now());
                    fresh.setTraits(write(combination));
                    fresh.setNote("Подсажена прогоном");
                    return fresh;
                });
        known.setCheckedAt(OffsetDateTime.now());
        known.setRunId(runId);
        // Связка без ответа — это не сбой: у неё могло не набраться носителей, и «носителей
        // не набралось» честнее пустого места.
        known.setVerdict(answer == null ? "NOT_MEASURED" : answer.verdict().name());
        known.setExtra(answer == null ? null : answer.extra());
        known.setError(answer == null ? null : answer.error());
        known.setCarriers(answer == null ? null : answer.pairs());
        combinations.save(known);
    }

    /** Связка — это НАБОР сторон: порядок в ней ничего не значит. */
    private boolean same(List<String> one, List<String> other) {
        return one.size() == other.size() && one.containsAll(other);
    }

    /** Связки, которые стоит проверить, и что о них известно. */
    public List<BalanceCombinationDto> combinations() {
        return combinations.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    /** Запомнить связку: что проверяем и зачем. */
    @Transactional("historyTransactionManager")
    public BalanceCombinationDto remember(BalanceCombinationDto.Request request) {
        List<String> traits = request.traits() == null ? List.<String>of()
                : request.traits().stream()
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (traits.size() < 2 || traits.size() > 3) {
            throw new ConflictException("balance.comboSize", traits.size());
        }
        traits.forEach(raceTraits::require);
        boolean already = combinations.findAllByOrderByCreatedAtDesc().stream()
                .anyMatch(one -> same(read(one.getTraits(), new TypeReference<List<String>>() { }),
                        traits));
        if (already) {
            throw new ConflictException("balance.comboExists");
        }
        BalanceCombinationEntity entity = new BalanceCombinationEntity();
        entity.setId(UUID.randomUUID());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setTraits(write(traits));
        entity.setNote(request.note());
        combinations.save(entity);
        return toDto(entity);
    }

    @Transactional("historyTransactionManager")
    public void forget(UUID id) {
        combinations.deleteById(id);
    }

    private BalanceCombinationDto toDto(BalanceCombinationEntity entity) {
        List<String> traits = read(entity.getTraits(), new TypeReference<List<String>>() { });
        String label = entity.getVerdict() == null ? null : switch (entity.getVerdict()) {
            case "SYNERGY" -> messages.label(BalanceVerdictRules.Pairing.SYNERGY);
            case "ANTI" -> messages.label(BalanceVerdictRules.Pairing.ANTI);
            case "PLAIN" -> messages.label(BalanceVerdictRules.Pairing.PLAIN);
            default -> "носителей не набралось";
        };
        return new BalanceCombinationDto(entity.getId(), traits,
                traits.stream().map(this::name).toList(),
                entity.getNote(), entity.getCreatedAt(), entity.getCheckedAt(),
                entity.getRunId(), entity.getVerdict(), label,
                entity.getExtra(), entity.getError(), entity.getCarriers());
    }

    /** Род прогона: замер по умолчанию — так заказывали до появления оракула. */
    private String kind(BalanceRunRequest request) {
        if (request.kind() == null || request.kind().isBlank()) {
            return MEASURE;
        }
        if (!MEASURE.equals(request.kind()) && !ORACLE.equals(request.kind())) {
            throw new ConflictException("balance.unknownKind", request.kind());
        }
        return request.kind();
    }

    /**
     * Поиск сильнейших сборок — этап 3.
     * <p>
     * Идёт тем же фоновым потоком, что и замер, и по тем же правилам: один прогон за раз,
     * партии удаляются за собой, отказ отмечается в записи. Разница только в вопросе.
     */
    private void search(UUID runId) {
        try {
            BalanceRunEntity run = repository.findById(runId).orElseThrow();
            BalanceOracleRules.Search found =
                    oracle.search(run, run.getPopulation(), new AtomicInteger());
            progress.searched(runId, oracle.write(found));
            log.info("Оракул сборок {} закончен: поколений {}, сборок в ладдере {}, разрыв {}",
                    runId, found.generations(), found.ladder().size(), found.gap());
        } catch (RuntimeException failure) {
            log.error("Оракул сборок {} оборвался", runId, failure);
            progress.failed(runId, failure.getMessage());
        }
    }

    /** Цены сторон на миг запуска — «код: очки». */
    private Map<String, Integer> prices() {
        Map<String, Integer> prices = new LinkedHashMap<>();
        raceTraits.groups().forEach(group ->
                group.options().forEach(trait -> prices.put(trait.code(), trait.picks())));
        return prices;
    }

    /**
     * Чем играет каждая империя — ЗАМЫСЕЛ мест, а не готовые сборки.
     * <p>
     * Место с выбранной готовой расой играет ею всю дорогу. Место без выбора получает
     * случайную сборку, и <b>свою на каждую партию</b>: шесть сборок на сто двадцать
     * партий — это шесть наблюдений, а не сто двадцать, и стороны внутри одной сборки
     * становятся неразличимы совсем. Первый прогон это и показал: у разных сторон выходила
     * одна и та же сила с ошибкой в тысячи пунктов.
     */
    private List<Slot> slots(BalanceRunRequest request, long seed) {
        List<BalanceRunRequest.RaceSlot> asked = request.races() == null ? List.of() : request.races();
        List<Slot> slots = new ArrayList<>();
        for (int slot = 0; slot < request.empires(); slot++) {
            BalanceRunRequest.RaceSlot want = slot < asked.size() ? asked.get(slot) : null;
            if (want != null && want.raceCode() != null && !want.raceCode().isBlank()) {
                if (raceTraits.raceTraits(want.raceCode()).isEmpty()) {
                    throw new ConflictException("balance.raceNotFound", want.raceCode());
                }
                slots.add(new Slot(slot, want.raceCode(), null));
                continue;
            }
            Integer budget = want == null ? null : want.budget();
            if (budget != null && (budget < 0 || budget > raceTraits.picksBudget())) {
                throw new ConflictException("balance.budgetRange", raceTraits.picksBudget(), budget);
            }
            // Заданный бюджет сразу проверяем на собираемость: отказ на заказе понятнее,
            // чем прогон, который час играл пустыми расами. Пустой бюджет значит «свой на
            // каждую партию» — такой проверять нечего, жребий возьмёт собираемый.
            if (budget != null
                    && builds.build(budget, raceTraits.antiPicksBudget(), new Random(seed)).isEmpty()) {
                throw new ConflictException("balance.budgetImpossible", budget);
            }
            slots.add(new Slot(slot, null, budget));
        }
        return slots;
    }

    /**
     * Сборки для одной партии: готовые расы как есть, случайные — заново на каждую партию.
     * <p>
     * Жребий выводится из зерна прогона и номера партии, а не из общего генератора: прогон
     * с тем же зерном обязан повториться целиком, как и партия.
     */
    private List<BalanceGameRunner.Design> designs(List<Slot> slots, long seed, int index,
                                 List<String> combination) {
        Random random = new Random(seed * 1_000_003L + index);
        List<BalanceGameRunner.Design> designs = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.raceCode() != null) {
                List<String> traits = raceTraits.raceTraits(slot.raceCode());
                designs.add(new BalanceGameRunner.Design(slot.slot(), slot.raceCode(), traits, games.cost(traits)));
                continue;
            }
            // Бюджет места без выбора — свой на каждую партию. Один и тот же бюджет у
            // всех сборок делает матрицу замера вырожденной по построению: каждая сборка
            // стоит ровно его, и взвешенная ценами сумма столбцов оказывается константой.
            // Разные бюджеты эту зависимость и разрывают.
            int budget = slot.budget() == null
                    ? random.nextInt(raceTraits.picksBudget() + 1)
                    : slot.budget();
            // Слабости продаются до потолка анти-выбора — п. 2.1 этапа 2: без них
            // минусовая половина таблицы не попадает в замер ни разу.
            //
            // Проверяемая связка подсаживается ПО ЖРЕБИЮ НА КАЖДУЮ СТОРОНУ — п. 2.14
            // этапа 2: у пары выходят четыре четверти (обе, только первая, только вторая,
            // ни одной), у тройки — восемь восьмых. Иначе связку не отделить от её частей
            // ВОВСЕ: если сборки несут либо все стороны, либо ни одной, столбец связки
            // совпадает со столбцами её частей, и вес у такого набора не определён ни один.
            // Сборки с ЧАСТЬЮ связки это совпадение и разрывают — они здесь не остаток, а
            // главное условие измеримости.
            List<String> required = new ArrayList<>();
            List<String> forbidden = new ArrayList<>();
            for (String side : combination) {
                (random.nextBoolean() ? required : forbidden).add(side);
            }
            List<String> traits = builds.build(budget, raceTraits.antiPicksBudget(), random,
                    required, forbidden);
            designs.add(new BalanceGameRunner.Design(slot.slot(), "Сборка на " + budget, traits, games.cost(traits)));
        }
        return designs;
    }

    /**
     * Сам прогон: партия за партией, каждая — завести, доиграть, снять замер, удалить.
     * <p>
     * Партии удаляются сразу: копить их в базе нельзя, на этом уже погорели — в базе
     * набралось восемь с половиной сотен брошенных партий.
     */
    private void play(UUID runId) {
        List<BalanceVerdictRules.Empire> measured = new ArrayList<>();
        try {
            BalanceRunEntity run = repository.findById(runId).orElseThrow();
            List<Slot> slots = read(run.getRaceDesigns(), new TypeReference<>() { });
            Map<String, Integer> prices = read(run.getTraitCosts(), new TypeReference<>() { });
            List<String> combination = run.getCombinationTraits() == null ? List.<String>of()
                    : read(run.getCombinationTraits(), new TypeReference<>() { });

            AtomicInteger done = new AtomicInteger();
            // Пул делается по НАИБОЛЬШЕМУ пределу, а ограничивает не он, а счётчик
            // BalanceLoad: переразмерить пул посреди прогона нельзя, счётчик — можно.
            ExecutorService pool = Executors.newFixedThreadPool(load.poolSize(), runnable -> {
                Thread thread = new Thread(runnable, "balance-game");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<List<BalanceVerdictRules.Empire>>> playing = new ArrayList<>();
                for (int index = 0; index < run.getGames(); index++) {
                    // Сборки свои на каждую партию: иначе наблюдений будет столько,
                    // сколько сборок, и стороны внутри одной сборки не разделить вовсе.
                    // Переставляются они ещё и по местам — стартовый угол главный источник
                    // разброса, и закреплённая за местом сборка забирала бы его удачу себе
                    // (п. 3 плана, латинский квадрат).
                    int game = index;
                    List<BalanceGameRunner.Design> designs =
                            rotate(designs(slots, run.getSeed(), game, combination), game);
                    playing.add(pool.submit(() -> {
                        // Пауза спрашивается ПЕРЕД партией, а не внутри неё: начатую
                        // партию бросать нельзя, её замер пропал бы.
                        awaitResume(runId);
                        // Место под партию — п. 2 этапа 2: ночью их больше, днём меньше,
                        // и предел спрашивается ПЕРЕД партией, а не при старте сервера.
                        load.take();
                        List<BalanceVerdictRules.Empire> one;
                        try {
                            one = playOne(run, designs, run.getSeed() + game);
                        } finally {
                            load.release();
                        }
                        progress.played(runId, done.incrementAndGet());
                        return one;
                    }));
                }
                for (Future<List<BalanceVerdictRules.Empire>> future : playing) {
                    measured.addAll(future.get());
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Прогон прерван", stopped);
            } catch (java.util.concurrent.ExecutionException broken) {
                throw new IllegalStateException(broken.getCause() == null
                        ? broken.getMessage()
                        : broken.getCause().getMessage(), broken);
            } finally {
                pool.shutdownNow();
            }

            BalanceVerdictRules.Assessment assessment =
                    verdictRules.assess(measured, prices, combination);
            progress.finished(runId, assessment, measured);
            remember(runId, combination, assessment);
            log.info("Балансовый прогон {} закончен: {} замеров, оценено сторон {}",
                    runId, measured.size(), assessment.measured());
        } catch (RuntimeException failure) {
            log.error("Балансовый прогон {} оборвался", runId, failure);
            progress.failed(runId, failure.getMessage());
        } finally {
            paused.remove(runId);
        }
    }

    /**
     * Ставит прогон на паузу — п. 2 этапа 2.
     * <p>
     * Отвечает сразу, а вступает в силу, когда доиграются начатые партии: см. {@link #PAUSED}.
     */
    public void pause(UUID runId) {
        if (paused.add(runId)) {
            progress.paused(runId);
            log.info("Балансовый прогон {} поставлен на паузу", runId);
        }
    }

    /** Снимает паузу и будит потоки партий. */
    public void resume(UUID runId) {
        if (paused.remove(runId)) {
            progress.resumed(runId);
            log.info("Балансовый прогон {} продолжен", runId);
        }
        synchronized (paused) {
            paused.notifyAll();
        }
    }

    /** Идёт ли прогон прямо сейчас: приостановленный не считается. */
    public Boolean isPaused(UUID runId) {
        return paused.contains(runId);
    }

    /**
     * Держит поток партии, пока прогон на паузе.
     * <p>
     * Ждём на общем значке, а не на отдельном у каждого прогона: прогоны исполняются по
     * одному, и второго ждущего не бывает.
     */
    private void awaitResume(UUID runId) {
        synchronized (paused) {
            while (paused.contains(runId)) {
                try {
                    paused.wait(1000);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Прогон прерван на паузе", stopped);
                }
            }
        }
    }

    /** Те же сборки, сдвинутые по кругу: место {@code i} получает сборку {@code i + shift}. */
    private List<BalanceGameRunner.Design> rotate(List<BalanceGameRunner.Design> designs, int shift) {
        List<BalanceGameRunner.Design> rotated = new ArrayList<>(designs.size());
        for (int slot = 0; slot < designs.size(); slot++) {
            BalanceGameRunner.Design design = designs.get((slot + shift) % designs.size());
            rotated.add(new BalanceGameRunner.Design(slot, design.name(), design.traits(), design.budget()));
        }
        return rotated;
    }

    /** Одна партия прогона: замер на империю — доля выработки, которую она себе взяла. */
    /** Одна партия прогона: условия берутся из его записи. */
    private List<BalanceVerdictRules.Empire> playOne(BalanceRunEntity run,
                                                     List<BalanceGameRunner.Design> designs,
                                                     long seed) {
        return games.play(run.getGalaxySize(), run.getEmpires(), run.getTurns(), designs, seed);
    }

    private GalaxySize galaxySize(BalanceRunRequest request) {
        if (request.galaxySize() == null || request.galaxySize().isBlank()) {
            return GalaxySize.SMALL;
        }
        try {
            return GalaxySize.valueOf(request.galaxySize());
        } catch (IllegalArgumentException unknown) {
            throw new ConflictException("balance.unknownGalaxySize", request.galaxySize());
        }
    }

    private BalanceRunDto toDto(BalanceRunEntity run) {
        List<Slot> slots = read(run.getRaceDesigns(), new TypeReference<>() { });
        Map<String, Integer> costs = read(run.getTraitCosts(), new TypeReference<>() { });
        BalanceVerdictRules.Assessment assessment = run.getResult() == null
                ? null
                : read(run.getResult(), new TypeReference<>() { });

        List<BalanceRunDto.JudgementDto> judgements = assessment == null ? List.of()
                : assessment.judgements().stream()
                .map(one -> new BalanceRunDto.JudgementDto(
                        one.code(), name(one.code()), one.price(), one.takers(),
                        one.strength(), one.error(),
                        one.production(), one.research(), one.espionage(),
                        one.money(), one.military(), one.ground(), one.technology(),
                        one.fairPrice(), one.recommendedPrice(),
                        one.verdict().name(), messages.label(one.verdict())))
                .toList();

        // Связок нет у прогонов, посчитанных до того, как их научились искать: слепок
        // старой версии — единственное место, где это поле и правда бывает пустым.
        //
        // И пустым бывает не только само поле, но и СОСТАВ связки внутри него: пока связка
        // была парой, в слепке лежали «первая» и «вторая» стороны, а не набор. Прочитанная
        // нынешним разбором, такая связка приходит с пустым составом — и валила весь список
        // прогонов, а не одну строку. Старые связки поэтому пропускаются: пересудить прогон
        // («пересудить нынешними правилами») и получить их в нынешнем виде дешевле, чем
        // держать в приборе второй разбор для отменённой формы записи.
        List<BalanceRunDto.SynergyDto> synergies =
                assessment == null || assessment.synergies() == null ? List.of()
                : assessment.synergies().stream()
                .filter(one -> one.members() != null && !one.members().isEmpty())
                .map(one -> new BalanceRunDto.SynergyDto(
                        one.members(), one.members().stream().map(this::name).toList(),
                        one.pairs(), one.price(), one.extra(), one.error(), one.together(),
                        one.verdict().name(), messages.label(one.verdict())))
                .toList();

        return new BalanceRunDto(
                run.getId(), run.getCreatedAt(), run.getFinishedAt(), run.getStatus(),
                run.getGalaxySize(), run.getEmpires(), run.getGames(), run.getTurns(),
                run.getPlayed(), run.getSeed(),
                slots.stream()
                        .map(this::toSlotDto)
                        .toList(),
                costs,
                assessment == null ? null : assessment.pointValue(),
                // Невязка выводится ИЗ ПРИГОВОРОВ, а не хранится рядом с ними: так она
                // появляется и у прогонов, сыгранных до её появления, — а слепок прогона
                // остаётся тем же, каким был записан.
                assessment == null ? null
                        : verdictRules.residual(assessment.judgements(), assessment.pointValue()),
                assessment == null ? null : assessment.measured(),
                judgements,
                synergies,
                run.getCombinationTraits() == null ? List.of()
                        : read(run.getCombinationTraits(), new TypeReference<List<String>>() { }),
                run.getKind(),
                run.getPopulation(),
                ladder(run),
                run.getFailure());
    }

    /** Ладдер оракула для пульта: коды сторон разворачиваются в названия. */
    private BalanceRunDto.OracleDto ladder(BalanceRunEntity run) {
        if (run.getLadder() == null) {
            return null;
        }
        BalanceOracleRules.Search found = oracle.read(run.getLadder());
        // Списки старого ладдера бывают пусты, и это не сбой, а слепок прежней версии: поле
        // «поиск не пробовал» завелось позже самих прогонов. Тот же случай, что со связками
        // старой формы, — и он уже валил ВЕСЬ список прогонов, а не одну строку. Правило:
        // всё, что читается из слепка, читается с оглядкой на то, что этого поля там нет.
        List<String> dead = found.deadTraits() == null ? List.<String>of() : found.deadTraits();
        List<String> unseen = found.unseenTraits() == null ? List.<String>of() : found.unseenTraits();
        return new BalanceRunDto.OracleDto(
                found.ladder().stream()
                        .map(one -> new BalanceRunDto.OracleBuildDto(
                                one.traits(), one.traits().stream().map(this::name).toList(),
                                one.budget(), one.games(), one.strength(), one.error()))
                        .toList(),
                found.best(), found.median(), found.gap(), found.dominated(),
                dead, dead.stream().map(this::name).toList(),
                unseen, unseen.stream().map(this::name).toList(),
                found.generations());
    }

    /** Название стороны для пульта: код в таблице читается плохо. */
    private String name(String code) {
        try {
            return raceTraits.require(code).name();
        } catch (NotFoundException gone) {
            // Сторона, которой в справочнике уже нет: прогон её мерил, а файл с тех пор
            // правили. Приговор всё равно показываем — кодом.
            return code;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new IllegalStateException("Не удалось записать прогон балансировки", broken);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
            throw new IllegalStateException("Не удалось прочитать прогон балансировки", broken);
        }
    }

    /** Место прогона для пульта: готовая раса со своими сторонами или бюджет сборки. */
    private BalanceRunDto.RaceSlotDto toSlotDto(Slot slot) {
        if (slot.raceCode() == null) {
            return new BalanceRunDto.RaceSlotDto(slot.slot(),
                    slot.budget() == null
                            ? "Случайная сборка, бюджет свой на каждую партию"
                            : "Случайная сборка на " + slot.budget(),
                    List.of(), slot.budget());
        }
        List<String> traits = raceTraits.raceTraits(slot.raceCode());
        return new BalanceRunDto.RaceSlotDto(slot.slot(), slot.raceCode(), traits, games.cost(traits));
    }

    /** Чем играет одна империя прогона: имя для показа, стороны и их цена. */
    /**
     * Замысел места: готовая раса или случайная сборка на бюджет.
     * <p>
     * В записи прогона хранится именно он, а не готовые стороны: случайная сборка
     * переигрывается на каждую партию, и запомненный однажды набор превратил бы сто
     * двадцать партий в шесть наблюдений.
     */
    private record Slot(Integer slot, String raceCode, Integer budget) {
    }
}
