package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.WeaponModification;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.ShipDesignComponentEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.ShipRole;
import com.moo3.server.dto.SaveShipDesignRequest;
import com.moo3.server.dto.ShipCatalogDto;
import com.moo3.server.dto.ShipComponentDto;
import com.moo3.server.dto.ShipDesignComponentDto;
import com.moo3.server.dto.ShipDesignDto;
import com.moo3.server.dto.WeaponModificationDto;
import com.moo3.server.dto.ShipEffectDto;
import com.moo3.server.dto.ShipHullDto;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.repository.ShipDesignComponentRepository;
import com.moo3.server.repository.ShipDesignRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Проекты кораблей империи — п. 8, окно Ship Design MOO II.
 * <p>
 * <b>Ячейки.</b> У империи шесть ячеек проектов. Игрок переделывает ячейку сколько
 * угодно, но построенные корабли остаются прежними: старый проект помечается
 * {@code obsolete} и продолжает жить у флота, а новый занимает ячейку и с этого хода
 * строится колониями. Так переделка проекта не меняет задним числом корабли, которые
 * давно в строю.
 * <p>
 * <b>Что можно поставить.</b> Только то, чья технология изучена. Проверка идёт здесь, а не
 * на клиенте: экран показывает недоступное серым, но верить ему нельзя.
 * <p>
 * <b>Числа.</b> Все характеристики считает {@link ShipDesignRules} из справочника
 * {@link ShipCatalog}. Здесь остаются доступ, проверки и сборка ответа.
 */
@Service
public class ShipDesignService {

    private static final Logger log = LoggerFactory.getLogger(ShipDesignService.class);

    /** Ячеек проектов у империи — как шесть строк в окне дизайна MOO II. */
    public static final Integer MAX_DESIGNS = 6;

    /** Имя стартового проекта: с ним империя входит в партию. */
    /**
     * Имя проекта по умолчанию — п. 3.5: ХРАНИТСЯ в базе, поэтому английское. Имя, взятое
     * на языке того, кто нажал кнопку, показывалось бы соседу по партии как есть.
     */
    private static final String DEFAULT_DESIGN_NAME = "Frigate";

    /**
     * Служебная ячейка гражданских кораблей — п. 8.
     * <p>
     * Ноль, а не одна из шести: в MOO II колониальный корабль и транспорт стоят в списке
     * стройки готовыми и ячейку дизайна не занимают. Окно дизайна показывает ячейки с
     * первой, поэтому нулевая в него и не попадает.
     */
    public static final Integer CIVIL_SLOT = 0;

    private static final String COLONY_SHIP_NAME = "Colony Ship";

    private static final String OUTPOST_SHIP_NAME = "Outpost Ship";

    private static final String TRANSPORT_NAME = "Transport";

    private final Messages messages;
    private final ShipCatalog shipCatalog;
    private final ShipDesignRules shipDesignRules;
    private final ShipDesignRepository shipDesignRepository;
    private final ShipDesignComponentRepository shipDesignComponentRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final RaceService raceService;

    /** Правила обороны колонии — п. 8, п. 11: какие корпуса бывают платформами и в каких ячейках. */
    private final OrbitalDefenceRules orbitalDefenceRules;

    /** Дерево технологий: по нему код нужной технологии превращается в её название. */
    private final ResearchCatalog researchCatalog;

    /**
     * Чего не хватает для кораблестроения — словами игроку, на его языке; {@code null},
     * когда строить уже можно. Список недостающего знает правило, текст — словарь.
     */
    private String shipbuildingRequirement(Set<String> technologies) {
        List<String> missing = shipDesignRules.missingShipbuildingTechnologies(technologies);
        return missing.isEmpty() ? null : messages.get("ship.noShipbuilding", String.join(", ", missing));
    }

    public ShipDesignService(ShipCatalog shipCatalog,
                             Messages messages,
                             ShipDesignRules shipDesignRules,
                             ShipDesignRepository shipDesignRepository,
                             ShipDesignComponentRepository shipDesignComponentRepository,
                             PlayerTechnologyRepository playerTechnologyRepository,
                             RaceService raceService,
                             OrbitalDefenceRules orbitalDefenceRules,
                             ResearchCatalog researchCatalog) {
        this.orbitalDefenceRules = orbitalDefenceRules;
        this.researchCatalog = researchCatalog;
        this.messages = messages;
        this.shipCatalog = shipCatalog;
        this.shipDesignRules = shipDesignRules;
        this.shipDesignRepository = shipDesignRepository;
        this.shipDesignComponentRepository = shipDesignComponentRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.raceService = raceService;
    }

    /**
     * Что империя может поставить в проект прямо сейчас — п. 8.
     * <p>
     * Пока не изучены базовые уровни Power и Chemistry, окно дизайна открывать не над чем:
     * ни двигателя, ни топлива у империи нет. Справочник всё равно приходит целиком —
     * экран показывает, чего ждать, а не пустоту.
     */
    @Transactional(readOnly = true)
    public ShipCatalogDto catalog(PlayerEntity player) {
        Set<String> technologies = technologies(player);

        List<ShipHullDto> hulls = shipCatalog.hulls().stream()
                // Платформы обороны в окне дизайна не показываются вовсе: их не собирают,
                // а строят зданием колонии, и вооружает их игра сама — п. 8, п. 11.
                .filter(hull -> !Boolean.TRUE.equals(hull.platform()))
                .map(hull -> new ShipHullDto(
                        hull.code(),
                        hull.name(),
                        hull.description(),
                        hull.sortOrder(),
                        hull.space(),
                        hull.cost(),
                        hull.structure(),
                        hull.command(),
                        hull.hitChancePercent(),
                        hull.systemFactor(),
                        hull.requiredTechCode(),
                        techName(hull.requiredTechCode()),
                        researched(hull.requiredTechCode(), technologies)))
                .toList();

        List<ShipComponentDto> components = shipCatalog.components().stream()
                .map(component -> new ShipComponentDto(
                        component.code(),
                        component.name(),
                        component.description(),
                        component.slot(),
                        component.space(),
                        component.cost(),
                        component.slot().scalesWithHull(),
                        component.weaponKind(),
                        component.envelops(),
                        component.requiredTechCode(),
                        techName(component.requiredTechCode()),
                        researched(component.requiredTechCode(), technologies),
                        effectLines(component),
                        shipCatalog.modifications().stream()
                                .filter(modification ->
                                        Boolean.TRUE.equals(component.carries(modification)))
                                .map(WeaponModification::code)
                                .toList()))
                .toList();

        List<WeaponModificationDto> modifications = shipCatalog.modifications().stream()
                .map(modification -> new WeaponModificationDto(
                        modification.code(),
                        modification.name(),
                        modification.description(),
                        modification.damagePercent(),
                        modification.attackPercent(),
                        modification.shotsPercent(),
                        modification.rangePercent(),
                        modification.costPercent(),
                        modification.spacePercent(),
                        modification.piercesArmour(),
                        modification.piercesShield(),
                        modification.envelops(),
                        modification.noRangePenalty(),
                        modification.halvesEvasion(),
                        modification.missileArmourPercent(),
                        modification.missileSpeed(),
                        modification.hitsEngine(),
                        modification.requiredTechCode(),
                        researched(modification.requiredTechCode(), technologies)))
                .toList();

        return new ShipCatalogDto(hulls, components, modifications, MAX_DESIGNS,
                shipDesignRules.shipbuildingAvailable(technologies),
                shipbuildingRequirement(technologies),
                ShipDesignRules.HULL_SIZE_WITHOUT_STAR_BASE);
    }

    /**
     * Проект корабля как вариант стройки колонии — п. 8 и п. 10.
     *
     * @param cost во что обойдётся корабль колонии, единиц производства
     */
    public record ShipBuildOption(
            UUID designId,
            String name,
            String hullName,
            /** Размер корпуса 1..6: без звёздной базы колония поднимает только первые два. */
            Integer hullSize,
            Integer cost,
            Integer attack,
            Integer defense
    ) {
    }

    /**
     * Что колонии империй могут строить из кораблей — п. 8.
     * <p>
     * Спрашивается на весь набор колоний разом: список стройки собирается для каждой
     * планеты карты, и ходить за проектами по колонии — та же тройная выборка, от которой
     * ушёл конец хода.
     *
     * @param races расы владельцев: атака и защита проекта у разных рас разные — п. 7
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ShipBuildOption>> buildOptions(Map<UUID, RaceEffects> races) {
        if (races.isEmpty()) {
            return Map.of();
        }

        // Гражданские корабли в этот список не попадают: у них своя строка стройки и своя
        // твёрдая цена — п. 8. Иначе колониальный корабль оказался бы в списке дважды,
        // причём вторым разом по цене собранного корпуса, а не по цене оригинала.
        List<ShipDesignEntity> designs =
                shipDesignRepository.findAllByOwnerPlayerIdInAndObsoleteFalseOrderBySlotAsc(races.keySet())
                        .stream()
                        .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                        .toList();
        Map<UUID, List<ShipDesignRules.Item>> items = itemsOf(designs);

        Map<UUID, List<ShipBuildOption>> options = new LinkedHashMap<>();
        for (ShipDesignEntity design : designs) {
            ShipHull hull = shipCatalog.hull(design.getHullCode());
            ShipStats stats = shipDesignRules.stats(
                    hull,
                    items.getOrDefault(design.getId(), List.of()),
                    races.getOrDefault(design.getOwnerPlayerId(), RaceEffects.NONE));
            options.computeIfAbsent(design.getOwnerPlayerId(), key -> new ArrayList<>())
                    .add(new ShipBuildOption(design.getId(), design.getName(), hull.name(),
                            hull.sortOrder(), stats.cost(), stats.attack(), stats.defense()));
        }
        return options;
    }

    /**
     * Действующие проекты игрока — те, что стоят в шести ячейках окна дизайна.
     * <p>
     * Гражданские корабли сюда не входят: в MOO II их не собирают и не переделывают, и
     * ячейки они не занимают — п. 8.
     */
    @Transactional(readOnly = true)
    public List<ShipDesignDto> designs(PlayerEntity player) {
        List<ShipDesignEntity> designs =
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                        .stream()
                        .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                        .toList();
        return toDtos(designs, raceService.effects(player));
    }

    /**
     * Сохраняет проект в ячейку — п. 8.
     * <p>
     * Ячейка не правится на месте: прежний проект остаётся жить у построенных кораблей,
     * а новый занимает его место. Поэтому у одной ячейки со временем накапливается
     * история проектов, из которой действующий — ровно один.
     */
    @Transactional
    public ShipDesignDto save(GameEntity game, PlayerEntity player, SaveShipDesignRequest request) {
        ShipHull hull = shipCatalog.hull(request.hullCode());
        Set<String> technologies = technologies(player);
        if (!Boolean.TRUE.equals(shipDesignRules.shipbuildingAvailable(technologies))) {
            throw new ConflictException("ship.noShipbuilding",
                    String.join(", ", shipDesignRules.missingShipbuildingTechnologies(technologies)));
        }
        if (!researched(hull.requiredTechCode(), technologies)) {
            throw new ConflictException("ship.hullNotResearched", hull.name());
        }

        List<ShipDesignRules.Item> items = requireItems(request, technologies);
        validate(hull, items);

        shipDesignRepository.findByOwnerPlayerIdAndSlotAndObsoleteFalse(player.getId(), request.slot())
                .ifPresent(previous -> {
                    previous.setObsolete(Boolean.TRUE);
                    shipDesignRepository.save(previous);
                });

        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(player.getId());
        design.setSlot(request.slot());
        design.setName(request.name().trim());
        design.setHullCode(hull.code());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, items);

        log.info("Игрок {} сохранил проект «{}» в ячейку {}: корпус {}, {} компонентов",
                player.getName(), design.getName(), design.getSlot(), hull.name(), items.size());
        return toDto(design, items, raceService.effects(player));
    }

    /**
     * Убирает проект из ячейки — п. 8. Корабли, построенные по нему, остаются в строю:
     * проект помечается вытесненным, а не стирается.
     */
    @Transactional
    public void delete(PlayerEntity player, UUID designId) {
        ShipDesignEntity design = requireOwn(player, designId);
        List<ShipDesignEntity> current =
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId());
        if (current.size() <= 1) {
            throw new ConflictException("ship.lastDesign");
        }
        design.setObsolete(Boolean.TRUE);
        shipDesignRepository.save(design);
        log.info("Игрок {} убрал проект «{}» из ячейки {}", player.getName(), design.getName(), design.getSlot());
    }

    /**
     * Стартовый проект империи — п. 8: фрегат с ядерным двигателем, титановой обшивкой и
     * масс-драйверами. Ровно то, с чем раса начинает партию в MOO II, и заодно ответ на
     * вопрос, что строить колонии, пока игрок не открыл окно дизайна.
     */
    @Transactional
    public ShipDesignEntity createDefaultDesign(GameEntity game, PlayerEntity player) {
        ShipHull hull = shipCatalog.cheapestHull();
        List<ShipDesignRules.Item> items = defaultItems(hull);

        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(player.getId());
        design.setSlot(1);
        design.setName(DEFAULT_DESIGN_NAME);
        design.setHullCode(hull.code());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, items);
        return design;
    }

    /**
     * Держит шесть ячеек дизайна заполненными — п. 8.
     * <p>
     * В MOO II ячейки не пустуют: игра сама предлагает готовые проекты под нынешний
     * уровень технологий, и в списке стройки колонии всегда стоят корабли, а не пустота.
     * Здесь так же: на каждый корпус, который империя уже вправе строить, заводится свой
     * проект — фрегат, эсминец, крейсер и дальше, сколько открыто технологиями.
     * <p>
     * Собранное игрой и собранное игроком различаются признаком {@code auto}: первое игра
     * переписывает, когда изучены новые двигатель, броня или пушка, второе не трогает
     * никогда — за него отвечает игрок.
     *
     * @return действующие проекты империи после пополнения
     */
    @Transactional
    public List<ShipDesignEntity> ensureAutoDesigns(UUID gameId, PlayerEntity player, Integer turn) {
        Set<String> technologies = technologies(player);
        List<ShipDesignEntity> designs = new ArrayList<>(
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                        .stream()
                        .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                        .toList());

        List<ShipHull> hulls = shipCatalog.hulls().stream()
                // Платформы обороны в шесть ячеек не идут: они не корабли, а здания
                // колонии, и проект у них свой — см. ensurePlatformDesigns.
                .filter(hull -> !Boolean.TRUE.equals(hull.platform()))
                .filter(hull -> researched(hull.requiredTechCode(), technologies))
                .sorted(Comparator.comparing(ShipHull::sortOrder))
                .limit(MAX_DESIGNS)
                .toList();
        Set<Integer> taken = designs.stream()
                .filter(design -> !Boolean.TRUE.equals(design.getAuto()))
                .map(ShipDesignEntity::getSlot)
                .collect(Collectors.toSet());

        // СОСТАВЫ БЕРУТСЯ ОДНОЙ ВЫБОРКОЙ, а не по проекту в цикле. Ниже каждый проект
        // сверяется с тем, что даёт нынешняя наука, и прежде эта сверка ходила в базу
        // отдельно за каждым — шесть запросов на вызов. А зовут этот метод из
        // `ResearchService.store`, то есть КАЖДЫЙ РАЗ, когда империя что-нибудь изучила, и
        // изнутри посчитанного хода, где всякий запрос вдобавок заставляет Hibernate
        // сбросить в базу всё, что ход успел изменить. Выборка стеков на круге 7 показала
        // `items` и `itemsOf` вместе почти третью всего хода.
        Map<UUID, List<ShipDesignRules.Item>> current = itemsOf(designs);

        int next = 0;
        for (ShipHull hull : hulls) {
            // Ячейки игрока не трогаем вовсе: он их и занял. Проект игры под этот корпус
            // либо обновляется до нынешних технологий, либо заводится заново.
            next++;
            while (next <= MAX_DESIGNS && taken.contains(next)) {
                next++;
            }
            if (next > MAX_DESIGNS) {
                break;
            }
            final int slot = next;
            ShipDesignEntity mine = designs.stream()
                    .filter(design -> Boolean.TRUE.equals(design.getAuto()))
                    .filter(design -> design.getSlot() == slot)
                    .findFirst()
                    .orElse(null);
            List<ShipDesignRules.Item> items = defaultItems(hull, technologies);
            if (mine == null) {
                designs.add(createAutoDesign(gameId, player, turn, slot, hull, items));
            } else {
                ShipDesignEntity fresh = replaceAutoDesign(gameId, player, turn, mine, hull, items,
                        current.getOrDefault(mine.getId(), List.of()));
                if (fresh != mine) {
                    designs.remove(mine);
                    designs.add(fresh);
                }
            }
        }
        return designs;
    }

    /**
     * Держит в порядке проекты платформ обороны — п. 8, п. 11.
     * <p>
     * Платформа вооружается САМА и всегда по последнему изученному: в MOO II звёздная база
     * «обновляется новейшими технологиями» без всякой перестройки, и переделать её вручную
     * нельзя. Здесь это тот же механизм, что держит шесть ячеек дизайна, — {@code auto} и
     * {@link #defaultItems}, — только ячейки служебные и отрицательные
     * ({@link OrbitalDefenceRules#slotOf}), чтобы платформа не отнимала места у игрока и не
     * показывалась там, где её нельзя переделать.
     * <p>
     * Зовётся оттуда же, откуда обновляются ячейки дизайна, — из {@code ResearchService},
     * когда технологии и правда прибавились. Проекты заводятся на ВСЕ корпуса платформ
     * разом, даже если колония такого здания ещё не построила: проект сам по себе ничего
     * не стоит, а искать по колониям, кому что понадобится, значило бы ходить в базу
     * изнутри посчитанного хода.
     *
     * @return проекты платформ этой империи по коду корпуса
     */
    @Transactional
    public Map<String, ShipDesignEntity> ensurePlatformDesigns(UUID gameId, PlayerEntity player,
                                                               Integer turn) {
        Set<String> technologies = technologies(player);
        Map<String, ShipDesignEntity> mine =
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                        .stream()
                        .filter(design -> design.getRole() == ShipRole.PLATFORM)
                        .collect(Collectors.toMap(ShipDesignEntity::getHullCode,
                                design -> design, (first, second) -> first, LinkedHashMap::new));

        // Одной выборкой, по той же причине, что и у шести ячеек: платформ столько же,
        // и обновляются они тем же вызовом из `ResearchService.store`.
        Map<UUID, List<ShipDesignRules.Item>> current = itemsOf(mine.values());

        Map<String, ShipDesignEntity> platforms = new LinkedHashMap<>();
        for (String code : OrbitalDefenceRules.HULLS) {
            ShipHull hull = shipCatalog.hull(code);
            if (!Boolean.TRUE.equals(researched(hull.requiredTechCode(), technologies))) {
                continue;
            }
            List<ShipDesignRules.Item> items = defaultItems(hull, technologies);
            ShipDesignEntity design = mine.get(code);
            if (design == null) {
                design = createAutoDesign(gameId, player, turn,
                        orbitalDefenceRules.slotOf(code), hull, items);
                design.setRole(ShipRole.PLATFORM);
                shipDesignRepository.saveAndFlush(design);
            } else {
                refreshAutoDesign(design, hull, items,
                        current.getOrDefault(design.getId(), List.of()));
            }
            platforms.put(code, design);
        }
        return platforms;
    }

    /**
     * Проекты платформ обороны этой империи по коду корпуса — п. 8, п. 11.
     * <p>
     * Только чтение: заводит и обновляет их {@link #ensurePlatformDesigns}, которую зовут
     * исследования. Пусто — империя ещё не получила ни одной платформы (партия, начатая до
     * появления обороны, получит их с первым же прорывом).
     */
    @Transactional(readOnly = true)
    public Map<String, ShipDesignEntity> platformDesigns(PlayerEntity player) {
        return shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                .stream()
                .filter(design -> design.getRole() == ShipRole.PLATFORM)
                .collect(Collectors.toMap(ShipDesignEntity::getHullCode,
                        design -> design, (first, second) -> first, LinkedHashMap::new));
    }

    /** Заводит проект от игры: имя по корпусу, состав — лучшее из изученного. */
    private ShipDesignEntity createAutoDesign(UUID gameId, PlayerEntity player, Integer turn,
                                              Integer slot, ShipHull hull,
                                              List<ShipDesignRules.Item> items) {
        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(gameId);
        design.setOwnerPlayerId(player.getId());
        design.setSlot(slot);
        // Английское название корпуса, а не на языке запроса: имя проекта хранится в базе
        // (п. 3.5), и переименовать его игрок вправе сам.
        design.setName(hull.names().en());
        design.setHullCode(hull.code());
        design.setCreatedTurn(turn);
        design.setObsolete(Boolean.FALSE);
        design.setAuto(Boolean.TRUE);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, items);
        log.debug("Империя {} получила проект «{}» в ячейку {}", player.getName(), hull.name(), slot);
        return design;
    }

    /**
     * Подтягивает проект КОРАБЛЯ к нынешним технологиям — п. 8: новой строкой, а старая
     * помечается вытесненной.
     * <p>
     * <b>Построенный корабль остаётся с тем, с чем построен.</b> В MOO II изученная пушка
     * не появляется сама на кораблях, которые уже летают: новое ставят новым кораблям, а
     * старым — переоснащением за деньги. Раньше состав правился ПРЯМО В СТРОКЕ проекта, а
     * корабли во флоте ссылаются на неё же, — и в тот же миг, когда империя изучала лучшую
     * пушку, ВЕСЬ её построенный флот получал её даром. Измерено: построенный фрегат менял
     * залп, не потратив ни единицы производства. Прибору балансировки это врало вдвойне —
     * военная сила империи росла от одного факта исследования.
     * <p>
     * Старая строка не удаляется: по ней летают корабли, и её характеристики нужны бою и
     * слепку партии. Выборки «действующие проекты» её уже отсеивают признаком
     * {@code obsolete}, а те, что считают силу флота, намеренно берут и вытесненные.
     * <p>
     * Платформы обороны так НЕ обновляются — у них своя дорога
     * ({@link #refreshAutoDesign}): звёздная база в MOO II и правда перевооружается сама.
     *
     * @return действующий проект: прежний, если менять нечего, или новый
     */
    private ShipDesignEntity replaceAutoDesign(UUID gameId, PlayerEntity player, Integer turn,
                                               ShipDesignEntity design, ShipHull hull,
                                               List<ShipDesignRules.Item> items,
                                               List<ShipDesignRules.Item> current) {
        if (current.equals(items) && hull.code().equals(design.getHullCode())) {
            return design;
        }
        design.setObsolete(Boolean.TRUE);
        shipDesignRepository.saveAndFlush(design);
        log.debug("Империя {}: проект «{}» вытеснен новым — изучено лучшее вооружение",
                player.getName(), design.getName());
        return createAutoDesign(gameId, player, turn, design.getSlot(), hull, items);
    }

    /**
     * Подтягивает проект ПЛАТФОРМЫ обороны к нынешним технологиям — п. 11: состав
     * собирается заново прямо в строке.
     * <p>
     * Здесь правка на месте — это и есть правило: звёздная база в MOO II «обновляется
     * новейшими технологиями» сама, без перестройки. Кораблю так нельзя (см.
     * {@link #replaceAutoDesign}), а платформе — можно и нужно: она не летает, она стоит на
     * орбите и есть часть колонии.
     */
    private void refreshAutoDesign(ShipDesignEntity design, ShipHull hull,
                                   List<ShipDesignRules.Item> items,
                                   List<ShipDesignRules.Item> current) {
        if (current.equals(items) && hull.code().equals(design.getHullCode())) {
            return;
        }
        design.setHullCode(hull.code());
        design.setName(hull.names().en());
        shipDesignComponentRepository.deleteAllByDesignId(design.getId());
        shipDesignComponentRepository.flush();
        saveComponents(design, items);
    }

    /**
     * Гражданский корабль империи — п. 8: колониальный корабль или транспорт.
     * <p>
     * В MOO II эти два корабля в окне дизайна не собираются: игра предлагает их готовыми,
     * прямо в списке стройки колонии, и цена у них твёрдая. Поэтому здесь у них своя
     * служебная ячейка ({@link #CIVIL_SLOT}) — в шести ячейках дизайна они не показываются
     * и переделке не подлежат, — а состав минимальный: корпус и двигатель. Оружия у них
     * нет, и это не упущение: гражданский корабль в бой не выходит вовсе.
     * <p>
     * Заводится по требованию, при первой стройке такого корабля: партии, начатые до
     * появления расселения, получают его тем же способом, что и новые.
     */
    @Transactional
    public ShipDesignEntity civilDesign(GameEntity game, PlayerEntity player, ShipRole role) {
        return shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(player.getId()).stream()
                .filter(design -> design.getRole() == role)
                .findFirst()
                .orElseGet(() -> createCivilDesign(game, player, role));
    }

    /** Заводит гражданский проект: корпус подешевле и один двигатель — везти, а не воевать. */
    private ShipDesignEntity createCivilDesign(GameEntity game, PlayerEntity player, ShipRole role) {
        ShipHull hull = shipCatalog.cheapestHull();
        ShipComponent engine = shipCatalog.components(ShipComponentSlot.ENGINE).stream()
                .filter(component -> component.requiredTechCode() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("В справочнике нет стартового двигателя"));

        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(player.getId());
        design.setSlot(CIVIL_SLOT);
        design.setName(switch (role) {
            case COLONY -> COLONY_SHIP_NAME;
            case OUTPOST -> OUTPOST_SHIP_NAME;
            default -> TRANSPORT_NAME;
        });
        design.setHullCode(hull.code());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        design.setRole(role);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, List.of(new ShipDesignRules.Item(engine, 1)));
        log.info("Империя {} завела проект «{}»", player.getName(), design.getName());
        return design;
    }

    /**
     * Проект, который колония строит по умолчанию: первый действующий по номеру ячейки.
     * Если проектов нет вовсе — партия начата до появления подсистемы, — заводится
     * стартовый.
     */
    @Transactional
    public ShipDesignEntity defaultDesign(GameEntity game, PlayerEntity player) {
        return shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                .stream()
                .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                .findFirst()
                .orElseGet(() -> createDefaultDesign(game, player));
    }

    /** Проект игрока по идентификатору; чужой проект недоступен. */
    @Transactional(readOnly = true)
    public ShipDesignEntity requireOwn(PlayerEntity player, UUID designId) {
        ShipDesignEntity design = shipDesignRepository.findById(designId)
                .orElseThrow(() -> new NotFoundException("ship.designNotFound", designId));
        if (!design.getOwnerPlayerId().equals(player.getId())) {
            throw new NotFoundException("ship.designNotFound", designId);
        }
        return design;
    }

    /** Действующий проект игрока по идентификатору — по вытесненному строить нельзя. */
    @Transactional(readOnly = true)
    public ShipDesignEntity requireBuildable(PlayerEntity player, UUID designId) {
        ShipDesignEntity design = requireOwn(player, designId);
        if (Boolean.TRUE.equals(design.getObsolete())) {
            throw new ConflictException("ship.designObsolete", design.getName());
        }
        return design;
    }

    /** Характеристики проекта с расовыми поправками — их спрашивают стройка и бой. */
    public ShipStats stats(ShipDesignEntity design, RaceEffects race) {
        return shipDesignRules.stats(shipCatalog.hull(design.getHullCode()), items(design), race);
    }

    /** Боевая сила одного корабля этого проекта — по ней сходятся флоты. */
    public Integer power(ShipDesignEntity design, RaceEffects race) {
        return shipDesignRules.power(stats(design, race));
    }

    /**
     * Характеристики сразу многих проектов: фаза встреч и экран флота спрашивают их
     * пачкой, и выбирать состав по одному проекту — та же тройная выборка, от которой
     * ушёл {@code TurnContext}.
     */
    public Map<UUID, ShipStats> statsByDesign(Collection<ShipDesignEntity> designs, RaceEffects race) {
        return statsFromItems(designs, itemsOf(designs), race);
    }

    /**
     * То же, но по УЖЕ вычитанным составам — п. 8.
     * <p>
     * Нужно тем, кому состав нужен и сам по себе: {@code FleetService.combat} собирал
     * проекты, их характеристики и составы тремя вызовами подряд, и составы уходили в базу
     * ДВАЖДЫ — один раз внутри {@link #statsByDesign}, второй раз своим {@link #itemsOf}.
     * Внутри посчитанного хода лишний запрос стоит дороже, чем кажется: он заставляет
     * Hibernate сбросить туда же всё, что ход успел изменить.
     */
    public Map<UUID, ShipStats> statsFromItems(Collection<ShipDesignEntity> designs,
                                               Map<UUID, List<ShipDesignRules.Item>> items,
                                               RaceEffects race) {
        Map<UUID, ShipStats> stats = new HashMap<>(designs.size());
        for (ShipDesignEntity design : designs) {
            stats.put(design.getId(), shipDesignRules.stats(
                    shipCatalog.hull(design.getHullCode()),
                    items.getOrDefault(design.getId(), List.of()),
                    race));
        }
        return stats;
    }

    /**
     * Характеристики всех проектов игрока, включая вытесненные, — п. 8.
     * <p>
     * Спрашивает флот: по вытесненному проекту строить нельзя, а корабли, построенные
     * раньше, по нему летают и воюют. Одна выборка на игрока — состав флотов читается
     * пачкой, как того требует конец хода.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ShipStats> statsOf(PlayerEntity player) {
        List<ShipDesignEntity> designs = shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(player.getId());
        return statsByDesign(designs, raceService.effects(player));
    }

    /** Размер корпуса проекта: 1 у фрегата, 6 у Leviathan — п. 8. */
    public Integer hullSize(ShipDesignEntity design) {
        return shipCatalog.hull(design.getHullCode()).sortOrder();
    }

    /**
     * Проекты игрока по идентификатору, включая вытесненные, — для состава флота.
     * <p>
     * Пояснение и {@code @Transactional} стояли строкой выше и приклеились к вставленному
     * между ними {@link #hullSize}: аннотация принадлежит методу, а не месту в файле
     * (CLAUDE.md). Сам {@code hullSize} в базу не ходит вовсе — он спрашивает справочник, —
     * а этот метод ходит, и пометка нужна ему.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ShipDesignEntity> allDesignsOf(PlayerEntity player) {
        return shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(player.getId()).stream()
                .collect(Collectors.toMap(ShipDesignEntity::getId, design -> design));
    }

    /** Проекты партии по идентификатору — их спрашивают флоты и слепок. */
    @Transactional(readOnly = true)
    public Map<UUID, ShipDesignEntity> designsOfGame(UUID gameId) {
        return shipDesignRepository.findAllByGameId(gameId).stream()
                .sorted(GameOrder.DESIGNS)
                .collect(Collectors.toMap(ShipDesignEntity::getId, design -> design));
    }

    /** Состав проекта из базы, уже разобранный по справочнику. */
    public List<ShipDesignRules.Item> items(ShipDesignEntity design) {
        return toItems(shipDesignComponentRepository.findAllByDesignIdOrderBySortOrderAsc(design.getId()));
    }

    /** Составы сразу нескольких проектов — одной выборкой. */
    public Map<UUID, List<ShipDesignRules.Item>> itemsOf(Collection<ShipDesignEntity> designs) {
        List<UUID> ids = designs.stream().map(ShipDesignEntity::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return shipDesignComponentRepository.findAllByDesignIdInOrderBySortOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(
                        ShipDesignComponentEntity::getDesignId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::toItems)));
    }

    /** Готовый ответ по одному проекту — его отдают экран дизайна и стройка. */
    public ShipDesignDto toDto(ShipDesignEntity design, RaceEffects race) {
        return toDto(design, items(design), race);
    }

    /** Готовые ответы по многим проектам — одной выборкой состава. */
    public List<ShipDesignDto> toDtos(List<ShipDesignEntity> designs, RaceEffects race) {
        Map<UUID, List<ShipDesignRules.Item>> items = itemsOf(designs);
        return designs.stream()
                .map(design -> toDto(design, items.getOrDefault(design.getId(), List.of()), race))
                .toList();
    }

    private ShipDesignDto toDto(ShipDesignEntity design, List<ShipDesignRules.Item> items, RaceEffects race) {
        ShipHull hull = shipCatalog.hull(design.getHullCode());
        ShipStats stats = shipDesignRules.stats(hull, items, race);

        List<ShipDesignComponentDto> components = items.stream()
                .map(item -> new ShipDesignComponentDto(
                        item.component().code(),
                        item.component().name(),
                        item.component().slot(),
                        item.count(),
                        // Место и цена — у состава: модификация ствола меняет и то и другое.
                        item.spaceOn(hull) * item.count(),
                        item.costOn(hull) * item.count(),
                        item.modifications().stream().map(WeaponModification::code).toList(),
                        item.component().slot() == ShipComponentSlot.WEAPON ? item.damage() : null))
                .toList();

        return new ShipDesignDto(
                design.getId(),
                design.getSlot(),
                design.getName(),
                hull.code(),
                hull.name(),
                components,
                stats.space(),
                stats.spaceUsed(),
                stats.cost(),
                stats.structure(),
                stats.armour(),
                stats.shield(),
                stats.speed(),
                stats.combatSpeed(),
                stats.attack(),
                stats.defense(),
                stats.missileEvasion(),
                stats.troops(),
                stats.command(),
                shipDesignRules.power(stats),
                design.getObsolete());
    }

    /**
     * Разбирает состав запроса и проверяет технологии. Неизвестный код — 404 из
     * справочника, неизученный — конфликт: это разные ошибки, и клиенту полезно их различать.
     */
    private List<ShipDesignRules.Item> requireItems(SaveShipDesignRequest request, Set<String> technologies) {
        // Ключ строки состава — компонент вместе с его модификациями: тяжёлые лазеры и
        // обычные стоят в проекте разными строками, как и в окне дизайна MOO II.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> mods = new LinkedHashMap<>();
        for (SaveShipDesignRequest.ShipComponentChoice choice : request.components()) {
            List<String> chosen = choice.modifications() == null
                    ? List.of()
                    : choice.modifications().stream().distinct().sorted().toList();
            String key = choice.code() + "|" + String.join(",", chosen);
            counts.merge(key, choice.count(), Integer::sum);
            mods.putIfAbsent(key, chosen);
        }

        List<ShipDesignRules.Item> items = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String code = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            ShipComponent component = shipCatalog.component(code);
            if (!researched(component.requiredTechCode(), technologies)) {
                throw new ConflictException("ship.componentNotResearched", component.name());
            }
            List<String> chosen = mods.getOrDefault(entry.getKey(), List.of());
            if (!chosen.isEmpty() && component.slot() != ShipComponentSlot.WEAPON) {
                throw new ConflictException("ship.modificationWeaponsOnly", component.name());
            }
            List<WeaponModification> modifications = chosen.stream()
                    .map(shipCatalog::modification)
                    .toList();
            for (WeaponModification modification : modifications) {
                // Модификации у каждого ствола свои — п. 8: ближнюю оборону носит
                // ускоритель частиц, а луч смерти её не берёт вовсе, и у ракеты набор
                // другой целиком. Список носимого лежит у самого оружия в справочнике.
                if (!Boolean.TRUE.equals(component.carries(modification))) {
                    throw new ConflictException("ship.modificationNotCarried", component.name(), modification.name());
                }
                if (!researched(modification.requiredTechCode(), technologies)) {
                    throw new ConflictException("ship.modificationNotResearched", modification.name());
                }
            }
            items.add(new ShipDesignRules.Item(component, entry.getValue(), modifications));
        }
        return items;
    }

    /**
     * Проверки проекта — п. 8. Двигатель обязателен: без него корабль не полетит. Брони,
     * щита, компьютера и постановщика помех бывает по одному — так устроено окно дизайна
     * MOO II. И всё вместе должно влезать в корпус.
     */
    private void validate(ShipHull hull, List<ShipDesignRules.Item> items) {
        Map<ShipComponentSlot, Integer> bySlot = new EnumMap<>(ShipComponentSlot.class);
        for (ShipDesignRules.Item item : items) {
            bySlot.merge(item.component().slot(), item.count(), Integer::sum);
            if (Boolean.TRUE.equals(item.component().slot().single()) && item.count() > 1) {
                throw new ConflictException("ship.duplicateComponent", item.component().name());
            }
        }

        if (bySlot.getOrDefault(ShipComponentSlot.ENGINE, 0) == 0) {
            throw new ConflictException("ship.engineRequired");
        }
        for (ShipComponentSlot slot : ShipComponentSlot.values()) {
            if (Boolean.TRUE.equals(slot.single()) && bySlot.getOrDefault(slot, 0) > 1) {
                throw new ConflictException("ship.slotTwice", slot);
            }
        }
        // Особые модули не повторяются: два одинаковых усиления корпуса смысла не имеют.
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.SPECIAL && item.count() > 1) {
                throw new ConflictException("ship.specialOnce", item.component().name());
            }
        }

        ShipStats stats = shipDesignRules.stats(hull, items, RaceEffects.NONE);
        if (!stats.fits()) {
            throw new ConflictException("ship.noSpace", stats.spaceUsed(), stats.space());
        }
    }

    /** Состав стартового проекта: двигатель, обшивка и столько пушек, сколько влезет. */
    private List<ShipDesignRules.Item> defaultItems(ShipHull hull) {
        return defaultItems(hull, Set.of());
    }

    /**
     * Состав проекта, который игра собирает сама, — п. 8.
     * <p>
     * Берётся лучшее из изученного: двигатель, обшивка, компьютер и щит — по одному, а
     * остаток корпуса занимает оружие. Так и поступает MOO II, предлагая готовые проекты:
     * колонии всегда есть что строить, пока игрок не собрал своего. Лучшим считается
     * последнее в справочнике: он идёт по возрастанию уровня, как дерево технологий.
     */
    private List<ShipDesignRules.Item> defaultItems(ShipHull hull, Set<String> technologies) {
        List<ShipDesignRules.Item> items = new ArrayList<>(4);
        int left = hull.space();

        for (ShipComponentSlot slot : List.of(ShipComponentSlot.ENGINE, ShipComponentSlot.ARMOR,
                ShipComponentSlot.COMPUTER, ShipComponentSlot.SHIELD)) {
            ShipComponent best = bestAvailable(slot, technologies);
            if (best == null || best.spaceOn(hull) > left) {
                continue;
            }
            items.add(new ShipDesignRules.Item(best, 1));
            left -= best.spaceOn(hull);
        }

        // Планетарный щит оружия не несёт вовсе — п. 11: он держит, а не бьёт. Остаток
        // корпуса у него так и остаётся пустым, и это не упущение состава.
        if (Boolean.TRUE.equals(hull.unarmed())) {
            return List.copyOf(items);
        }

        ShipComponent weapon = bestWeapon(hull, left, technologies);
        if (weapon == null) {
            weapon = shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                    .filter(component -> component.requiredTechCode() == null)
                    .min(Comparator.comparing(ShipComponent::space))
                    .orElseThrow(() -> new IllegalStateException("В справочнике нет стартового оружия"));
        }
        int guns = Math.max(1, left / Math.max(1, weapon.spaceOn(hull)));
        items.add(new ShipDesignRules.Item(weapon, guns));
        return List.copyOf(items);
    }

    /**
     * Лучшее изученное в гнезде; {@code null} — в гнезде у империи нет ничего. Порядок
     * справочника и есть порядок силы: он идёт снизу вверх по дереву технологий.
     */
    private ShipComponent bestAvailable(ShipComponentSlot slot, Set<String> technologies) {
        List<ShipComponent> open = shipCatalog.components(slot).stream()
                .filter(component -> researched(component.requiredTechCode(), technologies))
                .toList();
        if (open.isEmpty()) {
            return null;
        }
        // ОРУЖИЕ ЗДЕСЬ НЕ ВЫБИРАЕТСЯ — для него есть bestWeapon, и вот почему.
        //
        // Порядок справочника годится для лестниц: двигатели, броня, щиты, компьютеры и
        // помехи идут в нём одной возрастающей чередой, и последнее изученное там и есть
        // лучшее. У оружия порядок ДРУГОЙ: сперва лучи (50-61), потом ракеты (70-73),
        // потом торпеды (80-82) — три лестницы подряд. «Последнее в справочнике» выбирало
        // из них ядерную ракету (70), потому что она стоит ПОСЛЕ всех лучей, а достаётся
        // она каждой империи со стартовой Chemistry на первом же ходу. Измерено: империя
        // вооружала все свои проекты ядерными ракетами и не меняла их НИКОГДА — ни на
        // плазменную пушку, ни на луч смерти; изучение Fusion Beam не меняло залп вовсе.
        // Двенадцать лучевых технологий были для игры мертвы.
        //
        // Правильный ответ зависит от КОРПУСА, а не только от изученного: стволы кладутся
        // целыми, и звёздный конвертер (100 клеток) на фрегат не влезает вовсе. Поэтому
        // оружие выбирает bestWeapon, которому известны корпус и остаток места.
        return open.get(open.size() - 1);
    }

    /**
     * Чем вооружить этот корпус — п. 8: ствол, дающий САМЫЙ БОЛЬШОЙ ЗАЛП в оставшемся месте.
     * <p>
     * Считается фактический залп, а не урон за клетку: стволы кладутся в корпус целыми, и
     * остаток места пропадает. Виднее всего это на тяжёлых стволах: у лучей оригинала база
     * десять клеток, а у Дробителя и Копья новы — пятьдесят, поэтому фрегату с его двадцатью
     * тремя свободными клетками они не достаются вовсе, а крейсеру с девяноста двумя Копьё
     * достаётся одно (400 урона) против девяти плазменных пушек (540). Так выбор сам собой
     * ставит мелкие стволы на мелкий корпус и крупные на крупный — ровно как в MOO II.
     */
    private ShipComponent bestWeapon(ShipHull hull, Integer space, Set<String> technologies) {
        List<ShipComponent> open = shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                .filter(component -> researched(component.requiredTechCode(), technologies))
                .toList();
        if (open.isEmpty()) {
            return null;
        }
        return open.stream()
                .max(Comparator
                        .comparingInt((ShipComponent one) ->
                                (space / Math.max(1, one.spaceOn(hull))) * damage(one))
                        // При равном залпе берётся более убойный ствол: одним выстрелом
                        // проще пробить щит, чем двумя слабыми (п. 8).
                        .thenComparingInt(this::damage))
                .orElse(null);
    }

    /**
     * Урон ствола за залп — п. 8: сколько бьёт, сколькими выстрелами и на сколько сторон.
     * <p>
     * Обволакивающий удар считается вчетверо тем же множителем, каким его считают бой и
     * сила проекта ({@link ShipDesignRules#ENVELOPING_SIDES}): иначе «лучшее оружие» у
     * автоматического проекта значило бы не то, что «сильнее» в остальной игре.
     */
    private int damage(ShipComponent weapon) {
        int sides = Boolean.TRUE.equals(weapon.envelops()) ? ShipDesignRules.ENVELOPING_SIDES : 1;
        return weapon.effects().getOrDefault(ShipEffectType.WEAPON_DAMAGE, 0)
                * Math.max(1, weapon.effects().getOrDefault(ShipEffectType.WEAPON_SHOTS, 1))
                * sides;
    }

    private void saveComponents(ShipDesignEntity design, List<ShipDesignRules.Item> items) {
        List<ShipDesignComponentEntity> rows = new ArrayList<>(items.size());
        int order = 0;
        for (ShipDesignRules.Item item : items) {
            ShipDesignComponentEntity row = new ShipDesignComponentEntity();
            row.setDesignId(design.getId());
            row.setComponentCode(item.component().code());
            row.setCount(item.count());
            row.setSortOrder(order++);
            row.setModifications(item.modifications().isEmpty() ? null
                    : item.modifications().stream()
                            .map(WeaponModification::code)
                            .collect(Collectors.joining(",")));
            rows.add(row);
        }
        shipDesignComponentRepository.saveAll(rows);
    }

    private List<ShipDesignRules.Item> toItems(List<ShipDesignComponentEntity> rows) {
        return rows.stream()
                .map(row -> new ShipDesignRules.Item(
                        shipCatalog.component(row.getComponentCode()),
                        row.getCount(),
                        modifications(row.getModifications())))
                .toList();
    }

    /**
     * Модификации ствола из строки состава — п. 8: коды через запятую, пусто — ствол как
     * есть. Неизвестный код молча пропускается: справочник правится на лету, и модификация
     * может из него уйти, а проекты по ней уже собраны — ронять из-за этого бой нельзя.
     */
    private List<WeaponModification> modifications(String codes) {
        if (codes == null || codes.isBlank()) {
            return List.of();
        }
        List<WeaponModification> found = new ArrayList<>();
        for (String code : codes.split(",")) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                found.add(shipCatalog.modification(trimmed));
            } catch (NotFoundException ignored) {
                log.debug("Модификация «{}» пропала из справочника: проект строится без неё", trimmed);
            }
        }
        return List.copyOf(found);
    }

    /** Коды изученных игроком технологий. */
    private Set<String> technologies(PlayerEntity player) {
        return playerTechnologyRepository.findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId())
                .stream()
                .map(technology -> technology.getOptionCode())
                .collect(Collectors.toSet());
    }

    /** Компонент без технологии доступен с начала партии — как в MOO II. */
    private Boolean researched(String requiredTechCode, Set<String> technologies) {
        return requiredTechCode == null || technologies.contains(requiredTechCode);
    }

    /**
     * Название нужной технологии на языке читателя — п. 3.5.
     * <p>
     * Окно выбора системы писало КОД («нужна технология: electronic-computer»): код лежит
     * в базе у изученного, а игроку нужно имя. Неизвестный код возвращается как есть —
     * молчать о требовании хуже, чем показать его строкой.
     */
    private String techName(String requiredTechCode) {
        if (requiredTechCode == null) {
            return null;
        }
        var place = researchCatalog.places().get(requiredTechCode);
        return place == null ? requiredTechCode : place.option().name();
    }

    /**
     * Действия компонента для экрана: число и подпись вида «урон 6», «место 50 %».
     * <p>
     * Числа уходят на клиент потому, что окно дизайна считает проект на лету, пока игрок
     * его собирает, — сохранённого проекта ещё нет, и спрашивать сервер не о чем.
     */
    private List<ShipEffectDto> effectLines(ShipComponent component) {
        List<ShipEffectDto> lines = new ArrayList<>(component.effects().size());
        for (Map.Entry<ShipEffectType, Integer> effect : component.effects().entrySet()) {
            lines.add(new ShipEffectDto(effect.getKey(), effect.getValue(),
                    effectName(effect.getKey()) + " " + effect.getValue()
                            + (percent(effect.getKey()) ? " %" : "")));
        }
        return lines;
    }

    private Boolean percent(ShipEffectType type) {
        return type == ShipEffectType.ARMOUR_PERCENT
                || type == ShipEffectType.ATTACK_PERCENT
                || type == ShipEffectType.STRUCTURE_PERCENT
                || type == ShipEffectType.SPACE_PERCENT;
    }

    private String effectName(ShipEffectType type) {
        return switch (type) {
            case WEAPON_DAMAGE -> "урон";
            case WEAPON_SHOTS -> "выстрелов";
            case ARMOUR -> "броня";
            case ARMOUR_PERCENT -> "броня";
            case SHIELD -> "щит";
            case SPEED -> "скорость";
            case COMBAT_SPEED -> "боевая скорость";
            case MISSILE_EVASION -> "уклонение от ракет";
            case ATTACK_PERCENT -> "атака";
            case DEFENSE -> "защита";
            case STRUCTURE_PERCENT -> "прочность";
            case SPACE_PERCENT -> "место";
            case TROOPS -> "десант";
        };
    }
}
