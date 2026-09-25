package com.moo3.server.service;

import com.moo3.server.domain.Building;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.enums.ShipRole;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.service.stub.TurnPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Фаза производства и дохода в конце хода — п. 10.
 * <p>
 * Идёт после роста населения и до исследований, как в MOO II: там ход считает сначала
 * население, потом выработку, потом достраивает здания.
 * <p>
 * Производство колонии уходит в её проект. Здание накапливает единицы производства до
 * своей стоимости и достраивается; остаток переходит в следующую стройку, как накопленное
 * производство колонии в MOO II. Дома и товары не достраиваются никогда: их вклад уже
 * учтён — дома в приросте населения, товары в доходе колонии. Колониальная база
 * достраивается, но здания не оставляет: она поднимает на колонии флаг, по которому
 * игрок выбирает планету системы для заселения — п. 4.1. Так же устроен шпион (п. 13):
 * достроенный агент уходит империи, а не остаётся на планете.
 * <p>
 * Доход всех колоний игрока идёт в его казну. <b>Пустой стройки у колонии не остаётся:</b>
 * достроив последнее и не найдя ничего в очереди, она переходит на товары — в MOO II
 * стройка не бывает пустой, и произведённому всегда есть куда деться.
 */
@Service
public class ProductionPhase implements TurnPhase {

    private static final Logger log = LoggerFactory.getLogger(ProductionPhase.class);

    private final PlanetBuildingRepository planetBuildingRepository;
    private final ColonyService colonyService;
    private final EspionageService espionageService;
    private final FleetService fleetService;
    private final ShipDesignService shipDesignService;
    private final RaceService raceService;
    private final CommandRules commandRules;

    public ProductionPhase(PlanetBuildingRepository planetBuildingRepository,
                           ColonyService colonyService,
                           EspionageService espionageService,
                           FleetService fleetService,
                           ShipDesignService shipDesignService,
                           RaceService raceService,
                           CommandRules commandRules) {
        this.commandRules = commandRules;
        this.planetBuildingRepository = planetBuildingRepository;
        this.colonyService = colonyService;
        this.espionageService = espionageService;
        this.fleetService = fleetService;
        this.shipDesignService = shipDesignService;
        this.raceService = raceService;
    }

    @Override
    public Integer order() {
        return 7;
    }

    @Override
    public String name() {
        return "Производство и доход";
    }

    @Override
    public void apply(TurnContext context) {
        List<PlanetBuildingEntity> completed = new ArrayList<>();
        Map<UUID, Integer> incomeByPlayer = new HashMap<>();

        for (PlanetEntity planet : context.colonies()) {
            Integer income = colonyService.income(planet, context.colonyContext());
            incomeByPlayer.merge(planet.getOwnerPlayerId(), income, Integer::sum);
            reportLoss(planet, income, context);
            build(planet, context).ifPresent(completed::add);
            advanceQueue(planet, context);
        }

        planetBuildingRepository.saveAll(completed);
        payCommandPoints(context, incomeByPlayer);
        payTreasuries(context, incomeByPlayer);
        payDebts(context);
    }

    /**
     * Достроила — берётся следующее из очереди, а если очередь пуста, то товары (п. 10).
     * <p>
     * Любая законченная стройка гасит код проекта, и это единственная примета того, что
     * колония освободилась: дома и товары не кончаются никогда.
     * <p>
     * <b>Бесконечная стройка очередь не запирает.</b> Дома и товары, взятые из середины
     * очереди, не кончились бы никогда, и всё, что стоит за ними, колония не заложила бы
     * вовсе — так у игрока и вышло: за домами стояла колониальная база, и снять её со
     * второго места было нечем. Поэтому бесконечный проект берётся со стапеля только
     * последним; пока за ним в очереди есть что строить, он пропускается. Последним он
     * по-прежнему значит «а дальше — дома всегда», и это единственный его смысл в очереди.
     * <p>
     * <b>Пустой стройки у колонии не остаётся.</b> В MOO II колония всегда что-то строит,
     * и «строить нечего» там значит товары: производство идёт в казну, а не пропадает.
     * <p>
     * Остаток производства сверх стоимости уже лежит на колонии и уходит в новую стройку
     * сам: в MOO II накопленное при переходе не пропадает. Вложиться в неё колония
     * успеет со следующего хода — производство этого хода уже потрачено.
     * <p>
     * Очередь правится копией: у колонки свой преобразователь, и правку списка на месте
     * Hibernate не заметил бы (см. {@code ColonyService.enqueue}).
     */
    private void advanceQueue(PlanetEntity planet, TurnContext context) {
        if (planet.getProjectCode() != null) {
            return;
        }
        if (planet.getBuildQueue().isEmpty()) {
            /*
              Строить нечего — колония переходит на товары, как в MOO II, где стройка не
              бывает пустой вовсе. Пустая стройка молча съедала производство: колония
              работала, а в казну и в постройки не шло ничего, и понять это можно было
              только по строке «производство пропадает» на её экране.
             */
            planet.setProjectCode(ColonyProject.TRADE_GOODS);
            planet.setProjectDesignId(null);
            return;
        }

        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        String next = queue.remove(0);
        // Бесконечное в середине очереди пропускаем: оно не кончится, и очередь за ним
        // встанет навсегда. Последним оно остаётся — там ему и место.
        while (Boolean.TRUE.equals(ColonyProject.endless(next)) && !queue.isEmpty()) {
            log.info("Колония {} пропустила {} в очереди: за ним есть что строить",
                    planet.getName(), next);
            next = queue.remove(0);
        }
        planet.setBuildQueue(queue);

        // Код корабля несёт в себе проект, а планета держит их раздельно — так же, как
        // при выборе стройки руками (п. 8).
        UUID designId = ColonyProject.shipDesign(next);
        planet.setProjectCode(designId == null ? next : ColonyProject.SHIP);
        planet.setProjectDesignId(designId);

        context.report().add(planet.getOwnerPlayerId(), "BUILD_QUEUE",
                new MessageKey("turn.build.queueNext", planet.getName()),
                context.systemOf(planet), planet.getId());
        log.info("Колония {} взяла из очереди {} на ходу {}",
                planet.getName(), next, context.turn());
    }

    /**
     * Вкладывает производство колонии в её стройку и достраивает здание, если накопилось.
     *
     * @return построенное на этом ходу здание, если стройка завершилась
     */
    private Optional<PlanetBuildingEntity> build(PlanetEntity planet, TurnContext context) {
        BuildingEffects effects = context.colonyContext().effects(planet);
        Integer turn = context.turn();

        if (ColonyProject.COLONY_BASE.equals(planet.getProjectCode())) {
            buildColonyBase(planet, effects, context);
            return Optional.empty();
        }

        if (ColonyProject.SPY.equals(planet.getProjectCode())) {
            buildSpy(planet, effects, context);
            return Optional.empty();
        }

        if (ColonyProject.isShip(planet.getProjectCode())) {
            buildShip(planet, effects, context);
            return Optional.empty();
        }

        if (ColonyProject.FREIGHTER.equals(planet.getProjectCode())) {
            buildFreighter(planet, effects, context);
            return Optional.empty();
        }

        if (ColonyProject.COLONY_SHIP.equals(planet.getProjectCode())) {
            buildCivilShip(planet, effects, context, ShipRole.COLONY,
                    ColonyProject.COLONY_SHIP_COST, ColonyProject.COLONY_SHIP_SETTLERS,
                    "COLONY_SHIP", "turn.build.civil.colonyShip");
            return Optional.empty();
        }

        if (ColonyProject.OUTPOST_SHIP.equals(planet.getProjectCode())) {
            buildCivilShip(planet, effects, context, ShipRole.OUTPOST,
                    ColonyProject.OUTPOST_SHIP_COST, 0,
                    "OUTPOST_SHIP", "turn.build.civil.outpostShip");
            return Optional.empty();
        }

        if (ColonyProject.TRANSPORT.equals(planet.getProjectCode())) {
            buildCivilShip(planet, effects, context, ShipRole.TRANSPORT,
                    ColonyProject.TRANSPORT_COST, ColonyProject.TRANSPORT_TROOPS,
                    "TRANSPORT", "turn.build.civil.transport");
            return Optional.empty();
        }

        Building building = context.colonyContext().catalog().get(planet.getProjectCode());
        if (building == null) {
            // Ничего не строится либо строятся дома или товары — накапливать нечего.
            return Optional.empty();
        }

        planet.setProjectPoints(planet.getProjectPoints() + colonyService.production(planet, context.colonyContext()));
        if (planet.getProjectPoints() < building.cost()) {
            return Optional.empty();
        }

        // Остаток сверх стоимости остаётся на колонии и идёт в следующую стройку.
        planet.setProjectPoints(planet.getProjectPoints() - building.cost());
        planet.setProjectCode(null);

        // Второй такой же постройки на планете быть не может: в MOO II здание строится
        // однажды. Стройку сюда мог поставить и тот, кто смотрел на устаревший список
        // доступного, — тогда она просто пропадает, а не роняет ход на уникальности.
        if (context.colonyContext().buildings(planet).stream()
                .anyMatch(existing -> existing.code().equals(building.code()))) {
            log.info("Колония {} уже имеет {}: стройка отменена", planet.getName(), building.name());
            return Optional.empty();
        }
        context.colonyContext().built(planet, building);

        PlanetBuildingEntity built = new PlanetBuildingEntity();
        built.setPlanetId(planet.getId());
        built.setBuildingCode(building.code());
        built.setBuiltTurn(turn);

        context.report().add(planet.getOwnerPlayerId(), "BUILDING",
                // Ссылка, а не название: справочник переведён, и название в подстановке
                // выдало бы язык того, кто закончил ход, — п. 3.5.
                new MessageKey("turn.build.building", planet.getName(),
                        CatalogTexts.building(building.code())),
                context.systemOf(planet), planet.getId());
        log.info("Колония {} построила {} на ходу {}", planet.getName(), building.name(), turn);
        return Optional.of(built);
    }

    /**
     * Вкладывает производство в колониальную базу. Готовая база зданием не становится:
     * она поднимает на колонии флаг, и планету для заселения выбирает игрок — п. 4.1.
     * Пока выбор не сделан, колония строит следующий проект, а остаток производства
     * переходит в него, как и после любой достроенной стройки.
     */
    private void buildColonyBase(PlanetEntity planet, BuildingEffects effects, TurnContext context) {
        planet.setProjectPoints(planet.getProjectPoints() + colonyService.production(planet, context.colonyContext()));
        if (planet.getProjectPoints() < ColonyProject.COLONY_BASE_COST) {
            return;
        }
        /*
          Готовая база на колонии одна: планету ей выбирает игрок, и второй такой же
          отметки на планете нет. Поэтому достроенная вторая ждёт, пока распорядятся
          первой, — вложенное не пропадает, а стоит на цене проекта. Иначе колония,
          которой заказали три базы подряд, молча теряла бы производство двух.
         */
        if (Boolean.TRUE.equals(planet.getColonyBaseReady())) {
            planet.setProjectPoints(ColonyProject.COLONY_BASE_COST);
            context.report().add(planet.getOwnerPlayerId(), "COLONY_BASE",
                    new MessageKey("turn.build.baseWaiting", planet.getName()),
                    context.systemOf(planet), planet.getId());
            return;
        }

        planet.setProjectPoints(planet.getProjectPoints() - ColonyProject.COLONY_BASE_COST);
        planet.setProjectCode(null);
        planet.setColonyBaseReady(Boolean.TRUE);

        context.report().add(planet.getOwnerPlayerId(), "COLONY_BASE",
                new MessageKey("turn.build.baseReady", planet.getName()),
                context.systemOf(planet), planet.getId());
        log.info("Колония {} построила колониальную базу на ходу {}", planet.getName(), context.turn());
    }

    /**
     * Вкладывает производство в шпиона — п. 13. Готовый агент поступает империи, а не
     * остаётся на планете: колония строит его, но распоряжается им игрок. Остаток
     * производства переходит в следующую стройку, как и после здания.
     */
    private void buildSpy(PlanetEntity planet, BuildingEffects effects, TurnContext context) {
        planet.setProjectPoints(planet.getProjectPoints() + colonyService.production(planet, context.colonyContext()));
        if (planet.getProjectPoints() < ColonyProject.SPY_COST) {
            return;
        }

        planet.setProjectPoints(planet.getProjectPoints() - ColonyProject.SPY_COST);
        planet.setProjectCode(null);

        // Владелец берётся из списка игроков хода: он уже загружен, и запрос за ним не нужен.
        owner(context, planet.getOwnerPlayerId()).ifPresent(owner -> {
            // Агент заводится строкой: у него своё задание и свои накопленные очки — п. 13.
            espionageService.recruit(owner.getId(), context.turn());
            owner.setSpies(owner.getSpies() + 1);
            context.report().add(owner.getId(), "SPY",
                    new MessageKey("turn.build.spy", planet.getName()),
                    context.systemOf(planet), planet.getId());
            log.info("Колония {} подготовила шпиона на ходу {}: у империи {} их теперь {}",
                    planet.getName(), context.turn(), owner.getName(), owner.getSpies());
        });
    }

    /**
     * Вкладывает производство в корабль — п. 8. Строится тот проект, который выбрал игрок:
     * от него и стоимость. Готовый корабль встаёт во флот той системы, где стоит колония,
     * — лететь ему пока никуда не нужно, флотом игрок распорядится сам. Остаток
     * производства переходит в следующую стройку.
     * <p>
     * Проекта у стройки может не оказаться: колония начала строить корабль в партии,
     * начатой до появления подсистемы, — тогда берётся первый действующий проект империи.
     */
    private void buildShip(PlanetEntity planet, BuildingEffects effects, TurnContext context) {
        PlayerEntity owner = owner(context, planet.getOwnerPlayerId()).orElse(null);
        if (owner == null) {
            return;
        }

        ShipDesignEntity design = design(planet, owner, context);
        // ХАРАКТЕРИСТИКИ БЕРУТСЯ ИЗ ПАМЯТКИ ХОДА, а не считаются заново на каждую колонию:
        // один и тот же проект строят разом полтора десятка колоний империи, и прежде
        // каждая из них уходила за его составом в базу отдельным запросом — изнутри
        // посчитанного хода, где всякий запрос заставляет Hibernate сбросить накопленное.
        ShipStats stats = context.shipStats(design.getId(),
                () -> shipDesignService.stats(design, raceService.effects(owner)));

        planet.setProjectPoints(planet.getProjectPoints() + colonyService.production(planet, context.colonyContext()));
        if (planet.getProjectPoints() < stats.cost()) {
            return;
        }

        planet.setProjectPoints(planet.getProjectPoints() - stats.cost());
        planet.setProjectCode(null);
        planet.setProjectDesignId(null);

        UUID systemId = context.systemOf(planet);
        var fleet = fleetService.addShip(
                context.game().getId(), planet.getOwnerPlayerId(), systemId, context.turn(), design.getId());
        context.report().add(planet.getOwnerPlayerId(), "SHIP",
                new MessageKey("turn.build.ship",
                        planet.getName(), design.getName(), fleet.getShips()),
                systemId, planet.getId());
        log.info("Колония {} построила корабль «{}» на ходу {}",
                planet.getName(), design.getName(), context.turn());
    }

    /**
     * Проект, который строит колония. Обычно он выбран игроком; если нет — берётся первый
     * действующий проект империи, и колония строит хоть что-то, а не пропадает зря.
     */
    private ShipDesignEntity design(PlanetEntity planet, PlayerEntity owner, TurnContext context) {
        if (planet.getProjectDesignId() == null) {
            ShipDesignEntity fallback = shipDesignService.defaultDesign(context.game(), owner);
            planet.setProjectDesignId(fallback.getId());
            return fallback;
        }
        return shipDesignService.requireOwn(owner, planet.getProjectDesignId());
    }

    /**
     * Вкладывает производство в грузовой корабль — п. 4.1.1. Готовый грузовик уходит
     * империи: он не стоит в системе и не воюет, а возит еду голодающим колониям, где бы
     * те ни были. Остаток производства переходит в следующую стройку.
     */
    private void buildFreighter(PlanetEntity planet, BuildingEffects effects, TurnContext context) {
        planet.setProjectPoints(planet.getProjectPoints() + colonyService.production(planet, context.colonyContext()));
        if (planet.getProjectPoints() < ColonyProject.FREIGHTER_COST) {
            return;
        }

        planet.setProjectPoints(planet.getProjectPoints() - ColonyProject.FREIGHTER_COST);
        planet.setProjectCode(null);

        owner(context, planet.getOwnerPlayerId()).ifPresent(owner -> {
            owner.setFreighters(owner.getFreighters() + 1);
            context.report().add(owner.getId(), "FREIGHTER",
                    new MessageKey("turn.build.freighter",
                            planet.getName(), owner.getFreighters()),
                    context.systemOf(planet), planet.getId());
            log.info("Колония {} построила грузовой корабль на ходу {}: всего {}",
                    planet.getName(), context.turn(), owner.getFreighters());
        });
    }

    /**
     * Вкладывает производство в гражданский корабль — п. 4.1 и п. 12: колониальный
     * корабль или транспорт.
     * <p>
     * Готовый корабль встаёт во флот своей системы вместе со своим грузом — поселенцами
     * или десантом. <b>Жителей колонии этот груз не отнимает</b>: в MOO II у обоих
     * кораблей твёрдая цена в единицах производства (500 и 100), и людей в ней уже
     * посчитали — колония, снаряжая транспорт, не пустеет.
     * <p>
     * Проект корабля империя получает по требованию: колониальный корабль и транспорт в
     * окне дизайна не собираются, у них своя служебная ячейка — п. 8.
     *
     * @param cargo сколько жителей увозит корабль: поселенцы или бойцы
     */
    private void buildCivilShip(PlanetEntity planet, BuildingEffects effects, TurnContext context,
                                ShipRole role, Integer cost, Integer cargo,
                                String eventType, String what) {
        PlayerEntity owner = owner(context, planet.getOwnerPlayerId()).orElse(null);
        if (owner == null) {
            return;
        }

        planet.setProjectPoints(planet.getProjectPoints() + colonyService.production(planet, context.colonyContext()));
        if (planet.getProjectPoints() < cost) {
            return;
        }

        planet.setProjectPoints(planet.getProjectPoints() - cost);
        planet.setProjectCode(null);

        ShipDesignEntity design = shipDesignService.civilDesign(context.game(), owner, role);
        UUID systemId = context.systemOf(planet);
        fleetService.addShip(context.game().getId(), owner.getId(), systemId, context.turn(),
                design.getId(), cargo);

        context.report().add(owner.getId(), eventType,
                new MessageKey("turn.build.civil", planet.getName(), what),
                systemId, planet.getId());
        log.info("Колония {} построила {} на ходу {}", planet.getName(), what, context.turn());
    }

    /**
     * Убыточная колония — своё событие хода, отдельно от общего дохода империи.
     * <p>
     * Доход империи — сумма, и колония, проедающая казну содержанием зданий, в ней
     * растворяется: империя в плюсе, а колония тянет её вниз, и игрок об этом не узнаёт.
     * Поэтому убыток называется поимённо, с суммой и с содержанием, из которого он вырос.
     * <p>
     * Событие ещё и решает судьбу окна «Результаты хода»: одно лишь пополнение казны его
     * не открывает, а убыток открывает — см. `TurnResults` на клиенте.
     */
    private void reportLoss(PlanetEntity planet, Integer income, TurnContext context) {
        if (income >= 0) {
            return;
        }
        Integer upkeep = context.colonyContext().effects(planet).upkeep();
        context.report().add(planet.getOwnerPlayerId(), "LOSS",
                upkeep > 0
                        ? new MessageKey("turn.colony.lossUpkeep", planet.getName(), income, upkeep)
                        : new MessageKey("turn.colony.loss", planet.getName(), income),
                context.systemOf(planet), planet.getId());
    }

    /**
     * Флот сверх командных очков империи оплачивается казной — п. 8, п. 14.
     * <p>
     * Запас дают колонии (военачальникам — вдвое больше, п. 7), расходуют его корабли по
     * своим корпусам. Пока флот в запас укладывается, он ничего не стоит: в MOO II
     * командные очки это предел, а не налог. Перебор списывается из дохода до того, как
     * тот ляжет в казну, и назван игроку поимённо — иначе доход просто «почему-то» падал
     * бы с каждым новым дредноутом.
     */
    private void payCommandPoints(TurnContext context, Map<UUID, Integer> incomeByPlayer) {
        Map<UUID, Integer> used = fleetService.commandUsedByPlayer(context.game().getId());
        if (used.isEmpty()) {
            return;
        }

        for (PlayerEntity player : context.players()) {
            Integer points = used.getOrDefault(player.getId(), 0);
            if (points == 0) {
                continue;
            }
            Integer capacity = commandRules.capacity(
                    context.coloniesOf(player.getId()).size(), raceService.effects(player));
            Integer cost = commandRules.upkeep(points, capacity);
            if (cost == 0) {
                continue;
            }
            incomeByPlayer.merge(player.getId(), -cost, Integer::sum);
            context.report().add(player.getId(), "COMMAND",
                    new MessageKey("turn.command.over", points, capacity, cost));
        }
    }

    /** Казна игрока пополняется доходом его колоний; уйти в минус она может — это долг. */
    private void payTreasuries(TurnContext context, Map<UUID, Integer> incomeByPlayer) {
        for (PlayerEntity player : context.players()) {
            Integer income = incomeByPlayer.getOrDefault(player.getId(), 0);
            if (income == 0) {
                continue;
            }
            player.setCredits(player.getCredits() + income);
            context.report().add(player.getId(), "INCOME",
                    new MessageKey("turn.income.empire",
                            (income > 0 ? "+" : "") + income, player.getCredits()));
        }
    }

    /**
     * ПУСТАЯ КАЗНА ЗАСТАВЛЯЕТ РАСПРОДАВАТЬСЯ — п. 10, п. 11.1 (правило оригинала).
     * <p>
     * «To prevent your treasury from becoming negative the game automatically scraps
     * things» (руководство патча 1.50). До этой правки минус в казне не значил ровно
     * НИЧЕГО: `setCredits` уводил её сколь угодно глубоко, и никто этого не замечал.
     * Реплей партии показал, чем это оборачивается: сильнейшая сборка круга 5 ушла в долг
     * на 272-м ходу и закончила партию с −56 690 кредитов, продолжая строить всё те же
     * здания. Империя строила больше, чем может кормить, и игра ей не возражала.
     * <p>
     * <b>Что продаётся.</b> Самое дорогое в содержании: именно оно и есть причина долга, а
     * казна получает половину цены ({@link PopulationCalculator#sellValue}). Продажа идёт
     * тем же путём, каким продаёт игрок ({@link ColonyService#sellBuilding}) — второго
     * свода правил продажи в игре нет, и предел «одна постройка с колонии за ход»
     * соблюдается сам собой. Он же и ограничивает распродажу: за ход империя избавляется
     * не больше чем от одного здания на колонию, и долг гасится за несколько ходов, а не
     * обвалом.
     * <p>
     * <b>Чего здесь пока нет.</b> В оригинале первыми идут под нож КОРАБЛИ («a correct
     * number of ships is scrapped to cover your debt»), и это правильный порядок для долга,
     * выросшего из флота. У нас флот оплачивается только перебором командных очков
     * ({@link CommandRules}), и долги в замерах росли из содержания зданий — поэтому
     * начато со зданий. Списание кораблей — следующий шаг, и место для него здесь же.
     */
    private void payDebts(TurnContext context) {
        for (PlayerEntity player : context.players()) {
            if (player.getCredits() >= 0) {
                continue;
            }
            for (PlanetEntity colony : sortedColonies(context, player)) {
                if (player.getCredits() >= 0) {
                    break;
                }
                if (context.turn().equals(colony.getSoldTurn())) {
                    continue;
                }
                Building dearest = context.colonyContext().buildings(colony).stream()
                        .filter(building -> building.upkeep() > 0)
                        // Дороже всех в содержании — и при равенстве по коду: партия
                        // обязана повторяться до последнего числа (этап 0).
                        .max(Comparator.comparing(Building::upkeep)
                                .thenComparing(Comparator.comparing(Building::code).reversed()))
                        .orElse(null);
                if (dearest == null) {
                    continue;
                }
                colonyService.sellBuilding(player, colony.getId(), dearest.code(), context.turn());
                context.colonyContext().sold(colony, dearest.code());
                context.report().add(player.getId(), "DEBT",
                        new MessageKey("turn.debt.sold", colony.getName(),
                                CatalogTexts.building(dearest.code()), player.getCredits()),
                        context.systemOf(colony), colony.getId());
                log.info("Долг империи {}: продано {} на {} — в казне {}",
                        player.getName(), dearest.code(), colony.getName(), player.getCredits());
            }
        }
    }

    /** Колонии игрока в порядке, заданном партией: распродажа обязана повторяться. */
    private List<PlanetEntity> sortedColonies(TurnContext context, PlayerEntity player) {
        return context.coloniesOf(player.getId()).stream()
                .sorted(GameOrder.PLANETS)
                .toList();
    }

    private Optional<PlayerEntity> owner(TurnContext context, UUID playerId) {
        return context.players().stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst();
    }
}
