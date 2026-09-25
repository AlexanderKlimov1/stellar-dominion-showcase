package com.moo3.server.service;

import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.FleetShipEntity;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.GalacticEvent;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.dto.AcquiredTechnologyDto;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.FleetShipRepository;
import com.moo3.server.repository.PlanetBuildingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Случайные галактические события — п. 11.1, события MOO II.
 * <p>
 * «Время от времени случаются удачи, беды и напасти, к которым игрок не причастен», и в
 * окне новой игры их можно выключить — здесь так же: партия помнит свой выбор
 * ({@code game.galacticEvents}), и выключенные события не случаются вовсе.
 * <p>
 * <b>Кому достанется — правило оригинала:</b> «дурные события чаще случаются с ведущими
 * империями, а добрые — с теми, кто отстал». Кто ведёт, а кто отстал, считается той же
 * мощью, что рисует график окна «Инфо» и по которой сравнивают себя империи ИИ
 * ({@link EmpireMightRules}): одна мера на всю игру. Жребий взвешенный, а не выбор
 * первого: отставший не застрахован от беды, просто она реже приходит к нему.
 * <p>
 * <b>Раса «Везучие»</b> (п. 7): к ней дурные события не приходят вовсе, а добрые приходят
 * вдвое чаще — в MOO II это и есть смысл этой стороны расы.
 * <p>
 * <b>Событие обязано быть замеченным.</b> Каждое пишется в отчёт хода тому, кого касается
 * (код {@code GALACTIC}), и попадает в диалог итогов хода: событие, которого игрок не
 * увидел, для игры не случилось. Поэтому событие, которому не на что упасть (нет колоний,
 * нет флота в пути, не с кем ссорить), не выбирается — вместо него берётся другое.
 */
@Service
public class GalacticEventService {

    private static final Logger log = LoggerFactory.getLogger(GalacticEventService.class);

    /**
     * Как часто в партии что-то случается, в процентах на ход.
     * <p>
     * <i>Реконструкция:</i> частоту MOO II не публикует. Восемь процентов — это событие
     * раз в дюжину ходов на всю галактику: партия живёт своей жизнью, но новости не
     * заслоняют игру.
     */
    private static final int EVENT_CHANCE_PERCENT = 8;

    /** Во сколько раз ведущая империя вероятнее получит беду, а отстающая — удачу. */
    private static final int STANDING_BIAS = 3;

    /** Насколько чаще добрые события приходят к везучей расе. */
    private static final int LUCKY_BONUS = 2;

    /** Сколько денег приносит пожертвование и сколько уносят пираты, в процентах казны. */
    private static final int DONATION_PERCENT = 25;
    private static final int PIRATES_PERCENT = 20;
    private static final int DONATION_MIN = 50;

    /** Насколько землетрясение убавляет население колонии, в процентах. */
    private static final int EARTHQUAKE_PERCENT = 25;

    /** На сколько династический брак и убийство двигают доверие. */
    private static final int MARRIAGE_TRUST = 20;
    private static final int ASSASSIN_TRUST = 20;

    /** На сколько ходов воронка искривления отбрасывает летящий флот. */
    private static final int WARP_FUNNEL_TURNS = 3;

    private final EmpireInfoService empireInfoService;
    private final ResearchService researchService;
    private final RaceService raceService;
    private final PlanetBuildingRepository buildingRepository;
    private final FleetRepository fleetRepository;
    private final FleetShipRepository fleetShipRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final Messages messages;

    public GalacticEventService(EmpireInfoService empireInfoService,
                                ResearchService researchService,
                                RaceService raceService,
                                PlanetBuildingRepository buildingRepository,
                                FleetRepository fleetRepository,
                                FleetShipRepository fleetShipRepository,
                                DiplomacyRelationRepository relationRepository,
                                Messages messages) {
        this.empireInfoService = empireInfoService;
        this.researchService = researchService;
        this.raceService = raceService;
        this.buildingRepository = buildingRepository;
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.relationRepository = relationRepository;
        this.messages = messages;
    }

    /** Ход галактических событий: не чаще одного за ход и не в каждой партии — п. 11.1. */
    @Transactional
    public void apply(TurnContext context) {
        if (!Boolean.TRUE.equals(context.game().getGalacticEvents())) {
            return;
        }

        // Жребий от зерна партии и хода: перезагруженное сохранение считается так же.
        Random random = new Random(context.game().getSeed() * 53L + context.turn() * 59L);
        if (random.nextInt(100) >= EVENT_CHANCE_PERCENT) {
            return;
        }

        Map<UUID, Integer> might = empireInfoService.mightByPlayer(context);
        Boolean good = random.nextBoolean();
        PlayerEntity target = pick(context, might, good, random);
        if (target == null) {
            return;
        }

        // Событий у знака несколько; берём подходящее — то, которому есть на что упасть.
        List<GalacticEvent> candidates = new ArrayList<>(
                java.util.Arrays.stream(GalacticEvent.values())
                        .filter(event -> event.getGood().equals(good))
                        .toList());
        while (!candidates.isEmpty()) {
            GalacticEvent event = weighted(candidates, random);
            MessageKey story = happen(context, target, event, random);
            if (story != null) {
                context.report().add(target.getId(), "GALACTIC", story);
                log.info("Галактическое событие {} у игрока {}: {}",
                        event.name(), target.getName(), story.key());
                return;
            }
            candidates.remove(event);
        }
    }

    /**
     * Кому достанется событие — п. 11.1, правило MOO II.
     * <p>
     * Дурное тянется к ведущему, доброе — к отстающему, но жребий взвешенный: у первого в
     * галактике втрое больше шансов на беду, чем у последнего, а не полная неизбежность.
     * Везучая раса (п. 7) в списке бед не участвует вовсе, а в списке удач весит вдвое.
     */
    private PlayerEntity pick(TurnContext context, Map<UUID, Integer> might, Boolean good,
                              Random random) {
        List<PlayerEntity> players = context.players().stream()
                .filter(player -> Boolean.TRUE.equals(good)
                        || !Boolean.TRUE.equals(raceService.effects(player).lucky()))
                .toList();
        if (players.isEmpty()) {
            return null;
        }

        List<PlayerEntity> ranked = players.stream()
                .sorted(Comparator.comparingInt(player -> might.getOrDefault(player.getId(), 0)))
                .toList();

        List<Integer> weights = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            // Ранг от слабого к сильному: беде вес растёт к сильным, удаче — к слабым.
            int rank = Boolean.TRUE.equals(good) ? ranked.size() - 1 - i : i;
            int weight = 1 + rank * (STANDING_BIAS - 1);
            if (Boolean.TRUE.equals(good)
                    && Boolean.TRUE.equals(raceService.effects(ranked.get(i)).lucky())) {
                weight *= LUCKY_BONUS;
            }
            weights.add(weight);
        }

        int total = weights.stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(total);
        log.debug("Жребий события ({}): {}", Boolean.TRUE.equals(good) ? "доброе" : "дурное",
                java.util.stream.IntStream.range(0, ranked.size())
                        .mapToObj(i -> ranked.get(i).getName() + "=" + weights.get(i))
                        .toList());
        for (int i = 0; i < ranked.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                return ranked.get(i);
            }
        }
        return ranked.get(ranked.size() - 1);
    }

    /** Событие из списка по весам: у частых он больше. */
    private GalacticEvent weighted(List<GalacticEvent> events, Random random) {
        int total = events.stream().mapToInt(GalacticEvent::getWeight).sum();
        int roll = random.nextInt(total);
        for (GalacticEvent event : events) {
            roll -= event.getWeight();
            if (roll < 0) {
                return event;
            }
        }
        return events.get(events.size() - 1);
    }

    /**
     * Само событие: что оно делает и что об этом сказать игроку.
     *
     * @return рассказ для отчёта хода ключом с подстановками (п. 3.5): язык читателя
     *         внутри хода неизвестен; {@code null} — событию не на что упасть, и его
     *         место займёт другое
     */
    private MessageKey happen(TurnContext context, PlayerEntity player, GalacticEvent event,
                              Random random) {
        List<PlanetEntity> colonies = context.coloniesOf(player.getId());
        return switch (event) {
            case ANCIENT_SHIP -> gift(player, context.turn(), random, Boolean.FALSE, event);
            case SECRET_EXPERIMENT -> gift(player, context.turn(), random, Boolean.TRUE, event);

            case AXIS_SHIFT -> {
                PlanetEntity colony = worstClimate(colonies);
                if (colony == null || colony.getClimate() == PlanetClimate.TERRAN
                        || colony.getClimate() == PlanetClimate.GAIA) {
                    yield null;
                }
                colony.setClimate(PlanetClimate.TERRAN);
                yield new MessageKey("turn.galactic.axisShift", event, colony.getName());
            }

            case MINERAL_DEPOSIT -> {
                PlanetEntity colony = any(colonies, random);
                MineralRichness richer = shift(colony == null ? null : colony.getMinerals(), 1);
                if (richer == null) {
                    yield null;
                }
                colony.setMinerals(richer);
                yield new MessageKey("turn.galactic.mineralDeposit",
                        event, colony.getName(), richer.getLabel());
            }

            case DONATION -> {
                int amount = Math.max(DONATION_MIN, player.getCredits() * DONATION_PERCENT / 100);
                player.setCredits(player.getCredits() + amount);
                yield new MessageKey("turn.galactic.donation", event, amount);
            }

            case DIPLOMATIC_MARRIAGE -> trust(player, MARRIAGE_TRUST, event);
            case DIPLOMATIC_ASSASSIN -> trust(player, -ASSASSIN_TRUST, event);

            case WORMHOLE -> {
                FleetEntity fleet = travelling(context, player, random);
                if (fleet == null) {
                    yield null;
                }
                fleet.setArrivalTurn(context.turn() + 1);
                yield new MessageKey("turn.galactic.wormhole", event,
                        systemName(context, fleet.getTargetSystemId()));
            }

            case WARP_FUNNEL -> {
                FleetEntity fleet = travelling(context, player, random);
                if (fleet == null) {
                    yield null;
                }
                fleet.setArrivalTurn(fleet.getArrivalTurn() + WARP_FUNNEL_TURNS);
                yield new MessageKey("turn.galactic.warpFunnel", event, WARP_FUNNEL_TURNS);
            }

            case COMPUTER_VIRUS -> {
                if (player.getResearchOptionCode() == null || player.getResearchPoints() <= 0) {
                    yield null;
                }
                Integer lost = player.getResearchPoints();
                player.setResearchPoints(0);
                yield new MessageKey("turn.galactic.computerVirus", event, lost);
            }

            case PIRATES -> {
                int stolen = player.getCredits() * PIRATES_PERCENT / 100;
                if (stolen <= 0) {
                    yield null;
                }
                player.setCredits(player.getCredits() - stolen);
                yield new MessageKey("turn.galactic.pirates", event, stolen);
            }

            case EARTHQUAKE -> {
                PlanetEntity colony = any(colonies, random);
                if (colony == null || colony.getPopulation() <= 1) {
                    yield null;
                }
                int lost = Math.max(1, colony.getPopulation() * EARTHQUAKE_PERCENT / 100);
                colony.setPopulation(colony.getPopulation() - lost);
                String ruined = ruin(colony, random);
                yield ruined == null
                        ? new MessageKey("turn.galactic.earthquake", event, colony.getName(), lost)
                        : new MessageKey("turn.galactic.earthquakeRuined",
                                event, colony.getName(), lost, ruined);
            }

            case INDUSTRIAL_ACCIDENT -> {
                PlanetEntity colony = any(colonies, random);
                PlanetClimate worse = worsen(colony == null ? null : colony.getClimate());
                if (worse == null) {
                    yield null;
                }
                colony.setClimate(worse);
                yield new MessageKey("turn.galactic.industrialAccident",
                        event, colony.getName(), worse.getLabel());
            }

            case MINERAL_DEPLETION -> {
                PlanetEntity colony = any(colonies, random);
                MineralRichness poorer = shift(colony == null ? null : colony.getMinerals(), -1);
                if (poorer == null) {
                    yield null;
                }
                colony.setMinerals(poorer);
                yield new MessageKey("turn.galactic.mineralDepletion",
                        event, colony.getName(), poorer.getLabel());
            }

            case EXPLODED_SHIP -> {
                FleetEntity fleet = anyFleet(context, player, random);
                if (fleet == null) {
                    yield null;
                }
                yield destroyShip(fleet, event, random);
            }
        };
    }

    /** Технология в подарок: находка древнего корабля и тайный эксперимент. */
    private MessageKey gift(PlayerEntity player, Integer turn, Random random, Boolean wholeLevel,
                            GalacticEvent event) {
        List<AcquiredTechnologyDto> gained =
                researchService.grantGift(player, turn, random, wholeLevel);
        if (gained.isEmpty()) {
            return null;
        }
        return new MessageKey("turn.galactic.gift", event,
                gained.stream()
                        .map(one -> CatalogTexts.tech(one.optionCode()))
                        .collect(Collectors.joining(", ")));
    }

    /**
     * Династический брак и дипломатическое убийство — п. 15: событие двигает доверие с
     * одним из знакомых соседей. Незнакомым событие не достаётся: ссорить и мирить не с
     * кем, и рассказать игроку было бы не о чем.
     */
    private MessageKey trust(PlayerEntity player, Integer change, GalacticEvent event) {
        // СОСЕД БЕРЁТСЯ ПЕРВЫМ, а выборка порядка не задавала вовсе — то есть его выбирала
        // база, то есть случайные идентификаторы строк. Две одинаковые партии двигали
        // доверие РАЗНЫМ соседям и дальше расходились. Ключ — код другой империи в виде
        // строки: свой слот сюда не дотянуть (событие знает только игрока), а порядок нужен
        // хоть какой-то, лишь бы он выводился из самих данных, а не из того, как легли
        // строки. См. GameOrder о том, почему идентификатор строки ключом не годится:
        // здесь он участвует ЗНАЧЕНИЕМ, одинаковым в обоих прогонах, а не порядком записи.
        List<DiplomacyRelationEntity> relations = new ArrayList<>(
                relationRepository.findAllByPlayerId(player.getId()));
        if (relations.isEmpty()) {
            return null;
        }
        relations.sort(Comparator.comparing(one -> one.getOtherPlayerId().toString()));
        DiplomacyRelationEntity mine = relations.get(0);
        DiplomacyRelationEntity theirs = relationRepository
                .findByPlayerIdAndOtherPlayerId(mine.getOtherPlayerId(), player.getId())
                .orElse(null);
        // Двигаются оба доверия: событие про отношения двух империй, а не про мнение одной.
        mine.setTrust(clamp(mine.getTrust() + change));
        if (theirs != null) {
            theirs.setTrust(clamp(theirs.getTrust() + change));
            relationRepository.save(theirs);
        }
        relationRepository.save(mine);
        return new MessageKey(change > 0
                ? "turn.galactic.trustUp"
                : "turn.galactic.trustDown", event);
    }

    /** Уничтожает один корабль флота; флот без кораблей исчезает. */
    private MessageKey destroyShip(FleetEntity fleet, GalacticEvent event, Random random) {
        List<FleetShipEntity> composition = fleetShipRepository.findAllByFleetIdOrderByIdAsc(fleet.getId());
        if (composition.isEmpty()) {
            return null;
        }
        FleetShipEntity group = composition.get(random.nextInt(composition.size()));
        group.setShips(group.getShips() - 1);
        if (group.getShips() <= 0) {
            fleetShipRepository.delete(group);
        } else {
            fleetShipRepository.save(group);
        }
        fleet.setShips(fleet.getShips() - 1);
        if (fleet.getShips() <= 0) {
            fleetRepository.delete(fleet);
        } else {
            fleetRepository.save(fleet);
        }
        return new MessageKey("turn.galactic.explodedShip", event);
    }

    /** Сносит одну постройку колонии; {@code null} — сносить нечего. */
    private String ruin(PlanetEntity colony, Random random) {
        List<PlanetBuildingEntity> buildings = buildingRepository.findAllByPlanetId(colony.getId());
        if (buildings.isEmpty()) {
            return null;
        }
        PlanetBuildingEntity building = buildings.get(random.nextInt(buildings.size()));
        buildingRepository.delete(building);
        return building.getBuildingCode();
    }

    /** Колония с худшим климатом: сдвиг оси выправляет именно её — там от него больше проку. */
    private PlanetEntity worstClimate(List<PlanetEntity> colonies) {
        return colonies.stream()
                .filter(colony -> colony.getClimate().getTerraformOrder() > 0)
                .min(Comparator.comparingInt(colony -> colony.getClimate().getTerraformOrder()))
                .orElse(null);
    }

    private PlanetEntity any(List<PlanetEntity> colonies, Random random) {
        return colonies.isEmpty() ? null : colonies.get(random.nextInt(colonies.size()));
    }

    /**
     * Порядок флотов для жребия — п. 11.1, этап 0 балансировки.
     * <p>
     * Флот выбирается ИНДЕКСОМ ({@code fleets.get(random.nextInt(...))}), а выборка флотов
     * порядка не задаёт: он достаётся базе, то есть случайным идентификаторам строк. Две
     * одинаковые партии теряли к воронке искривления РАЗНЫЕ флоты и дальше расходились.
     * Ключ игровой: ход создания, потом имя звезды, на которой флот стоит (см. GameOrder о
     * том, почему ни идентификатор строки, ни строковая колонка базы ключом не годятся).
     */
    private Comparator<FleetEntity> fleetOrder(TurnContext context) {
        Map<UUID, String> names = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, StarSystemEntity::getName,
                        (first, second) -> first));
        return Comparator.comparing(FleetEntity::getCreatedTurn)
                .thenComparing(fleet -> names.getOrDefault(fleet.getStarSystemId(), ""));
    }

    /** Любой стоящий флот игрока: взрыв застаёт корабль и на стоянке. */
    private FleetEntity anyFleet(TurnContext context, PlayerEntity player, Random random) {
        List<FleetEntity> fleets = fleetRepository.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(fleet -> fleet.getShips() > 0)
                .sorted(fleetOrder(context))
                .toList();
        return fleets.isEmpty() ? null : fleets.get(random.nextInt(fleets.size()));
    }

    /** Флот в пути: червоточина и воронка искривления случаются только с такими. */
    /**
     * Название системы для отчёта хода.
     * <p>
     * Берётся из уже вычитанной ходом галактики, а не своей выборкой. Без него в тексте
     * стоял ИДЕНТИФИКАТОР системы: «флот у системы 748cf6c1-…» — игроку такая строка не
     * говорит ничего. Названия нет только у системы, которой нет.
     */
    private String systemName(TurnContext context, UUID systemId) {
        return context.systems().stream()
                .filter(system -> system.getId().equals(systemId))
                .map(StarSystemEntity::getName)
                .findFirst()
                .orElseGet(() -> messages.get("turn.system.unnamed"));
    }

    private FleetEntity travelling(TurnContext context, PlayerEntity player, Random random) {
        List<FleetEntity> fleets = fleetRepository.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(fleet -> fleet.getTargetSystemId() != null && fleet.getArrivalTurn() != null)
                .sorted(fleetOrder(context))
                .toList();
        return fleets.isEmpty() ? null : fleets.get(random.nextInt(fleets.size()));
    }

    /**
     * Соседняя ступень богатства недр; {@code null} — дальше некуда.
     * <p>
     * Ступени берутся по выработке на рабочего, а не по порядку в перечислении: перечисление
     * заведено не по возрастанию, и полагаться на него было бы ошибкой.
     */
    private MineralRichness shift(MineralRichness from, int step) {
        if (from == null) {
            return null;
        }
        List<MineralRichness> ladder = java.util.Arrays.stream(MineralRichness.values())
                .sorted(Comparator.comparingInt(MineralRichness::getProductionPerWorker))
                .toList();
        int index = ladder.indexOf(from) + step;
        return index < 0 || index >= ladder.size() ? null : ladder.get(index);
    }

    /** Ступень климата вниз по цепочке терраформирования; {@code null} — портить нечего. */
    private PlanetClimate worsen(PlanetClimate from) {
        if (from == null || from.getTerraformOrder() <= 1) {
            return null;
        }
        return java.util.Arrays.stream(PlanetClimate.values())
                .filter(climate -> climate.getTerraformOrder().equals(from.getTerraformOrder() - 1))
                .findFirst()
                .orElse(null);
    }

    private Integer clamp(Integer trust) {
        return Math.max(0, Math.min(100, trust));
    }
}
