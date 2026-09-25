package com.moo3.server.service;

import com.moo3.server.domain.Building;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.PlanetFind;
import com.moo3.server.domain.enums.PlanetGravity;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.entity.PopulationTransferEntity;
import com.moo3.server.dto.ColonyDto;
import com.moo3.server.dto.ColonyProjectDto;
import com.moo3.server.dto.SetPopulationRequest;
import com.moo3.server.dto.ColonizeRequest;
import com.moo3.server.dto.SetProjectRequest;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.repository.PopulationTransferRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Колонии игрока — п. 4.1 и п. 10.
 * <p>
 * Как в MOO II, каждый житель занят чем-то одним — фермерством, производством или наукой,
 * — и выработка зависит и от планеты, и от построенных зданий: климат и фермы дают еду,
 * минералы и заводы производство, учёные и лаборатории науку.
 * <p>
 * Колония строит один проект за раз. Проект это либо здание, доступное после изучения
 * своей технологии, либо особый проект MOO II — дома, товары или колониальная база.
 * Дома и товары доступны с первого хода и не заканчиваются никогда; колониальная база
 * достраивается и заселяет свободную планету своей же системы.
 */
@Service
public class ColonyService {

    private static final Logger log = LoggerFactory.getLogger(ColonyService.class);

    private static final ColonyProjectDto HOUSING = new ColonyProjectDto(
            ColonyProject.HOUSING,
            "Дома",
            "Производство колонии уходит в жильё и ускоряет рост населения. Зданием не становится.",
            null, 0, Boolean.TRUE, null, ColonyProject.repeatable(ColonyProject.HOUSING));

    private static final ColonyProjectDto TRADE_GOODS = new ColonyProjectDto(
            ColonyProject.TRADE_GOODS,
            "Товары",
            "Производство колонии продаётся и пополняет казну: кредит за две единицы. Зданием не становится.",
            null, 0, Boolean.TRUE, null, ColonyProject.repeatable(ColonyProject.TRADE_GOODS));

    private static final ColonyProjectDto SPY = new ColonyProjectDto(
            ColonyProject.SPY,
            "Шпион",
            "Готовит агента для разведки чужих систем и знакомства с их хозяевами — п. 15."
                    + " Исследований не требует. Зданием не становится.",
            ColonyProject.SPY_COST, 0, Boolean.TRUE, null,
            ColonyProject.repeatable(ColonyProject.SPY));

    private static final ColonyProjectDto FREIGHTER = new ColonyProjectDto(
            ColonyProject.FREIGHTER,
            "Грузовой флот",
            "Строит грузовой корабль — п. 4.1.1. Грузовики империи возят еду голодающим"
                    + " колониям: каждый увозит до пяти единиц за ход. Требует технологии"
                    + " «Freighters». Зданием не становится.",
            ColonyProject.FREIGHTER_COST, 0, Boolean.TRUE, null,
            ColonyProject.repeatable(ColonyProject.FREIGHTER));

    private static final ColonyProjectDto COLONY_BASE = new ColonyProjectDto(
            ColonyProject.COLONY_BASE,
            "Колониальная база",
            "Заселяет свободную планету этой же системы. По готовности игрок выбирает планету;"
                    + " новая колония начинается с одного жителя. Зданием не становится.",
            ColonyProject.COLONY_BASE_COST, 0, Boolean.TRUE, null,
            ColonyProject.repeatable(ColonyProject.COLONY_BASE));

    private static final ColonyProjectDto COLONY_SHIP = new ColonyProjectDto(
            ColonyProject.COLONY_SHIP,
            "Колониальный корабль",
            "Увозит поселенцев в другую систему и основывает там колонию — п. 4.1."
                    + " Готовый корабль встаёт во флот системы; куда лететь и какую планету"
                    + " заселять, решает игрок. В бою не участвует. Зданием не становится.",
            ColonyProject.COLONY_SHIP_COST, 0, Boolean.TRUE, null,
            ColonyProject.repeatable(ColonyProject.COLONY_SHIP));

    private static final ColonyProjectDto OUTPOST_SHIP = new ColonyProjectDto(
            ColonyProject.OUTPOST_SHIP,
            "Корабль-застава",
            "Ставит заставу на любой планете, даже непригодной для жизни, — п. 8."
                    + " Жителей и выработки застава не даёт, зато от неё империя дотягивается"
                    + " топливом дальше. В бою не участвует. Зданием не становится.",
            ColonyProject.OUTPOST_SHIP_COST, 0, Boolean.TRUE, null,
            ColonyProject.repeatable(ColonyProject.OUTPOST_SHIP));

    private static final ColonyProjectDto TRANSPORT = new ColonyProjectDto(
            ColonyProject.TRANSPORT,
            "Транспорт",
            "Везёт десант — четыре бойца, как в оригинале (п. 12). Готовый корабль встаёт"
                    + " во флот системы и высаживает десант на чужую колонию, куда долетит."
                    + " В бою не участвует. Зданием не становится.",
            ColonyProject.TRANSPORT_COST, 0, Boolean.TRUE, null,
            ColonyProject.repeatable(ColonyProject.TRANSPORT));

    private final PlanetRepository planetRepository;
    private final PlayerRepository playerRepository;
    private final PopulationTransferRepository transferRepository;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final BuildingCatalog buildingCatalog;
    private final RaceService raceService;
    private final DiplomacyService diplomacyService;
    private final PopulationCalculator populationCalculator;
    private final AssimilationRules assimilationRules;
    private final ShipDesignService shipDesignService;
    private final ShipDesignRules shipDesignRules;
    private final LeaderBonusService leaderBonuses;
    private final EmpireActivityService activity;

    public ColonyService(PlanetRepository planetRepository,
                         PlayerRepository playerRepository,
                         PopulationTransferRepository transferRepository,
                         PlanetBuildingRepository planetBuildingRepository,
                         PlayerTechnologyRepository playerTechnologyRepository,
                         BuildingCatalog buildingCatalog,
                         RaceService raceService,
                         DiplomacyService diplomacyService,
                         PopulationCalculator populationCalculator,
                         ShipDesignService shipDesignService,
                         ShipDesignRules shipDesignRules,
                         LeaderBonusService leaderBonuses,
                         AssimilationRules assimilationRules,
                         EmpireActivityService activity) {
        this.activity = activity;
        this.assimilationRules = assimilationRules;
        this.planetRepository = planetRepository;
        this.playerRepository = playerRepository;
        this.transferRepository = transferRepository;
        this.planetBuildingRepository = planetBuildingRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.buildingCatalog = buildingCatalog;
        this.raceService = raceService;
        this.diplomacyService = diplomacyService;
        this.populationCalculator = populationCalculator;
        this.shipDesignService = shipDesignService;
        this.shipDesignRules = shipDesignRules;
        this.leaderBonuses = leaderBonuses;
    }

    /**
     * Всё, что нужно знать о наборе колоний, собранное разом.
     * <p>
     * Изученное владельцем и построенные здания нужны каждой колонии, поэтому берутся
     * одним запросом на весь набор, а не по запросу на планету: карта галактики строится
     * по всем планетам сразу.
     */
    public record ColonyContext(
            Map<UUID, Set<String>> technologiesByOwner,
            Map<UUID, List<Building>> buildingsByPlanet,
            Map<UUID, RaceEffects> raceEffectsByOwner,
            Map<UUID, BuildingEffects> treatyEffectsByOwner,
            Map<String, Building> catalog,
            /** Сколько еды довёз колонии грузовой флот империи — п. 4.1.1. */
            Map<UUID, Integer> deliveredFoodByPlanet,
            /** Действующие проекты кораблей владельцев колоний — п. 8. */
            Map<UUID, List<ShipDesignService.ShipBuildOption>> shipDesignsByOwner,
            /**
             * Прибавки колониальных лидеров по звёздным системам — п. 6.
             *
             * Лидер служит в системе и помогает всем её колониям сразу, поэтому ключ здесь
             * система, а не планета. Пустая карта значит «лидеров нет» — так контекст
             * собирается и там, где лидеры ни при чём (карта галактики до найма).
             */
            Map<UUID, Map<String, Integer>> leaderBonusBySystem
    ) {

        /**
         * Процент прибавки колониального лидера этой колонии — п. 6.
         *
         * Идентификатор системы читается с ленивой ссылки планеты: сама система при этом
         * из базы не поднимается (см. «Грабли» в CLAUDE.md — с ленивой заглушки безопасно
         * читается только идентификатор).
         */
        public Integer leaderPercent(PlanetEntity planet, String ability) {
            if (leaderBonusBySystem.isEmpty() || planet.getStarSystem() == null) {
                return 0;
            }
            return leaderBonusBySystem
                    .getOrDefault(planet.getStarSystem().getId(), Map.of())
                    .getOrDefault(ability, 0);
        }

        /** Значение, поднятое прибавкой лидера: проценты складываются с сотней. */
        public Integer withLeader(Integer value, PlanetEntity planet, String ability) {
            Integer percent = leaderPercent(planet, ability);
            return percent == 0 ? value : value * (100 + percent) / 100;
        }

        /** Корабли, которые может строить эта колония, — проекты её империи. */
        public List<ShipDesignService.ShipBuildOption> shipDesigns(PlanetEntity planet) {
            return shipDesignsByOwner.getOrDefault(planet.getOwnerPlayerId(), List.of());
        }

        /** Подвоз еды на эту колонию; ноль — грузовиков нет или везти нечего. */
        public Integer delivered(PlanetEntity planet) {
            return deliveredFoodByPlanet.getOrDefault(planet.getId(), 0);
        }

        public List<Building> buildings(PlanetEntity planet) {
            return buildingsByPlanet.getOrDefault(planet.getId(), List.of());
        }

        /**
         * Отмечает здание, достроенное этим же ходом, — п. 10.
         * <p>
         * Контекст собирается один раз на весь ход, и без отметки он врёт всем, кто идёт
         * после производства: список доступного колонии по-прежнему предлагал бы только
         * что построенное здание. Империя ИИ на это и попалась — выбирала его снова, и
         * следующий ход падал на уникальности {@code (planet_id, building_code)}.
         */
        public void built(PlanetEntity planet, Building building) {
            buildingsByPlanet.computeIfAbsent(planet.getId(), key -> new ArrayList<>()).add(building);
        }

        /**
         * Отмечает здание, ПРОДАННОЕ этим же ходом, — п. 10, п. 11.1.
         * <p>
         * Зеркало {@link #built}: пустая казна заставляет империю распродавать постройки
         * прямо в фазе производства (ProductionPhase.payDebts), а фазы, идущие следом —
         * ИИ, наука, разведка, — считают по контексту хода. Без отметки они весь
         * оставшийся ход работали бы с содержанием и прибавками того, чего уже нет, а
         * империя ИИ вдобавок не стала бы отстраивать проданное.
         */
        public void sold(PlanetEntity planet, String buildingCode) {
            List<Building> standing = buildingsByPlanet.get(planet.getId());
            if (standing != null) {
                standing.removeIf(building -> building.code().equals(buildingCode));
            }
        }

        public Set<String> technologies(PlanetEntity planet) {
            return technologiesByOwner.getOrDefault(planet.getOwnerPlayerId(), Set.of());
        }

        /**
         * Всё, что действует на колонию: её постройки плюс особенности расы владельца
         * (п. 7). Раса работает как вечная постройка, поэтому расчёты колонии их не
         * различают — им достаточно суммы.
         */
        public BuildingEffects effects(PlanetEntity planet) {
            return BuildingEffects.sum(buildings(planet))
                    .plus(raceOfPopulation(planet))
                    .plus(artifacts(planet))
                    .plus(find(planet))
                    .plus(gravity(planet))
                    .plus(treatyEffectsByOwner.getOrDefault(planet.getOwnerPlayerId(), BuildingEffects.NONE));
        }

        /**
         * Чего стоит колонии непривычная тяжесть мира — п. 4.1, п. 7.
         * <p>
         * Штраф ложится на производство той же строкой, что и прибавки зданий: колония
         * не различает, откуда пришёл процент. Расе, привыкшей к такой тяжести, он равен
         * нулю — числа и правило в {@link PlanetGravity}.
         */
        private BuildingEffects gravity(PlanetEntity planet) {
            RaceEffects race = race(planet);
            Integer percent = PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())
                    .productionPercent(race.gravityLow(), race.gravityHigh());
            return percent == 0
                    ? BuildingEffects.NONE
                    : new BuildingEffects(Map.of(BuildingEffectType.PRODUCTION_PERCENT, percent), 0);
        }

        /**
         * Наследие ушедшей цивилизации на родном мире — п. 7: учёный даёт на нём два
         * лишних очка.
         * <p>
         * Единственная сторона расы, которая действует не на всю империю, а на одну
         * планету: мир артефактов достался игроку один. Поэтому она и не попадает
         * в общие эффекты расы, а приходит сюда отдельной прибавкой.
         */
        private BuildingEffects artifacts(PlanetEntity planet) {
            Integer bonus = race(planet).homeResearchPerScientist();
            return bonus == 0 || !Boolean.TRUE.equals(planet.getHomeworld())
                    ? BuildingEffects.NONE
                    : new BuildingEffects(Map.of(BuildingEffectType.RESEARCH_PER_SCIENTIST, bonus), 0);
        }

        /**
         * Что даёт колонии находка её планеты — п. 4.1: золотые жилы, самоцветы, туземцы
         * или наследие ушедшей цивилизации.
         * <p>
         * Находка описана теми же парами «тип эффекта — количество», что и здание
         * ({@link PlanetFind}), и приходит сюда одной строкой: колония не различает, чем
         * поднята её выработка — постройкой, расой или тем, что лежит у неё под ногами.
         * Содержания находка не требует: платить за жилу некому.
         */
        private BuildingEffects find(PlanetEntity planet) {
            return planet.getFind() == null
                    ? BuildingEffects.NONE
                    : new BuildingEffects(planet.getFind().getEffects(), 0);
        }

        /** Раса владельца колонии — п. 7; у ничьей планеты особенностей нет. */
        public RaceEffects race(PlanetEntity planet) {
            return raceEffectsByOwner.getOrDefault(planet.getOwnerPlayerId(), RaceEffects.NONE);
        }

        /** Раса подданных колонии — п. 12; пока их нет, это раса хозяина. */
        public RaceEffects alienRace(PlanetEntity planet) {
            if (planet.getAlienPopulation() <= 0 || planet.getAlienOwnerPlayerId() == null) {
                return race(planet);
            }
            return raceEffectsByOwner.getOrDefault(planet.getAlienOwnerPlayerId(), race(planet));
        }

        /**
         * Что даёт колонии её население — п. 7, п. 12.
         * <p>
         * Пока на колонии живут подданные, взятые с боем, работает она двумя расами
         * сразу: свои жители по правилам хозяина, подданные — по своим прежним. Доли
         * считаются по головам, и с каждым ассимилированным жителем колония всё больше
         * становится своей.
         */
        private BuildingEffects raceOfPopulation(PlanetEntity planet) {
            Integer aliens = Math.max(0, planet.getAlienPopulation());
            if (aliens == 0) {
                return race(planet).colony();
            }
            Integer own = Math.max(0, planet.getPopulation() - aliens);
            return race(planet).colony().blend(alienRace(planet).colony(), own, aliens);
        }
    }

    /** Собирает контекст для набора планет: изученное владельцами и построенные здания. */
    public ColonyContext context(Collection<PlanetEntity> planets) {
        Set<UUID> owners = planets.stream()
                .map(PlanetEntity::getOwnerPlayerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Расы прежних хозяев нужны там, где на колонии ещё живут их подданные (п. 12):
        // они работают по своим правилам, пока не ассимилируются.
        Set<UUID> withAliens = planets.stream()
                .map(PlanetEntity::getAlienOwnerPlayerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Еду империя развозит грузовым флотом между всеми своими колониями, поэтому
        // контекст всегда знает их все — даже когда его строят ради одной планеты.
        List<PlanetEntity> imperial = owners.isEmpty()
                ? List.of()
                : planetRepository.findAllByOwnerPlayerIdIn(owners).stream()
                        .sorted(GameOrder.PLANETS)
                        .filter(colony -> colony.getPopulation() > 0)
                        .toList();

        Set<UUID> planetIds = Stream.concat(planets.stream(), imperial.stream())
                .map(PlanetEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Set<String>> technologies = owners.isEmpty()
                ? Map.of()
                : playerTechnologyRepository.findAllByPlayerIdIn(owners).stream()
                        .collect(Collectors.groupingBy(
                                PlayerTechnologyEntity::getPlayerId,
                                Collectors.mapping(PlayerTechnologyEntity::getOptionCode, Collectors.toSet())));

        Map<String, Building> catalog = buildingCatalog.byCode();

        // Особенности расы берутся по владельцам колоний: их немного, и они не меняются
        // за партию, поэтому считаются один раз на весь набор планет.
        Set<UUID> races = new HashSet<>(owners);
        races.addAll(withAliens);
        Map<UUID, RaceEffects> raceEffects = raceService.effectsByPlayer(races);

        // Торговый и исследовательский договоры работают на колонии так же, как здания
        // и раса, — п. 15.
        // Одной выборкой на всех владельцев, а не запросом на игрока: контекст собирается
        // каждый ход, и три запроса на империю превращались в восемнадцать за ход изнутри
        // посчитанного хода — см. DiplomacyService.treatyEffects(Collection).
        Map<UUID, BuildingEffects> treatyEffects = diplomacyService.treatyEffects(owners);

        // Карта построенного меняется по ходу: производство дописывает в неё достроенное
        // (см. ColonyContext.built), поэтому она изменяемая, а не List.of()/Map.of().
        Map<UUID, List<Building>> built = planetIds.isEmpty()
                ? new HashMap<>()
                : planetBuildingRepository.findAllByPlanetIdIn(planetIds).stream()
                        .filter(entry -> catalog.containsKey(entry.getBuildingCode()))
                        .collect(Collectors.groupingBy(
                                PlanetBuildingEntity::getPlanetId,
                                Collectors.mapping(entry -> catalog.get(entry.getBuildingCode()),
                                        Collectors.toCollection(ArrayList::new))));

        // Проекты кораблей владельцев — один запрос на весь набор колоний: список стройки
        // собирается для каждой планеты карты, а проекты у империи общие — п. 8.
        Map<UUID, List<ShipDesignService.ShipBuildOption>> shipDesigns =
                shipDesignService.buildOptions(raceEffects);

        // Лидеры владельцев колоний — п. 6: колониальный лидер помогает всем колониям
        // своей системы, и его прибавка нужна выработке, доходу и рождаемости.
        Map<UUID, Map<String, Integer>> leaderBonus = leaderBonusBySystem(owners);

        ColonyContext context = new ColonyContext(technologies, built, raceEffects, treatyEffects,
                catalog, Map.of(), shipDesigns, leaderBonus);
        return new ColonyContext(technologies, built, raceEffects, treatyEffects, catalog,
                freightDeliveries(imperial, owners, context), shipDesigns, leaderBonus);
    }

    /**
     * Прибавки колониальных лидеров владельцев этих колоний — п. 6.
     * <p>
     * Ключ карты — звёздная система: лидер служит в системе и помогает всем её колониям
     * сразу. Берётся одной выборкой на всех владельцев: контекст собирается для карты
     * целиком, и запрос «на игрока» здесь стоил бы восьми запросов за ход.
     */
    private Map<UUID, Map<String, Integer>> leaderBonusBySystem(Set<UUID> owners) {
        Map<UUID, Map<String, Integer>> merged = new HashMap<>();
        leaderBonuses.of(owners).values().forEach(bonuses -> bonuses.bySystem()
                .forEach((systemId, abilities) -> merged
                        .computeIfAbsent(systemId, id -> new HashMap<>())
                        .putAll(abilities)));
        return merged;
    }

    /**
     * Сколько еды грузовой флот довезёт каждой голодающей колонии — п. 4.1.1.
     * <p>
     * Возится излишек соседних колоний той же империи, и не больше, чем поднимает её
     * грузовой флот. Без грузовиков подвоза нет вовсе: колония ест то, что вырастила
     * сама, — как и было в игре до появления флота.
     */
    private Map<UUID, Integer> freightDeliveries(List<PlanetEntity> colonies,
                                                 Set<UUID> owners,
                                                 ColonyContext effectsSource) {
        if (colonies.isEmpty() || owners.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> freighters = playerRepository.findAllById(owners).stream()
                .collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getFreighters));

        // Грузовики, занятые перевозкой жителей, еду не возят: рейс держит по грузовику
        // на единицу населения, пока не дойдёт — п. 4.1.1.
        Map<UUID, Integer> reserved = transferRepository.findAllByOwnerPlayerIdInOrderByIdAsc(owners).stream()
                .collect(Collectors.groupingBy(PopulationTransferEntity::getOwnerPlayerId,
                        Collectors.summingInt(transfer ->
                                populationCalculator.freightersForTransfer(transfer.getPopulation()))));

        Map<UUID, Integer> delivered = new HashMap<>();

        for (UUID owner : owners) {
            Integer fleet = Math.max(0,
                    freighters.getOrDefault(owner, 0) - reserved.getOrDefault(owner, 0));
            if (fleet <= 0) {
                continue;
            }

            List<PlanetEntity> own = colonies.stream()
                    .filter(colony -> owner.equals(colony.getOwnerPlayerId()))
                    .toList();

            int surplus = 0;
            List<PlanetEntity> hungry = new ArrayList<>();
            List<Integer> deficits = new ArrayList<>();
            for (PlanetEntity colony : own) {
                int balance = foodBalance(colony, effectsSource.effects(colony), effectsSource.race(colony));
                if (balance > 0) {
                    surplus += balance;
                } else if (balance < 0) {
                    hungry.add(colony);
                    deficits.add(-balance);
                }
            }
            if (hungry.isEmpty() || surplus == 0) {
                continue;
            }

            Integer pool = Math.min(surplus, populationCalculator.freightCapacity(fleet));
            List<Integer> shares = populationCalculator.deliverFood(pool, deficits);
            for (int index = 0; index < hungry.size(); index++) {
                if (shares.get(index) > 0) {
                    delivered.put(hungry.get(index).getId(), shares.get(index));
                }
            }
        }
        return Map.copyOf(delivered);
    }

    /** Остаток еды колонии до подвоза: своя выработка минус свои едоки. */
    private Integer foodBalance(PlanetEntity planet, BuildingEffects effects, RaceEffects race) {
        return populationCalculator.food(planet.getClimate(), jobs(planet).farmers(), effects, race)
                - populationCalculator.foodConsumption(planet.getPopulation(), race);
    }

    /** Контекст для одной планеты — для ответа на действие игрока с ней. */
    public ColonyContext context(PlanetEntity planet) {
        return context(List.of(planet));
    }

    /** Занятия жителей планеты, выправленные по её населению. */
    public PopulationJobs jobs(PlanetEntity planet) {
        return populationCalculator.normalize(planet.getClimate(), planet.getPopulation(), planet.getJobs());
    }

    /** Вместимость планеты со зданиями: биосферы её поднимают. */
    public Integer maxPopulation(PlanetEntity planet, BuildingEffects effects) {
        return maxPopulation(planet, effects, RaceEffects.NONE);
    }

    /**
     * Вместимость планеты со зданиями и расой хозяина — п. 7: неприхотливым, водным и
     * подземным на одной и той же планете помещается больше, чем прочим.
     * <p>
     * Расовая часть считается поверх записанной на планете, а не вместо неё: вместимость
     * планеты — её собственное свойство и меняется терраформированием, а раса приходит
     * и уходит вместе с хозяином колонии.
     */
    public Integer maxPopulation(PlanetEntity planet, BuildingEffects effects, RaceEffects race) {
        Integer capacity = planet.getMaxPopulation()
                + populationCalculator.raceCapacityBonus(planet.getPlanetSize(), planet.getClimate(), race)
                + effects.maxPopulationBonus();
        // У пригодной планеты ниже одного жителя вместимость не опускается: прибавка
        // бывает и отрицательной — туземцы (п. 4.1) занимают три места из собственной
        // вместимости планеты, — а колония, которой некуда поселить ни одного жителя,
        // сломала бы и рост, и захват. Непригодная так и остаётся нулём: газовый гигант
        // не становится обитаемым ни от чего.
        return planet.getMaxPopulation() <= 0 ? Math.max(0, capacity) : Math.max(1, capacity);
    }

    /**
     * Сколько единиц еды колонии не хватает за ход — уже с подвозом.
     * <p>
     * Своя выработка плюс то, что довёз грузовой флот империи (п. 4.1.1). Без грузовиков
     * подвоза нет, и колония ест только то, что вырастила сама, — как в MOO II до
     * постройки первых грузовиков.
     */
    public Integer foodLack(PlanetEntity planet, ColonyContext context) {
        return Math.max(0, -foodBalance(planet, context.effects(planet), context.race(planet))
                - context.delivered(planet));
    }

    /** Производство колонии за ход со всеми её зданиями. */
    public Integer production(PlanetEntity planet, BuildingEffects effects) {
        return production(planet, effects, RaceEffects.NONE);
    }

    /**
     * Производство колонии со зданиями, расой <b>и колониальным лидером</b> — п. 6.
     * <p>
     * Управляющий («Labor Leader») поднимает выработку рабочих всей своей системы. В
     * MOO II его процент касается именно рабочих, а не твёрдых прибавок от зданий, —
     * поэтому он применяется к добыче, а не к содержимому зданий. И применяется он
     * <b>до уборки</b> (п. 10): больше промышленности — больше отходов, иначе управляющий
     * давал бы чистое производство из ничего.
     */
    public Integer production(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        return net(planet, grossProduction(planet, context), effects, race);
    }

    /**
     * Производство колонии за ход со зданиями и расой — п. 7 и п. 10: добытое за вычетом
     * уборки и того, что проели жители.
     */
    public Integer production(PlanetEntity planet, BuildingEffects effects, RaceEffects race) {
        return net(planet, grossProduction(planet, effects), effects, race);
    }

    /**
     * Добыча колонии до уборки и прокорма — п. 10: то, что жители со зданиями подняли из
     * недр. Это же число пачкает планету, поэтому загрязнение считается от него.
     */
    public Integer grossProduction(PlanetEntity planet, BuildingEffects effects) {
        return populationCalculator.production(planet.getMinerals(), jobs(planet).workers(),
                planet.getPopulation(), effects);
    }

    /** Та же добыча, но с процентом управляющего системы — п. 6. */
    public Integer grossProduction(PlanetEntity planet, ColonyContext context) {
        return context.withLeader(grossProduction(planet, context.effects(planet)), planet, "LABOR");
    }

    /**
     * Что останется колонии от добытого — п. 7 и п. 10.
     * <p>
     * Сперва уборка за собой (загрязнение), потом прокорм: киборги питаются рудой наравне
     * с едой, и эта доля уходит до того, как колония вложит что-то в стройку. Ниже нуля
     * производство не опускается — жители просто ничего не построят.
     */
    private Integer net(PlanetEntity planet, Integer gross, BuildingEffects effects, RaceEffects race) {
        return Math.max(0, gross
                - pollution(planet, gross, effects)
                - populationCalculator.productionConsumption(planet.getPopulation(), race));
    }

    /**
     * Сколько единиц производства колония терпит без грязи — п. 10: терпимость размера
     * планеты, поднятая зданиями (наноразборщики удваивают её).
     */
    public Integer pollutionTolerance(PlanetEntity planet, BuildingEffects effects) {
        Integer base = planet.getPlanetSize().getPollutionTolerance();
        return base + base * effects.pollutionTolerancePercent() / 100;
    }

    /**
     * Сколько производства уходит на уборку за ход — п. 10.
     * <p>
     * Свалка в ядре планеты и неприхотливая раса (п. 7) снимают загрязнение целиком —
     * оба приходят одним и тем же действием, колония их не различает.
     * <p>
     * Чистая часть добычи — твёрдое производство зданий и переработка рециклотрона —
     * из грязного вычитается: чадят работники, а не механизмы.
     */
    public Integer pollution(PlanetEntity planet, Integer gross, BuildingEffects effects) {
        if (Boolean.TRUE.equals(effects.pollutionFree())) {
            return 0;
        }
        return populationCalculator.pollution(gross,
                populationCalculator.cleanProduction(planet.getPopulation(), effects),
                pollutionTolerance(planet, effects), effects.pollutionDivisor());
    }

    /** Уборка колонии за ход с учётом управляющего системы — для экрана колонии. */
    public Integer pollution(PlanetEntity planet, ColonyContext context) {
        return pollution(planet, grossProduction(planet, context), context.effects(planet));
    }

    /** Прибавка к рождаемости от медицинских технологий — п. 9. */
    public Integer medicineBonusPercent(PlanetEntity planet, ColonyContext context) {
        return populationCalculator.medicineBonusPercent(context.technologies(planet));
    }

    /** Прибавка к рождаемости от домов; колония, которая их не строит, не получает ничего. */
    public Integer housingBonusPercent(PlanetEntity planet, Integer production) {
        if (!ColonyProject.HOUSING.equals(planet.getProjectCode())) {
            return 0;
        }
        return populationCalculator.housingBonusPercent(production, planet.getPopulation());
    }

    /** Прирост населения колонии за ход в тысячах жителей. */
    public Integer growthK(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        // Расовая прибавка к рождаемости (п. 7) складывается с медициной и домами —
        // в MOO II проценты рождаемости складываются между собой.
        // Врач («Medicine») ускоряет рост во всей своей системе — п. 6; проценты
        // рождаемости в MOO II складываются между собой.
        Integer bonus = medicineBonusPercent(planet, context)
                + context.leaderPercent(planet, "MEDICINE")
                + housingBonusPercent(planet, production(planet, effects, race))
                + effects.growthPercent();
        return populationCalculator.growthK(
                planet.getPopulation(),
                maxPopulation(planet, effects, race),
                bonus,
                effects.growthKBonus(),
                foodLack(planet, context));
    }

    /**
     * Доход колонии в кредитах за ход — п. 10.
     * <p>
     * Как в MOO II: налог с жителей, поднятый космопортом и биржей, плюс выручка от
     * продажи излишков еды и товаров, минус содержание зданий.
     */
    public Integer income(PlanetEntity planet, ColonyContext context) {
        return context.withLeader(baseIncome(planet, context), planet, "FINANCIAL");
    }

    /**
     * Доход колонии без прибавки финансиста — п. 10. Отдельно, потому что процент лидера
     * в MOO II касается налога, а не всей суммы вместе с продажей еды; здесь это
     * упрощено до одного множителя на доход колонии, и упрощение честнее назвать, чем
     * прятать: раздельного налога у колонии в игре пока нет.
     */
    private Integer baseIncome(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        Integer food = populationCalculator.food(planet.getClimate(), jobs(planet).farmers(), effects, race);
        Integer consumption = populationCalculator.foodConsumption(planet.getPopulation(), race);

        Integer tradeGoods = ColonyProject.TRADE_GOODS.equals(planet.getProjectCode())
                ? populationCalculator.creditsFor(production(planet, effects, race))
                : 0;

        // Прирождённые торговцы (п. 7) продают вдвое дороже — и товары, и лишнюю еду.
        // Налог с жителей под эту надбавку не попадает: в MOO II она про торговлю,
        // а не про сбор податей, для которого есть своя сторона расы.
        Integer trade = withPercent(populationCalculator.creditsFor(food - consumption) + tradeGoods,
                race.tradeIncomePercent());

        // Кредиты находки идут ПОМИМО налога и торговой надбавки: в оригинале это
        // «special income» ровно в пять (золото) или десять (самоцветы) кредитов, и ни
        // от числа жителей, ни от космопорта он не зависит — п. 4.1.
        return populationCalculator.taxIncome(planet.getPopulation(), effects.incomePercent())
                + trade
                + effects.incomeFlat()
                - effects.upkeep();
    }

    /** Надбавка в процентах к уже посчитанной сумме; ноль процентов ничего не меняет. */
    private Integer withPercent(Integer amount, Integer bonusPercent) {
        return bonusPercent == 0 ? amount : (int) ((long) amount * (100 + bonusPercent) / 100);
    }

    /** Колония планеты; {@code null} — планета не заселена, и колонии на ней нет. */
    public ColonyDto colony(PlanetEntity planet, ColonyContext context) {
        if (planet.getPopulation() <= 0) {
            return null;
        }

        List<Building> buildings = context.buildings(planet);
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        PopulationJobs jobs = jobs(planet);

        // Земледелец и учёный поднимают выработку своих жителей во всей системе — п. 6.
        Integer food = context.withLeader(
                populationCalculator.food(planet.getClimate(), jobs.farmers(), effects, race),
                planet, "FARMING");
        Integer production = production(planet, context);
        Integer consumption = populationCalculator.foodConsumption(planet.getPopulation(), race);
        Building project = context.catalog().get(planet.getProjectCode());

        return new ColonyDto(
                planet.getPopulationK(),
                maxPopulation(planet, effects, race),
                growthK(planet, context),
                medicineBonusPercent(planet, context),
                housingBonusPercent(planet, production),
                effects.growthKBonus(),

                jobs.farmers(),
                jobs.workers(),
                jobs.scientists(),

                context.withLeader(
                        populationCalculator.foodPerFarmer(planet.getClimate(), race)
                                + effects.foodPerFarmer(), planet, "FARMING"),
                effects.foodFlat(),
                context.withLeader(
                        planet.getMinerals().getProductionPerWorker()
                                + effects.productionPerWorker(), planet, "LABOR"),
                effects.productionFlat(),
                effects.productionPerColonist(),
                context.withLeader(
                        populationCalculator.researchPerScientist()
                                + effects.researchPerScientist(), planet, "SCIENCE"),
                effects.researchFlat(),

                food,
                production,
                context.withLeader(
                        populationCalculator.research(jobs.scientists(), effects),
                        planet, "SCIENCE"),
                consumption,
                food - consumption,
                context.delivered(planet),
                income(planet, context),
                effects.upkeep(),

                pollution(planet, context),
                pollutionTolerance(planet, effects),
                effects.pollutionDivisor(),
                effects.pollutionFree(),

                projectCode(planet),
                projectName(planet, project, context),
                planet.getProjectPoints(),
                projectCost(planet, project, context),
                buildings.stream().map(this::toProject).toList(),
                available(planet, context),
                queue(planet, context),
                buyCost(planet, projectCost(planet, project, context)),
                planet.getSoldTurn(),
                planet.getAlienPopulation(),
                planet.getAlienPopulation() > 0
                        ? assimilationRules.turnsPerColonist(race) - planet.getAssimilationPoints()
                        : null);
    }

    /**
     * Что колония может строить прямо сейчас — п. 10.
     * <p>
     * Дома и товары доступны всегда, здание — когда его технология изучена, а само оно
     * ещё не построено на этой планете. Колониальная база — пока в системе есть свободная
     * планета, пригодная для колонизации, и предыдущая база уже пристроена: две базы
     * подряд колонии не нужны, селиться второй будет некуда.
     */
    public List<ColonyProjectDto> available(PlanetEntity planet, ColonyContext context) {
        // Занятое очередью из списка убирается: здание строится однажды, и предлагать его
        // второй раз значит обещать то, чего не будет, — п. 10.
        Set<String> queued = planet.getBuildQueue().stream()
                .filter(code -> !Boolean.TRUE.equals(ColonyProject.repeatable(code)))
                .collect(Collectors.toSet());
        // Колониальных баз колония заказывает столько, сколько в системе свободных планет:
        // база повторяемая, но каждая занимает планету, и заказанная сверх числа планет
        // осталась бы без места — п. 4.1. Считаются все заказанные: готовая, на стапеле и
        // в очереди.
        boolean baseFull = !Boolean.TRUE.equals(baseHasWhereToGo(planet));
        return buildable(planet, context).stream()
                .filter(project -> !queued.contains(project.code()))
                .filter(project -> !baseFull || !ColonyProject.COLONY_BASE.equals(project.code()))
                .toList();
    }

    /**
     * Очередь стройки колонии проектами — п. 10.
     * <p>
     * Хранится она кодами, а экрану нужны названия и цены, поэтому коды разбираются по
     * тому же списку, из которого их выбирали. Проект, пропавший из списка (здание
     * успели построить, технологию отняла кража), из очереди тоже пропадает: строить его
     * всё равно нечем, а показывать мёртвую строку незачем.
     */
    public List<ColonyProjectDto> queue(PlanetEntity planet, ColonyContext context) {
        if (planet.getBuildQueue().isEmpty()) {
            return List.of();
        }
        Map<String, ColonyProjectDto> byCode = buildable(planet, context).stream()
                .collect(Collectors.toMap(ColonyProjectDto::code, project -> project,
                        (first, second) -> first));
        return planet.getBuildQueue().stream()
                .map(byCode::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Всё, что колония может заложить, — до оглядки на очередь. */
    private List<ColonyProjectDto> buildable(PlanetEntity planet, ColonyContext context) {
        Set<String> technologies = context.technologies(planet);
        Set<String> built = context.buildings(planet).stream()
                .map(Building::code)
                .collect(Collectors.toSet());

        List<ColonyProjectDto> projects = new ArrayList<>();
        projects.add(HOUSING);
        projects.add(TRADE_GOODS);
        // Шпион доступен любой колонии с первого хода: разведка технологий не ждёт — п. 13.
        projects.add(SPY);
        /*
          Корабли — по проекту на строку, как в списке стройки MOO II: империя строит не
          «корабль вообще», а тот проект, который сама собрала в окне дизайна — п. 8.

          Два условия. Первое: у империи должны быть двигатель и топливо — базовые уровни
          Power и Chemistry; до них строить не из чего. Второе: колония поднимает корпус не
          больше того, что позволяет её верфь, — звёздная база на орбите. Без базы это
          фрегат и эсминец, с базой — что угодно.
        */
        if (Boolean.TRUE.equals(shipDesignRules.shipbuildingAvailable(technologies))) {
            Integer maxHullSize = shipDesignRules.maxHullSize(built.contains(ShipDesignRules.STAR_BASE));
            for (ShipDesignService.ShipBuildOption design : context.shipDesigns(planet)) {
                if (design.hullSize() <= maxHullSize) {
                    projects.add(shipProject(design));
                }
            }
        }
        // Грузовой флот — только после своей технологии, как в MOO II: до неё возить еду
        // между колониями нечем — п. 4.1.1.
        if (technologies.contains(ColonyProject.FREIGHTER_TECH)) {
            projects.add(FREIGHTER);
        }
        // База предлагается, пока в системе есть куда селиться. Готовая, но ещё не
        // пристроенная база списку не мешает: колония вправе заложить следующую, а
        // достроит её только после того, как игрок распорядится нынешней, — иначе
        // заселение системы из нескольких планет шло бы по одной базе за раз и с
        // обязательным заходом в список стройки между ними.
        if (hasFreePlanet(planet)) {
            projects.add(COLONY_BASE);
        }
        /*
          Гражданские корабли — п. 4.1 и п. 12. В MOO II они стоят в списке стройки готовыми,
          со своей твёрдой ценой, и собирать их в окне дизайна нельзя. Верфи они не требуют:
          корпус у них наименьший.

          Условий два, и второе появилось не сразу: мало уметь строить корабли вообще
          (базовые Power и Chemistry) — нужна ещё сама технология корабля. Каждый из трёх
          стоит в дереве отдельной строкой (Power, уровень 2 «Cold Fusion»), и без неё
          колониальный корабль можно было заложить до того, как он изучен: империя
          расселялась технологией, которой у неё нет. Уровень общий, поэтому на деле все
          три открываются разом, но спрашивается у каждого своя.
        */
        if (Boolean.TRUE.equals(shipDesignRules.shipbuildingAvailable(technologies))) {
            if (technologies.contains(ColonyProject.COLONY_SHIP_TECH)) {
                projects.add(COLONY_SHIP);
            }
            if (technologies.contains(ColonyProject.OUTPOST_SHIP_TECH)) {
                projects.add(OUTPOST_SHIP);
            }
            if (technologies.contains(ColonyProject.TRANSPORT_TECH)) {
                projects.add(TRANSPORT);
            }
        }
        for (Building building : context.catalog().values()) {
            if (!built.contains(building.code()) && researched(building, technologies)) {
                projects.add(toProject(building));
            }
        }
        return projects;
    }

    /**
     * Перераспределение жителей колонии игроком — п. 4.1.
     * <p>
     * Сумма занятий обязана сойтись с населением: незанятых жителей в колонии нет, и
     * «потерять» человека при перераспределении нельзя.
     */
    @Transactional
    public PlanetEntity setPopulation(PlayerEntity player, UUID planetId, SetPopulationRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);

        PopulationJobs jobs = new PopulationJobs(request.farmers(), request.workers(), request.scientists());
        if (!jobs.total().equals(planet.getPopulation())) {
            throw new ConflictException("colony.jobsMismatch", planet.getName(), planet.getPopulation(), jobs.total());
        }

        planet.setJobs(jobs);
        log.info("Игрок {} перераспределил {}: {} фермеров, {} рабочих, {} учёных",
                player.getName(), planet.getName(), jobs.farmers(), jobs.workers(), jobs.scientists());
        return planet;
    }

    /**
     * Во что обойдётся выкуп текущей стройки — п. 10; {@code null}, когда выкупать нечего.
     * <p>
     * Дома и товары не кончаются никогда, и выкупать в них нечего; у оплаченной стройки
     * недостающего не осталось. Само правило цены — в {@code PopulationCalculator.buyCost}.
     */
    private Integer buyCost(PlanetEntity planet, Integer cost) {
        if (cost == null || planet.getProjectPoints() >= cost) {
            return null;
        }
        return populationCalculator.buyCost(cost, planet.getProjectPoints());
    }

    /**
     * Выкуп стройки за кредиты — п. 10.
     * <p>
     * В MOO II недостающее производство докупают, и вещь достраивается тем же ходом:
     * «construction is completed when you click the Turn button». Здесь так же — казна
     * платит за недостающие единицы, они ложатся в стройку, а достраивает её та же фаза
     * производства, что и всегда: своего пути у купленного нет.
     * <p>
     * Дома и товары не выкупаются: они не кончаются вовсе, и «достроить» их нельзя.
     */
    @Transactional
    public PlanetEntity buyProject(PlayerEntity player, UUID planetId) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        ColonyContext context = context(planet);
        Building project = context.catalog().get(planet.getProjectCode());
        Integer cost = projectCost(planet, project, context);
        if (cost == null) {
            throw new ConflictException("colony.nothingToBuy", planet.getName());
        }

        Integer price = buyCost(planet, cost);
        if (price == null) {
            throw new ConflictException("colony.alreadyPaid", planet.getName());
        }
        if (player.getCredits() < price) {
            throw new ConflictException("colony.buyNoCredits", price, player.getCredits());
        }

        player.setCredits(player.getCredits() - price);
        playerRepository.save(player);
        planet.setProjectPoints(cost);
        planetRepository.save(planet);

        log.info("Игрок {} выкупил стройку колонии {} за {} кр.",
                player.getName(), planet.getName(), price);
        return planet;
    }

    /**
     * Во что обойдётся выкуп стройки этой колонии прямо сейчас — п. 10.
     * <p>
     * Отличается от {@link #buyProject} только тем, откуда берётся контекст: здесь его даёт
     * ход ({@code TurnContext}), а не своя выборка. Нужно это империям ИИ — они решают,
     * что выкупить, внутри посчитанного хода, и ходить за контекстом колонии на каждую
     * проверку значило бы бить по всему ходу (см. «Грабли» в CLAUDE.md).
     * <p>
     * {@code null} — выкупать нечего: стройка пуста, бесконечна или уже оплачена.
     */
    public Integer buyPrice(PlanetEntity planet, ColonyContext context) {
        Building project = context.catalog().get(planet.getProjectCode());
        return buyCost(planet, projectCost(planet, project, context));
    }

    /**
     * Выкуп стройки из фазы хода — п. 10: та же покупка, что у игрока, но по контексту хода.
     * <p>
     * Цену считает {@link #buyPrice}, и второго свода правил выкупа в проекте нет: казна
     * платит за недостающие единицы, они ложатся в стройку, а достраивает её та же фаза
     * производства, что и обычно.
     *
     * @return {@code true} — выкуп состоялся
     */
    public Boolean buyFromTurn(PlayerEntity player, PlanetEntity planet, ColonyContext context) {
        Integer price = buyPrice(planet, context);
        if (price == null || player.getCredits() < price) {
            return Boolean.FALSE;
        }
        Building project = context.catalog().get(planet.getProjectCode());
        Integer cost = projectCost(planet, project, context);
        player.setCredits(player.getCredits() - price);
        planet.setProjectPoints(cost);
        log.info("Империя {} выкупила стройку колонии {} за {} кр.",
                player.getName(), planet.getName(), price);
        return Boolean.TRUE;
    }

    /**
     * Продажа постройки — п. 10.
     * <p>
     * В MOO II построенное можно продать: здание исчезает с планеты, казна получает
     * половину его цены в кредитах, а колония перестаёт платить за него содержание.
     * Продаётся <b>одно здание за ход и на каждой колонии своё</b> — предел оригинала;
     * общего счётчика у империи нет.
     * <p>
     * <b>Продажа рушит и планы.</b> Здание могло быть условием стройки: звёздная база
     * поднимает корпуса крупнее эсминца, и без неё заложенный дредноут строить нечем.
     * Поэтому после продажи очередь и текущая стройка сверяются со списком доступного, и
     * то, что стало невозможным, из них уходит — как в оригинале, где «продажа здания
     * убирает из очереди зависевшие от него изделия» (руководство к патчу 1.50).
     * Накопленное производство при этом остаётся колонии: оно лежит на ней, а не на
     * проекте.
     *
     * @param turn ход партии: им отмечается, что колония своё за этот ход уже продала
     */
    @Transactional
    public PlanetEntity sellBuilding(PlayerEntity player, UUID planetId,
                                     String buildingCode, Integer turn) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        if (turn.equals(planet.getSoldTurn())) {
            throw new ConflictException("colony.soldThisTurn", planet.getName());
        }

        PlanetBuildingEntity built = planetBuildingRepository.findAllByPlanetId(planetId).stream()
                .filter(row -> row.getBuildingCode().equals(buildingCode))
                .findFirst()
                .orElseThrow(() -> new ConflictException("colony.noSuchBuilding", planet.getName(), buildingCode));
        Building building = buildingCatalog.byCode().get(buildingCode);
        if (building == null) {
            throw new ConflictException("colony.unknownBuilding", buildingCode);
        }

        Integer paid = populationCalculator.sellValue(building.cost());
        planetBuildingRepository.delete(built);
        planet.setSoldTurn(turn);
        player.setCredits(player.getCredits() + paid);
        playerRepository.save(player);

        dropImpossibleProjects(planet);

        log.info("Игрок {} продал {} на колонии {} за {} кр.",
                player.getName(), building.name(), planet.getName(), paid);
        return planet;
    }

    /**
     * Убирает из стройки и очереди то, что после продажи строить нечем, — п. 10.
     * <p>
     * Список доступного и есть последнее слово о том, что колония может построить, — по
     * нему и сверяемся. Особые проекты (дома, товары, шпион) в этом списке стоят всегда и
     * потому не пострадают; уходит то, чьё условие продано, — прежде всего крупные
     * корабли, которым нужна звёздная база.
     */
    private void dropImpossibleProjects(PlanetEntity planet) {
        // Сверяемся с полным списком строимого, а не с `available`: тот убирает уже
        // поставленное в очередь, и очередь вычистила бы сама себя до пустоты.
        Set<String> possible = buildable(planet, context(planet)).stream()
                .map(ColonyProjectDto::code)
                .collect(Collectors.toSet());

        String current = planet.getProjectCode() == null ? null
                : ColonyProject.SHIP.equals(planet.getProjectCode())
                ? ColonyProject.ship(planet.getProjectDesignId())
                : planet.getProjectCode();
        if (current != null && !possible.contains(current)) {
            // Пустой стройки у колонии не бывает: то, что строить стало нечем, сменяется
            // товарами — производство пойдёт в казну, а не пропадёт (п. 10).
            planet.setProjectCode(ColonyProject.TRADE_GOODS);
            planet.setProjectDesignId(null);
        }

        // Очередь правится копией, а не на месте: у колонки свой преобразователь, и
        // правку того же списка Hibernate не заметил бы (см. `enqueue`).
        List<String> queue = planet.getBuildQueue().stream()
                .filter(possible::contains)
                .collect(Collectors.toCollection(ArrayList::new));
        if (queue.size() != planet.getBuildQueue().size()) {
            planet.setBuildQueue(queue);
        }
    }

    /**
     * Смена проекта колонии — п. 10.
     * <p>
     * Вложенные единицы производства остаются на колонии: в MOO II накопленное не
     * пропадает при смене стройки, а идёт в следующую.
     * <p>
     * Сверяется проект со <b>списком строимого</b> ({@code buildable}), а не с
     * {@code available}: последний убирает уже поставленное в очередь, и проект из
     * очереди нельзя было бы поднять на стапель — а именно этого от списка и ждут, когда
     * передумали. Поднятый из очереди проект из неё и уходит: строить его дважды никто
     * не просил.
     */
    @Transactional
    public PlanetEntity setProject(PlayerEntity player, UUID planetId, SetProjectRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);

        ColonyProjectDto project = buildable(planet, context(planet)).stream()
                .filter(candidate -> candidate.code().equals(request.projectCode()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("colony.cannotBuild", planet.getName(), request.projectCode()));

        // У корабля код несёт проект, а планета хранит их раздельно: код стройки остаётся
        // «SHIP», проект уходит в свою колонку. Так переделка проекта в окне дизайна не
        // трогает того, что уже стоит на стапеле, — п. 8.
        // База со стапеля тоже занимает планету: заказать её сверх числа свободных нельзя.
        // Поднятая из очереди не в счёт — она уже заказана и лишь меняет место.
        if (ColonyProject.COLONY_BASE.equals(project.code())
                && !planet.getBuildQueue().contains(ColonyProject.COLONY_BASE)) {
            requireRoomForBase(planet);
        }

        UUID designId = ColonyProject.shipDesign(project.code());
        planet.setProjectCode(designId == null ? project.code() : ColonyProject.SHIP);
        planet.setProjectDesignId(designId);
        removeFirstFromQueue(planet, project.code());

        log.info("Колония {} игрока {} строит {}", planet.getName(), player.getName(), project.name());
        return planet;
    }

    /**
     * Убирает из очереди первый такой проект — п. 10: поднятый на стапель из очереди
     * стоять в ней же больше не должен.
     * <p>
     * Правится очередь копией, а не на месте: у колонки свой преобразователь, и правку
     * того же списка Hibernate не заметил бы вовсе.
     */
    private void removeFirstFromQueue(PlanetEntity planet, String projectCode) {
        int at = planet.getBuildQueue().indexOf(projectCode);
        if (at < 0) {
            return;
        }
        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        queue.remove(at);
        planet.setBuildQueue(queue);
    }

    /**
     * Добавить проект в очередь стройки — п. 10.
     * <p>
     * Очередь MOO II: колония строит одно, а что за ним — решено заранее, и производство
     * не простаивает ход, пока игрок выбирает. Если строить нечего вовсе, проект встаёт
     * не в очередь, а прямо на стапель: очередь за пустой стройкой ждала бы вечно —
     * забирают из неё только тогда, когда предыдущее достроено.
     * <p>
     * Больше {@link ColonyProject#QUEUE_LIMIT} проектов очередь не принимает, как и в
     * оригинале.
     */
    @Transactional
    public PlanetEntity enqueue(PlayerEntity player, UUID planetId, SetProjectRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        // Про базу отказываем своими словами: общий отказ списка стройки назвал бы причиной
        // технологию, а дело в том, что селить больше некуда — п. 4.1.
        if (ColonyProject.COLONY_BASE.equals(request.projectCode())) {
            requireRoomForBase(planet);
        }
        // Стапеля не держат ни пустая стройка, ни бесконечная: дома и товары не кончаются
        // никогда, а очередь забирают только за достроенным — поставленное за домами ждало
        // бы вечно, и колония на глазах игрока «не слушалась» бы вовсе. Поэтому новый
        // проект встаёт на их место сразу.
        //
        // В очередь бесконечная стройка при этом не уходит: вернувшись в неё, она заперла
        // бы всё, что стоит следом, — а товары колония берёт сама, как только строить
        // становится нечего (ProductionPhase).
        if (planet.getProjectCode() == null
                || Boolean.TRUE.equals(ColonyProject.endless(planet.getProjectCode()))) {
            return setProject(player, planetId, request);
        }

        ColonyProjectDto project = available(planet, context(planet)).stream()
                .filter(candidate -> candidate.code().equals(request.projectCode()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("colony.cannotQueue", planet.getName(), request.projectCode()));
        // То, что уже на стапеле, в очередь не ставят: здание строится однажды, и очередь
        // просто отменила бы его на следующем ходу. Корабли и шпионов это не касается —
        // их строят сколько угодно раз.
        if (!Boolean.TRUE.equals(ColonyProject.repeatable(project.code()))
                && project.code().equals(planet.getProjectCode())) {
            throw new ConflictException("colony.alreadyBuilding", planet.getName(), project.name());
        }
        if (planet.getBuildQueue().size() >= ColonyProject.QUEUE_LIMIT) {
            throw new ConflictException("colony.queueFull", planet.getName(), ColonyProject.QUEUE_LIMIT);
        }

        // Очередь правится копией, а не на месте: у колонки свой преобразователь, и
        // Hibernate сравнивает поле с тем же самым списком, который отдал при загрузке.
        // Правка на месте меняет и его — изменения просто не видно, и в базу оно не идёт.
        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        if (Boolean.TRUE.equals(request.top())) {
            // В голову очереди — так ставит список колоний: там проект задают сразу многим
            // колониям, и ждать за всем, что у каждой уже набрано, он не может.
            queue.add(0, project.code());
        } else {
            queue.add(project.code());
        }
        planet.setBuildQueue(queue);

        log.info("Колония {} игрока {} поставила в очередь {}{}",
                planet.getName(), player.getName(), project.name(),
                Boolean.TRUE.equals(request.top()) ? " первым" : "");
        return planet;
    }

    /**
     * Поставить один и тот же проект в очередь сразу многим колониям — п. 10.
     * <p>
     * Так распоряжаются империей из списка колоний: изучив автолабораторию, её закладывают
     * всем разом, а не обходят колонии по одной. Проект должен быть доступен <b>каждой</b>
     * колонии набора — иначе отказ с именем той, которой он не по силам: наполовину
     * выполненный приказ хуже невыполненного, игрок не увидит, кому он не достался.
     * <p>
     * Порядок разбирается в {@link #enqueue}: из списка колоний проект идёт в голову
     * очереди.
     */
    @Transactional
    public List<PlanetEntity> enqueueAll(PlayerEntity player, List<UUID> planetIds,
                                         SetProjectRequest request) {
        List<PlanetEntity> planets = new ArrayList<>(planetIds.size());
        for (UUID planetId : planetIds) {
            planets.add(enqueue(player, planetId, request));
        }
        return planets;
    }

    /**
     * Убрать проект из очереди — п. 10.
     * <p>
     * Вложенного производства при этом не теряется: единицы лежат на самой колонии, а не
     * на проекте, и уходят в то, что колония строит следующим.
     */
    @Transactional
    public PlanetEntity removeFromQueue(PlayerEntity player, UUID planetId, Integer index) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        requireQueueIndex(planet, index);

        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        String removed = queue.remove(index.intValue());
        planet.setBuildQueue(queue);

        log.info("Колония {} игрока {} убрала из очереди {}",
                planet.getName(), player.getName(), removed);
        return planet;
    }

    /**
     * Переставить проект в очереди — п. 10: в оригинале очередь не только набирают, но и
     * переупорядочивают, когда становится ясно, что нужнее.
     */
    @Transactional
    public PlanetEntity moveInQueue(PlayerEntity player, UUID planetId,
                                    Integer index, Integer toIndex) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        requireQueueIndex(planet, index);
        requireQueueIndex(planet, toIndex);

        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        String moved = queue.remove(index.intValue());
        queue.add(toIndex, moved);
        planet.setBuildQueue(queue);

        log.info("Колония {} игрока {} передвинула {} с {} на {} место",
                planet.getName(), player.getName(), moved, index + 1, toIndex + 1);
        return planet;
    }

    /** Место в очереди должно существовать: пустого хвоста у неё нет. */
    private void requireQueueIndex(PlanetEntity planet, Integer index) {
        if (index < 0 || index >= planet.getBuildQueue().size()) {
            throw new ConflictException("colony.queueNoIndex", planet.getName(), (index + 1), planet.getBuildQueue().size());
        }
    }

    /**
     * Заселение планеты готовой колониальной базой — п. 4.1.
     * <p>
     * Базу строит колония, а заселяет она соседнюю планету своей системы: лететь базе
     * никуда не нужно, поэтому выбор ограничен системой. Новая колония начинается с
     * одного жителя, как в MOO II, и с распределения по умолчанию — дальше это дело
     * игрока. Стройка ей достаётся та же, с какой начинают родные миры: колония без
     * стройки производит впустую.
     */
    @Transactional
    public PlanetEntity colonize(PlayerEntity player, UUID planetId, ColonizeRequest request) {
        PlanetEntity base = requireOwnPlanet(player, planetId);
        if (!Boolean.TRUE.equals(base.getColonyBaseReady())) {
            throw new ConflictException("colony.baseNotReady", base.getName());
        }

        PlanetEntity target = planetRepository.findById(request.targetPlanetId())
                .orElseThrow(() -> new NotFoundException("planet.notFound", request.targetPlanetId()));
        if (!target.getStarSystem().getId().equals(base.getStarSystem().getId())) {
            throw new ConflictException("colony.baseSameSystem");
        }
        if (!free(target)) {
            throw new ConflictException("colony.cannotSettle", target.getName());
        }

        target.setOwnerPlayerId(player.getId());
        target.setPopulation(1);
        target.setJobs(populationCalculator.defaultJobs(target.getClimate(), target.getPopulation()));
        target.setProjectCode(ColonyProject.TRADE_GOODS);
        target.setProjectPoints(0);
        base.setColonyBaseReady(Boolean.FALSE);
        planetRepository.saveAll(List.of(base, target));

        // Колониальная база — второй способ основать колонию, и для замера он не менее
        // важен, чем корабль: в тесной галактике ИИ расселяется почти только ею, и
        // счётчик, знавший лишь о корабле, показывал ноль колоний на партии, где их было
        // с десяток.
        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.COLONIZED);
        log.info("Игрок {} заселил {} колониальной базой с {}",
                player.getName(), target.getName(), base.getName());
        return target;
    }

    /** Планета по идентификатору — для сборки ответа после действий над ней. */
    public PlanetEntity planet(UUID planetId) {
        return planetRepository.findById(planetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", planetId));
    }

    /** Есть ли в системе планеты, которые колониальной базе есть смысл заселять. */
    private Boolean hasFreePlanet(PlanetEntity planet) {
        return freePlanets(planet) > 0;
    }

    /** Сколько в системе планет, которые колониальной базе есть смысл заселять. */
    private Integer freePlanets(PlanetEntity planet) {
        return (int) planet.getStarSystem().getPlanets().stream().filter(this::free).count();
    }

    /**
     * Сколько колониальных баз колония уже заказала — п. 4.1: готовая, строящаяся и
     * стоящие в очереди.
     * <p>
     * Каждая из них займёт по свободной планете, и больше, чем таких планет в системе,
     * заказывать нечего: лишняя база осталась бы без места, а производство на неё ушло бы.
     */
    private Integer basesOrdered(PlanetEntity planet) {
        int ready = Boolean.TRUE.equals(planet.getColonyBaseReady()) ? 1 : 0;
        int building = ColonyProject.COLONY_BASE.equals(planet.getProjectCode()) ? 1 : 0;
        int queued = (int) planet.getBuildQueue().stream()
                .filter(ColonyProject.COLONY_BASE::equals)
                .count();
        return ready + building + queued;
    }

    /** Есть ли куда девать ещё одну базу: свободных планет больше, чем уже заказано баз. */
    private Boolean baseHasWhereToGo(PlanetEntity planet) {
        return basesOrdered(planet) < freePlanets(planet);
    }

    /**
     * Отказ с причиной, когда база очередной колонии уже не нужна, — п. 4.1.
     * <p>
     * Общий отказ списка стройки («здание уже построено или его технология не изучена»)
     * про базу врёт: дело не в технологии, а в том, что заселять больше нечего.
     */
    private void requireRoomForBase(PlanetEntity planet) {
        if (Boolean.TRUE.equals(baseHasWhereToGo(planet))) {
            return;
        }
        Integer free = freePlanets(planet);
        throw free == 0
                ? new ConflictException("colony.noRoomForBase", planet.getName())
                : new ConflictException("colony.basesAlreadyOrdered", planet.getName(), free);
    }

    /** Планета свободна: ничья, незаселённая и пригодная для жизни — п. 4.1.2. */
    private Boolean free(PlanetEntity planet) {
        return planet.getOwnerPlayerId() == null
                && planet.getPopulation() <= 0
                && Boolean.TRUE.equals(planet.getClimate().getColonizable());
    }

    private PlanetEntity requireOwnPlanet(PlayerEntity player, UUID planetId) {
        PlanetEntity planet = planetRepository.findById(planetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", planetId));
        if (!player.getId().equals(planet.getOwnerPlayerId())) {
            throw new ForbiddenException("planet.notYours", planet.getName());
        }
        return planet;
    }

    /** Здание без технологии доступно сразу; с технологией — только после её изучения. */
    private Boolean researched(Building building, Set<String> technologies) {
        return building.requiredTechCode() == null || technologies.contains(building.requiredTechCode());
    }

    /**
     * Код стройки для клиента. У корабля он собирается обратно из кода и проекта: список
     * доступного различает корабли именно так, и текущая стройка должна совпадать с
     * одной из его строк.
     */
    private String projectCode(PlanetEntity planet) {
        if (ColonyProject.SHIP.equals(planet.getProjectCode()) && planet.getProjectDesignId() != null) {
            return ColonyProject.ship(planet.getProjectDesignId());
        }
        return planet.getProjectCode();
    }

    private String projectName(PlanetEntity planet, Building building, ColonyContext context) {
        String projectCode = planet.getProjectCode();
        if (ColonyProject.isShip(projectCode)) {
            return "Корабль: " + shipDesign(planet, context)
                    .map(ShipDesignService.ShipBuildOption::name)
                    .orElse("проект снят с постройки");
        }
        if (ColonyProject.FREIGHTER.equals(projectCode)) {
            return FREIGHTER.name();
        }
        if (ColonyProject.HOUSING.equals(projectCode)) {
            return HOUSING.name();
        }
        if (ColonyProject.TRADE_GOODS.equals(projectCode)) {
            return TRADE_GOODS.name();
        }
        if (ColonyProject.COLONY_BASE.equals(projectCode)) {
            return COLONY_BASE.name();
        }
        if (ColonyProject.SPY.equals(projectCode)) {
            return SPY.name();
        }
        if (ColonyProject.COLONY_SHIP.equals(projectCode)) {
            return COLONY_SHIP.name();
        }
        if (ColonyProject.OUTPOST_SHIP.equals(projectCode)) {
            return OUTPOST_SHIP.name();
        }
        if (ColonyProject.TRANSPORT.equals(projectCode)) {
            return TRANSPORT.name();
        }
        return building == null ? null : building.name();
    }

    /**
     * Во что обойдётся стройка. У домов и товаров стоимости нет вовсе — они не
     * заканчиваются; у колониальной базы она своя, в справочнике зданий её нет.
     */
    private Integer projectCost(PlanetEntity planet, Building building, ColonyContext context) {
        String projectCode = planet.getProjectCode();
        if (ColonyProject.isShip(projectCode)) {
            // Цена корабля — цена его проекта. Проект могли убрать из ячейки, пока корабль
            // строился: тогда цены нет, и стройку игрок выберет заново.
            return shipDesign(planet, context)
                    .map(ShipDesignService.ShipBuildOption::cost)
                    .orElse(null);
        }
        if (ColonyProject.COLONY_BASE.equals(projectCode)) {
            return ColonyProject.COLONY_BASE_COST;
        }
        if (ColonyProject.SPY.equals(projectCode)) {
            return ColonyProject.SPY_COST;
        }
        if (ColonyProject.FREIGHTER.equals(projectCode)) {
            return ColonyProject.FREIGHTER_COST;
        }
        if (ColonyProject.COLONY_SHIP.equals(projectCode)) {
            return ColonyProject.COLONY_SHIP_COST;
        }
        if (ColonyProject.OUTPOST_SHIP.equals(projectCode)) {
            return ColonyProject.OUTPOST_SHIP_COST;
        }
        if (ColonyProject.TRANSPORT.equals(projectCode)) {
            return ColonyProject.TRANSPORT_COST;
        }
        return building == null ? null : building.cost();
    }

    /**
     * Проект корабля строкой списка стройки — п. 8. Код несёт с собой идентификатор
     * проекта: кораблей у империи столько же, сколько проектов, и различать их в списке
     * больше нечем.
     */
    private ColonyProjectDto shipProject(ShipDesignService.ShipBuildOption design) {
        return new ColonyProjectDto(
                ColonyProject.ship(design.designId()),
                "Корабль: " + design.name(),
                design.hullName() + ", залп " + design.attack() + ", защита " + design.defense()
                        + ". Готовый корабль встаёт во флот этой системы; флотом игрок"
                        + " распоряжается сам. Зданием не становится.",
                design.cost(), 0, Boolean.TRUE, null,
                ColonyProject.repeatable(ColonyProject.SHIP));
    }

    /** Проект корабля, который строит колония; пусто — проект убран из ячейки. */
    private Optional<ShipDesignService.ShipBuildOption> shipDesign(PlanetEntity planet,
                                                                  ColonyContext context) {
        UUID designId = planet.getProjectDesignId();
        if (designId == null) {
            return Optional.empty();
        }
        return context.shipDesigns(planet).stream()
                .filter(option -> option.designId().equals(designId))
                .findFirst();
    }

    /**
     * Здание строкой списка — п. 10. Цена продажи считается здесь же: игрок видит её и в
     * окне продажи, и заранее, выбирая, что строить, — правило одно, и живёт оно на
     * сервере ({@code PopulationCalculator.sellValue}).
     */
    private ColonyProjectDto toProject(Building building) {
        return new ColonyProjectDto(
                building.code(),
                building.name(),
                building.description(),
                building.cost(),
                building.upkeep(),
                Boolean.FALSE,
                populationCalculator.sellValue(building.cost()),
                // Здание на планете одно: второй раз его не построить, и в очередь второй
                // раз оно не встаёт.
                Boolean.FALSE);
    }
}
