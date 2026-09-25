package com.moo3.server.service;

import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.dto.AcquiredTechnologyDto;
import com.moo3.server.dto.ChooseResearchRequest;
import com.moo3.server.dto.ResearchCategoryDto;
import com.moo3.server.dto.PlayerResearchDto;
import com.moo3.server.dto.ResearchLevelDto;
import com.moo3.server.dto.ResearchOptionDto;
import com.moo3.server.dto.ResearchTreeDto;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Исследования игрока — п. 9.
 * <p>
 * Дерево MOO II: восемь разделов без взаимных зависимостей, раздел — прямая
 * последовательность уровней. Уровни раздела изучаются строго по порядку, и на уровне
 * исследуется одна технология из предложенных; исключение — общие уровни дерева,
 * где технологии выдаются все сразу.
 * <p>
 * Империя исследует одну технологию за раз: весь доход очков идёт в текущую цель,
 * а прорыв считает {@link ResearchRules} по стоимости уровня из описания дерева.
 */
@Service
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    private final ResearchCatalog researchCatalog;
    private final ResearchRules researchRules;
    private final PopulationCalculator populationCalculator;
    private final PlanetRepository planetRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final ColonyService colonyService;
    private final LeaderBonusService leaderBonuses;
    private final RaceService raceService;
    private final GameAccess gameAccess;
    /**
     * Проекты кораблей — п. 8: изученное меняет то, что игра предлагает строить, и шесть
     * ячеек дизайна она держит под нынешний уровень технологий. Зависимость односторонняя:
     * дизайн об исследованиях ничего не знает, и кольца не выходит.
     */
    private final ShipDesignService shipDesignService;
    private final GovernmentService governmentService;

    public ResearchService(ResearchCatalog researchCatalog,
                           ResearchRules researchRules,
                           PopulationCalculator populationCalculator,
                           PlanetRepository planetRepository,
                           PlayerTechnologyRepository playerTechnologyRepository,
                           ColonyService colonyService,
                           LeaderBonusService leaderBonuses,
                           RaceService raceService,
                           GameAccess gameAccess,
                           ShipDesignService shipDesignService,
                           GovernmentService governmentService) {
        this.researchCatalog = researchCatalog;
        this.researchRules = researchRules;
        this.populationCalculator = populationCalculator;
        this.planetRepository = planetRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.colonyService = colonyService;
        this.leaderBonuses = leaderBonuses;
        this.raceService = raceService;
        this.gameAccess = gameAccess;
        this.shipDesignService = shipDesignService;
        this.governmentService = governmentService;
    }

    /** Состояние исследований участника партии — точка входа для контроллера. */
    @Transactional(readOnly = true)
    public PlayerResearchDto state(UUID gameId, String accessToken) {
        return state(gameAccess.requirePlayer(gameAccess.requireGame(gameId), accessToken));
    }

    /** Выбор технологии участником идущей партии — точка входа для контроллера. */
    @Transactional
    public PlayerResearchDto choose(UUID gameId, ChooseResearchRequest request) {
        return choose(gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken()), request);
    }

    /** Состояние исследований игрока: цель, вложенные очки и изученное. */
    @Transactional(readOnly = true)
    public PlayerResearchDto state(PlayerEntity player) {
        List<AcquiredTechnologyDto> acquired = acquired(player);
        Integer perTurn = researchPerTurn(player);
        // Изобретательность отдаётся экрану, а не выводится им из особенностей расы:
        // правило «уровень целиком» живёт здесь же, в grant, и разъехаться им нельзя.
        Boolean creative = raceService.effects(player).creative();

        if (player.getResearchOptionCode() == null) {
            return new PlayerResearchDto(null, null, null, null, null,
                    player.getResearchPoints(), perTurn, null, null, creative, acquired);
        }

        ResearchLevelDto level = researchCatalog.level(
                player.getResearchCategoryCode(), player.getResearchLevelOrder());
        ResearchOptionDto option = researchCatalog.option(
                player.getResearchCategoryCode(), player.getResearchLevelOrder(), player.getResearchOptionCode());

        return new PlayerResearchDto(
                player.getResearchCategoryCode(),
                player.getResearchLevelOrder(),
                option.code(),
                option.name(),
                level.cost(),
                player.getResearchPoints(),
                perTurn,
                researchRules.remainingToBaseCost(level.cost(), player.getResearchPoints()),
                researchRules.breakthroughPercent(level.cost(), player.getResearchPoints()),
                creative,
                acquired);
    }

    /**
     * Смена цели исследования.
     * <p>
     * Вложенные очки привязаны к проекту, а не копятся в общем котле, поэтому переход
     * на другую технологию обнуляет их. Повторный выбор той же технологии прогресса
     * не теряет: цель не менялась.
     */
    @Transactional
    public PlayerResearchDto choose(PlayerEntity player, ChooseResearchRequest request) {
        requireNextLevel(player, request.categoryCode(), request.levelOrder());
        ResearchOptionDto option = requestedOption(player, request);
        governmentService.requireOwnUpgrade(player, option.code());

        Boolean sameTarget = request.categoryCode().equals(player.getResearchCategoryCode())
                && request.levelOrder().equals(player.getResearchLevelOrder())
                && option.code().equals(player.getResearchOptionCode());
        if (!sameTarget) {
            player.setResearchCategoryCode(request.categoryCode());
            player.setResearchLevelOrder(request.levelOrder());
            player.setResearchOptionCode(option.code());
            player.setResearchPoints(0);
            log.info("Игрок {} исследует {} ({}, уровень {})",
                    player.getName(), option.name(), request.categoryCode(), request.levelOrder());
        }
        return state(player);
    }

    /**
     * Технология, в которую пойдут очки — п. 9.
     * <p>
     * Обычно это та, которую выбрал игрок. Неизобретательной расе (п. 7) выбирать не
     * дают: за неё выбирает случай, и присланный код на уровне игнорируется. Уровень
     * при этом остаётся её собственным — MOO II отнимает у таких рас выбор технологии,
     * а не право решать, что изучать дальше.
     */
    private ResearchOptionDto requestedOption(PlayerEntity player, ChooseResearchRequest request) {
        List<ResearchOptionDto> options = researchCatalog
                .level(request.categoryCode(), request.levelOrder())
                .options();
        if (Boolean.TRUE.equals(raceService.effects(player).uncreative()) && options.size() > 1) {
            // Случай привязан к игроку и уровню, а не к часам: иначе повторный выбор того
            // же уровня каждый раз давал бы другую технологию, и обнулял бы вложенное.
            int index = Math.floorMod(
                    Objects.hash(player.getId(), request.categoryCode(), request.levelOrder()),
                    options.size());
            return options.get(index);
        }
        return researchCatalog.option(
                request.categoryCode(), request.levelOrder(), request.optionCode());
    }

    /**
     * Доход очков исследований за ход — п. 9.
     * <p>
     * Очки дают учёные колоний игрока — п. 4.1: фермеры и рабочие заняты едой и
     * производством. Лаборатории и прочие научные здания прибавляют к этому своё — п. 10.
     */
    public Integer researchPerTurn(PlayerEntity player) {
        List<PlanetEntity> colonies = planetRepository.findAllByOwnerPlayerId(player.getId());
        return researchPerTurn(player, colonies, colonyService.context(colonies));
    }

    /**
     * То же, но по уже загруженным колониям хода — п. 11.1.
     * <p>
     * Конец хода вычитывает галактику один раз и передаёт её фазам контекстом
     * ({@link TurnContext}). Своя выборка на каждого игрока обходилась дорого: на партии
     * в восемь империй это восемь выборок колоний и восемь сборок контекста колоний за
     * ход — а контекст поднимает изученное, постройки, расу, договоры и проекты кораблей
     * владельца. Фаза передаёт готовое, и лишних запросов не остаётся.
     */
    public Integer researchPerTurn(PlayerEntity player, List<PlanetEntity> colonies,
                                   ColonyService.ColonyContext context) {
        Integer fromColonies = colonies.stream()
                .filter(planet -> player.getId().equals(planet.getOwnerPlayerId()))
                // Учёный («Science Leader») поднимает выработку учёных своей системы — п. 6.
                .mapToInt(planet -> context.withLeader(populationCalculator.research(
                        colonyService.jobs(planet).scientists(),
                        context.effects(planet)), planet, "SCIENCE"))
                .sum();
        // «Исследователь» даёт твёрдую прибавку всей империи, где бы он ни находился, —
        // в оригинале она работает и из офицерского резерва.
        return fromColonies + leaderBonuses.of(player.getId()).empireValue("RESEARCHER");
    }

    /**
     * Ход исследований игрока — фаза конца хода.
     * <p>
     * Порядок такой: сначала начисляются очки, потом проверяется прорыв, — поэтому доход
     * текущего хода участвует в прорыве этого же хода.
     *
     * @param completedTurn ход, который завершается: им помечается изученная технология
     * @return изученные на этом ходу технологии; пусто — прорыва не было
     */
    @Transactional
    public List<AcquiredTechnologyDto> advance(PlayerEntity player, Integer completedTurn,
                                               RandomGenerator random,
                                               List<PlanetEntity> colonies,
                                               ColonyService.ColonyContext colonyContext) {
        if (player.getResearchOptionCode() == null) {
            // Очки без цели девать некуда: в MOO II исследование идёт только в выбранный проект.
            return List.of();
        }

        ResearchLevelDto level = researchCatalog.level(
                player.getResearchCategoryCode(), player.getResearchLevelOrder());
        player.setResearchPoints(player.getResearchPoints()
                + researchPerTurn(player, colonies, colonyContext));

        if (!Boolean.TRUE.equals(researchRules.breakthrough(
                level.cost(), player.getResearchPoints(), random))) {
            return List.of();
        }

        List<AcquiredTechnologyDto> gained = grant(player, level, completedTurn);
        // Остаток очков сверх нужного пропадает, а цель освобождается под следующий выбор.
        player.setResearchCategoryCode(null);
        player.setResearchLevelOrder(null);
        player.setResearchOptionCode(null);
        player.setResearchPoints(0);

        return gained;
    }

    /**
     * Технологии, с которыми империя входит в партию, — п. 9.
     * <p>
     * В MOO II ни одна раса не начинает с пустой головой: три первых уровня дерева есть у
     * всех с первого хода, и они же перечислены в самом описании дерева
     * ({@code starting_techs.all_races}) — «Nuclear Fission (Power)», «Chemistry
     * (Chemistry)», «Physics (Physics)». Берутся они оттуда, а не списком кодов в коде:
     * дерево — данные, и второго свода стартовых технологий быть не должно. Сверка идёт
     * по названию уровня с разделом в скобках — ровно так эти строки в файле и написаны.
     * <p>
     * <b>Чем это было.</b> Список в файле лежал, но не выдавался никому: строка доезжала
     * до клиента справочной надписью, а в базе у игрока технологий не было ни одной. А
     * кораблестроение требует ДВУХ базовых уровней разом (Power — двигатель, Chemistry —
     * топливо, {@link ShipDesignRules#shipbuildingAvailable}), и империя ИИ, чьё
     * устремление не любит химию ({@link com.moo3.server.domain.enums.AiObjective}), не
     * бралась за неё никогда: колониальный корабль она изучала (Power, уровень 2), а
     * построить не могла — в списке стройки его не было вовсе. Измерено на зерне 777: за
     * 150 ходов восьми империй ШЕСТЬ перелётов, ноль застав, ни одного знакомства, ни
     * одной войны и 18 колоний на восьмерых — то есть родная звезда и одна колониальная
     * база у каждого. Прибор балансировки мерил при этом не силу рас, а запертую игру.
     *
     * @return что досталось империи; уже известное не выдаётся дважды
     */
    @Transactional
    public List<AcquiredTechnologyDto> grantStarting(PlayerEntity player, Integer turn) {
        List<AcquiredTechnologyDto> given = new ArrayList<>();
        // Какие уровни стартовые — решает справочник: он сверяет НАЗВАНИЯ, а названия
        // теперь переводятся, и сверка на языке запроса не нашла бы ни одного (п. 3.5).
        for (ResearchCatalog.StartingLevel starting : researchCatalog.startingLevels()) {
            ResearchLevelDto level = researchCatalog.level(starting.categoryCode(), starting.levelOrder());
            given.addAll(store(player, starting.categoryCode(), starting.levelOrder(),
                    level.options(), turn));
        }
        return given;
    }

    /**
     * Технологии по кодам, без исследования, — п. 6: их приносит с собой нанятый лидер.
     * <p>
     * Отличие от подарка ({@link #grantGift}) в том, что здесь известно <b>что именно</b>
     * даётся: у лидера в справочнике перечислены его технологии поимённо. Уже изученное
     * отбрасывается само ({@code store}), а <b>коды, которых нет в дереве, пропускаются</b>:
     * справочник лидеров живёт своей жизнью, и правка дерева не должна ронять наём.
     *
     * @return что досталось империи впервые
     */
    @Transactional
    public List<AcquiredTechnologyDto> grantByCode(PlayerEntity player, List<String> codes,
                                                   Integer turn) {
        List<AcquiredTechnologyDto> given = new ArrayList<>();
        for (ResearchCategoryDto category : researchCatalog.tree().categories()) {
            for (ResearchLevelDto level : category.levels()) {
                List<ResearchOptionDto> wanted = level.options().stream()
                        .filter(option -> codes.contains(option.code()))
                        .toList();
                if (!wanted.isEmpty()) {
                    given.addAll(store(player, category.code(), level.order(), wanted, turn));
                }
            }
        }
        return given;
    }

    /**
     * Технология в подарок, без исследования, — п. 11.1: находка древнего корабля или
     * тайный эксперимент из галактических событий.
     * <p>
     * Берётся ближайший неизученный уровень случайного раздела. Найденное <b>не трогает
     * текущую цель</b>: находка не заменяет работу учёных, а прибавляется к ней — в
     * MOO II древний корабль дарит технологию, а не сбивает исследование.
     *
     * @param wholeLevel выдать уровень целиком (тайный эксперимент открывает «поле»
     *                   технологий) или одну технологию с него (древний корабль)
     * @return что досталось; пусто — изучать больше нечего
     */
    @Transactional
    public List<AcquiredTechnologyDto> grantGift(PlayerEntity player, Integer turn,
                                                 RandomGenerator random, Boolean wholeLevel) {
        Map<String, Integer> next = nextLevelOrders(player);
        List<ResearchCategoryDto> categories = new ArrayList<>(researchCatalog.tree().categories());
        // Разделы перебираются вразнобой: иначе подарок всегда падал бы в первый по счёту.
        Collections.shuffle(categories, new Random(random.nextLong()));

        for (ResearchCategoryDto category : categories) {
            Integer order = next.getOrDefault(category.code(), 1);
            ResearchLevelDto level = category.levels().stream()
                    .filter(candidate -> candidate.order().equals(order))
                    .findFirst()
                    .orElse(null);
            if (level == null || level.options().isEmpty()) {
                continue;
            }
            /*
              Уровень, который империя как раз исследует, подарок обходит стороной. Иначе
              находка выдала бы ту самую технологию, к которой учёные уже подошли, и на
              прорыве игрок получил бы её второй раз — база такого не принимает, а ход
              падал с ошибкой. Заодно это честнее: подарок открывает новое, а не
              дублирует начатое.
            */
            if (category.code().equals(player.getResearchCategoryCode())
                    && level.order().equals(player.getResearchLevelOrder())) {
                continue;
            }

            // Общий уровень выдаётся целиком всегда — выбора там нет ни у кого (п. 9).
            List<ResearchOptionDto> options = Boolean.TRUE.equals(wholeLevel)
                    || Boolean.TRUE.equals(level.general())
                    ? level.options()
                    : List.of(level.options().get(random.nextInt(level.options().size())));
            return store(player, category.code(), level.order(), options, turn);
        }
        return List.of();
    }

    /**
     * Записывает изученное. На общем уровне дерева игрок получает все его технологии,
     * на обычном — только выбранную.
     */
    private List<AcquiredTechnologyDto> grant(PlayerEntity player, ResearchLevelDto level, Integer turn) {
        // Изобретательная раса (п. 7) забирает уровень целиком — ровно так же, как это
        // делает общий уровень дерева, где выбора нет ни у кого. В MOO II это и есть
        // Creative: одна цена — все технологии уровня.
        List<ResearchOptionDto> options = Boolean.TRUE.equals(level.general())
                || Boolean.TRUE.equals(raceService.effects(player).creative())
                ? level.options()
                : List.of(researchCatalog.option(
                        player.getResearchCategoryCode(),
                        player.getResearchLevelOrder(),
                        player.getResearchOptionCode()));

        return store(player, player.getResearchCategoryCode(), player.getResearchLevelOrder(),
                options, turn);
    }

    /**
     * Кладёт изученное игроку: один путь и для исследования, и для подарка.
     * <p>
     * Уже известное отбрасывается: одна и та же технология может прийти дважды — своим
     * исследованием и находкой из галактического события (п. 11.1), — а в базе она у
     * игрока одна. Раньше на этом падал весь ход.
     */
    private List<AcquiredTechnologyDto> store(PlayerEntity player, String categoryCode,
                                              Integer levelOrder, List<ResearchOptionDto> options,
                                              Integer turn) {
        Set<String> known = playerTechnologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
        List<PlayerTechnologyEntity> technologies = new ArrayList<>(options.size());
        for (ResearchOptionDto option : options) {
            if (known.contains(option.code())) {
                continue;
            }
            PlayerTechnologyEntity technology = new PlayerTechnologyEntity();
            technology.setPlayerId(player.getId());
            technology.setCategoryCode(categoryCode);
            technology.setLevelOrder(levelOrder);
            technology.setOptionCode(option.code());
            technology.setAcquiredTurn(turn);
            technologies.add(technology);
        }
        playerTechnologyRepository.saveAll(technologies);

        // Изученное меняет и то, что игра предлагает строить: шесть ячеек дизайна она
        // держит под нынешний уровень технологий — п. 8. Собранное игроком при этом не
        // трогается, а сам пересчёт редок: он идёт только тогда, когда технологии и правда
        // прибавились.
        if (!technologies.isEmpty()) {
            shipDesignService.ensureAutoDesigns(player.getGame().getId(), player, turn);
            // И платформы обороны — п. 8, п. 11: звёздная база с боевой станцией
            // вооружаются сами, по последнему изученному, без всякой перестройки.
            shipDesignService.ensurePlatformDesigns(player.getGame().getId(), player, turn);
            governmentService.upgradeAfterResearch(player,
                    technologies.stream().map(PlayerTechnologyEntity::getOptionCode).toList());
        }

        log.info("Игрок {} изучил на ходу {}: {}", player.getName(), turn,
                options.stream().map(ResearchOptionDto::name).toList());
        return technologies.stream()
                .map(technology -> new AcquiredTechnologyDto(
                        technology.getCategoryCode(),
                        technology.getLevelOrder(),
                        technology.getOptionCode(),
                        optionName(options, technology.getOptionCode()),
                        technology.getAcquiredTurn()))
                .toList();
    }

    private String optionName(List<ResearchOptionDto> options, String optionCode) {
        return options.stream()
                .filter(option -> option.code().equals(optionCode))
                .map(ResearchOptionDto::name)
                .findFirst()
                .orElse(optionCode);
    }

    /**
     * Выбор цели за ИИ — п. 9.
     * <p>
     * До этого ИИ не исследовал вовсе: цель ему никто не ставил, а без цели фаза
     * исследований возвращается ни с чем. Империи ИИ навсегда оставались с тем, с чем
     * начали, — без двигателей, топлива и зданий, а игроку было нечего у них выменивать
     * (п. 15): в списке обмена у соседа не находилось ни одной технологии.
     * <p>
     * <b>Выбор случаен, и это правило оригинала.</b> Про ИИ MOO II прямо сказано, что он
     * «делает до нелепости глупый и буквально случайный выбор технологий, неотличимый от
     * неизобретательной расы». Поэтому технология внутри уровня берётся жребием — тем же,
     * каким её берёт неизобретательная раса ({@link #requestedOption}).
     * <p>
     * <b>Раздел выбирает устремление правителя</b> ({@code AiObjective}) — п. 15: в MOO II
     * сосед представлен двумя словами, «агрессивный промышленник», и второе как раз о том,
     * куда он вкладывается. Экспансионист налегает на двигатели, технолог на науку,
     * милитарист на оружие. Если в излюбленных разделах изучать больше нечего, берётся
     * любой непройденный: стоять из-за пройденной ветки империя не должна.
     * <p>
     * <i>Реконструкция:</i> какие именно разделы тянет каждое устремление, оригинал
     * называет общими словами — соответствие расставлено в самом {@code AiObjective}.
     * <p>
     * Жребий берётся от переданного генератора, а он засеян партией, ходом и слотом
     * игрока: перезагруженное сохранение должно считаться так же, как считалось.
     *
     * @return {@code true} — цель поставлена; {@code false} — цель уже была или дерево
     *         пройдено до конца
     */
    @Transactional
    public Boolean autoChoose(PlayerEntity player, RandomGenerator random) {
        return autoChoose(player, random, List.of());
    }

    /**
     * Тот же выбор, но с нуждами империи впереди вкусов правителя — п. 15.
     * <p>
     * <b>Зачем.</b> Раздел, выбранный устремлением, — это вкус, а не план. Империя, у
     * которой нет колониального корабля, без него не расселится вовсе, и никакой любимый
     * раздел этого не исправит: прогоны показали, что четыре устремления из шести не
     * трогают Power ни разу за триста ходов и сидят на родной звезде до конца партии.
     * Поэтому сперва спрашивается, чего империи не хватает <i>для её же замысла</i>
     * ({@code AiEmpireService.wantedTechnologies}), и наука идёт туда, а вкус решает
     * только тогда, когда нужного нет или оно уже изучено.
     * <p>
     * Нужное берётся не прыжком: если технология лежит выше по своему разделу, империя
     * идёт по нему уровень за уровнем, а на нужном уровне берёт именно её — <b>кроме
     * неизобретательной расы</b> (п. 7): у той выбор внутри уровня отнимает случай, и
     * обходить это правило ИИ не должен, иначе он играет не по правилам игрока.
     *
     * @param wanted коды технологий по убыванию нужды; пустой список — как раньше
     */
    @Transactional
    public Boolean autoChoose(PlayerEntity player, RandomGenerator random, List<String> wanted) {
        if (player.getResearchOptionCode() != null) {
            return Boolean.FALSE;
        }

        Map<String, Integer> next = nextLevelOrders(player);
        if (chooseWanted(player, random, wanted, next)) {
            return Boolean.TRUE;
        }
        List<ResearchLevelDto> available = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        for (ResearchCategoryDto category : researchCatalog.tree().categories()) {
            Integer order = next.getOrDefault(category.code(), 1);
            category.levels().stream()
                    .filter(level -> level.order().equals(order))
                    .findFirst()
                    .ifPresent(level -> {
                        available.add(level);
                        categories.add(category.code());
                    });
        }
        if (available.isEmpty()) {
            return Boolean.FALSE;
        }

        /*
          Устремление правителя (п. 15) сужает выбор до излюбленных разделов: экспансионист
          и правда уходит в двигатели, технолог — в науку. Если в них изучать больше нечего,
          берётся что угодно: стоять из-за пройденной ветки империя не должна.
        */
        List<Integer> preferred = new ArrayList<>();
        if (player.getAiObjective() != null) {
            List<String> favourite = player.getAiObjective().getFavouriteCategories();
            for (int i = 0; i < categories.size(); i++) {
                if (favourite.contains(categories.get(i))) {
                    preferred.add(i);
                }
            }
        }

        int pick = preferred.isEmpty()
                ? random.nextInt(available.size())
                : preferred.get(random.nextInt(preferred.size()));
        ResearchLevelDto level = available.get(pick);
        ResearchOptionDto option = pickOption(level, raceService.effects(player).uncreative(), random);

        player.setResearchCategoryCode(categories.get(pick));
        player.setResearchLevelOrder(level.order());
        player.setResearchOptionCode(option.code());
        player.setResearchPoints(0);
        log.debug("ИИ {} выбрал исследование {} ({}, уровень {})",
                player.getName(), option.name(), categories.get(pick), level.order());
        return Boolean.TRUE;
    }

    /**
     * Ставит целью то, чего империи не хватает, — п. 15; {@code false} — нужного нет.
     * <p>
     * Технология ищется по справочнику: её раздел и уровень известны, и если раздел ещё
     * не дошёл до неё, изучается очередной его уровень — дорога к нужному и есть нужда.
     */
    private Boolean chooseWanted(PlayerEntity player, RandomGenerator random,
                                 List<String> wanted, Map<String, Integer> next) {
        if (wanted.isEmpty()) {
            return Boolean.FALSE;
        }
        Set<String> known = playerTechnologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
        Boolean uncreative = raceService.effects(player).uncreative();

        for (String code : wanted) {
            if (known.contains(code)) {
                continue;
            }
            ResearchCatalog.TechnologyPlace place;
            try {
                place = researchCatalog.place(code);
            } catch (NotFoundException absent) {
                // Справочник живёт своей жизнью: кода может не оказаться вовсе, и это не
                // повод ронять ход — просто идём к следующей нужде.
                continue;
            }

            Integer order = next.getOrDefault(place.categoryCode(), 1);
            if (order > place.levelOrder()) {
                continue;
            }

            ResearchLevelDto level = researchCatalog.level(place.categoryCode(), order);
            ResearchOptionDto option = order.equals(place.levelOrder())
                    && !Boolean.TRUE.equals(uncreative)
                    ? place.option()
                    : pickOption(level, uncreative, random);

            player.setResearchCategoryCode(place.categoryCode());
            player.setResearchLevelOrder(level.order());
            player.setResearchOptionCode(option.code());
            player.setResearchPoints(0);
            log.debug("ИИ {} взялся за нужное: {} ({}, уровень {})",
                    player.getName(), option.name(), place.categoryCode(), level.order());
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /**
     * Что империя ИИ берёт с уровня, на котором есть выбор, — п. 9 и п. 7.
     * <p>
     * <b>Обычная раса берёт лучшее, неизобретательная — что придётся.</b> Лучшее не
     * выдумывается здесь: у каждого уровня дерева есть список {@code recommended}
     * ({@code resources/Technologies/tech.json}, поле разбирается в
     * {@link ResearchCatalog}), и это ровно то, что берёт с уровня понимающий игрок —
     * автоматический завод на Advanced Construction, усиленный корпус на Advanced
     * Engineering. Отмечены такие технологии на всех 62 уровнях с выбором.
     * <p>
     * <b>Чем это было.</b> Здесь стоял случайный выбор ДЛЯ ВСЕХ, и неизобретательность
     * тем самым не отнимала у ИИ ничего: он и так не выбирал. Замер это и показал —
     * сторона за −4 очка меряется силой +0,75, то есть возвращает очки даром, а оракул
     * сборок ставил её в семь сборок верхушки из восьми. Правило MOO II при этом не
     * менялось: неизобретательная раса как не выбирала, так и не выбирает, — изменилось
     * то, что теперь есть чего лишиться.
     * <p>
     * Если рекомендованного на уровне нет (такого в дереве не осталось, но справочник
     * живёт своей жизнью), выбор снова случаен — ронять ход из-за данных нельзя.
     */
    private ResearchOptionDto pickOption(ResearchLevelDto level, Boolean uncreative,
                                         RandomGenerator random) {
        List<ResearchOptionDto> options = level.options();
        if (!Boolean.TRUE.equals(uncreative)) {
            List<ResearchOptionDto> best = options.stream()
                    .filter(option -> Boolean.TRUE.equals(option.recommended()))
                    .toList();
            if (!best.isEmpty()) {
                options = best;
            }
        }
        return options.get(random.nextInt(options.size()));
    }

    /**
     * Какой уровень изучается следующим в каждом разделе — по изученному игроку.
     * <p>
     * Одной выборкой на всё дерево: разделов восемь, и ходить за каждым по отдельности
     * значило бы читать одну и ту же таблицу восемь раз.
     */
    private Map<String, Integer> nextLevelOrders(PlayerEntity player) {
        Map<String, Integer> next = new HashMap<>();
        for (PlayerTechnologyEntity technology
                : playerTechnologyRepository.findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId())) {
            next.merge(technology.getCategoryCode(), technology.getLevelOrder() + 1, Math::max);
        }
        return next;
    }

    /**
     * Раздел проходится по порядку: перепрыгнуть через неизученный уровень нельзя.
     * Следующий уровень — тот, что идёт за самым верхним изученным в разделе.
     */
    private void requireNextLevel(PlayerEntity player, String categoryCode, Integer levelOrder) {
        Integer next = playerTechnologyRepository.findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId())
                .stream()
                .filter(technology -> technology.getCategoryCode().equals(categoryCode))
                .mapToInt(PlayerTechnologyEntity::getLevelOrder)
                .max()
                .orElse(0) + 1;
        if (!next.equals(levelOrder)) {
            throw new ConflictException("research.wrongLevel", categoryCode, next, levelOrder);
        }
    }

    /**
     * Изученное игроком. Названия берутся одним проходом по дереву, а не поиском на
     * каждую технологию: дерево читается с диска, и в списке изученного их десятки.
     */
    private List<AcquiredTechnologyDto> acquired(PlayerEntity player) {
        Map<String, String> namesByCode = researchCatalog.tree().categories().stream()
                .flatMap(category -> category.levels().stream())
                .flatMap(level -> level.options().stream())
                .collect(Collectors.toMap(ResearchOptionDto::code, ResearchOptionDto::name, (first, second) -> first));

        return playerTechnologyRepository.findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId())
                .stream()
                .map(technology -> new AcquiredTechnologyDto(
                        technology.getCategoryCode(),
                        technology.getLevelOrder(),
                        technology.getOptionCode(),
                        namesByCode.get(technology.getOptionCode()),
                        technology.getAcquiredTurn()))
                .toList();
    }

}
