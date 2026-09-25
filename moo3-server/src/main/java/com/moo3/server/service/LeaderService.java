package com.moo3.server.service;

import com.moo3.server.domain.Leader;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerLeaderEntity;
import com.moo3.server.domain.enums.LeaderKind;
import com.moo3.server.domain.enums.LeaderState;
import com.moo3.server.dto.LeaderDto;
import com.moo3.server.dto.LeaderSkillDto;
import com.moo3.server.dto.LeadersDto;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.PlayerLeaderRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.StarSystemRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Лидеры империи — п. 6.
 * <p>
 * <b>Лидер — это наёмник.</b> Он сам предлагает службу, ждёт решения тридцать ходов и
 * уходит, если ответа нет; за наём берёт разовую плату, за службу — жалованье каждый ход.
 * Нанятый растёт в звании (очко опыта за ход) и с каждым званием работает лучше. Всё это
 * — правила оригинала; числа лежат в {@link LeaderRules}, состав лидеров — в справочнике
 * {@link LeaderCatalog}.
 * <p>
 * <b>Мест по четыре на каждый род</b>, и заняты они раздельно: полный набор губернаторов
 * не мешает нанять адмирала. Пока все места рода заняты, новые лидеры этого рода не
 * приходят вовсе — как в MOO II, где это и есть повод уволить слабого.
 * <p>
 * <b>Раса решает, кто приходит</b> (п. 7): отталкивающей реже и дороже, обаятельной чаще
 * и вдвое дешевле, и дипломатов с торговцами отталкивающей не предлагают вовсе — ей
 * нечего с ними делать.
 * <p>
 * <b>Прибавки считает соседняя служба</b> — {@link LeaderBonusService}: её спрашивают
 * колонии, флот и исследования, а наём тянет за собой исследования (лидер приносит
 * технологии), и в одном классе это замкнулось бы в кольцо зависимостей.
 */
@Service
public class LeaderService {

    private static final Logger log = LoggerFactory.getLogger(LeaderService.class);

    private final Messages messages;
    private final LeaderCatalog catalog;
    private final EmpireActivityService activity;
    private final LeaderRules rules;
    private final PlayerLeaderRepository leaders;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository systems;
    private final FleetRepository fleets;
    private final RaceService raceService;
    private final ResearchService researchService;
    /**
     * Множители зерна партии — п. 6, п. 15: у каждой случайности игры свой набор, иначе
     * жребий лидеров шёл бы в ногу с жребием исследований и событий галактики.
     */
    private static final long SEED_TURN = 137L;
    private static final long SEED_SLOT = 19L;
    private static final long SEED_HIRE = 7919L;

    public LeaderService(Messages messages,
                         LeaderCatalog catalog,
                         LeaderRules rules,
                         PlayerLeaderRepository leaders,
                         PlayerRepository playerRepository,
                         StarSystemRepository systems,
                         FleetRepository fleets,
                         RaceService raceService,
                         ResearchService researchService,
                         EmpireActivityService activity) {
        this.messages = messages;
        this.catalog = catalog;
        this.activity = activity;
        this.rules = rules;
        this.leaders = leaders;
        this.playerRepository = playerRepository;
        this.systems = systems;
        this.fleets = fleets;
        this.raceService = raceService;
        this.researchService = researchService;
    }

    /** Всё, что игрок знает о своих лидерах: предложения и служащие. */
    @Transactional(readOnly = true)
    public List<PlayerLeaderEntity> of(PlayerEntity player) {
        return leaders.findAllByPlayerId(player.getId()).stream()
                .filter(row -> row.getState() != LeaderState.DISMISSED)
                .sorted(Comparator.comparing(PlayerLeaderEntity::getState)
                        .thenComparing(PlayerLeaderEntity::getOfferedTurn))
                .toList();
    }

    /**
     * Лидеры сразу нескольких империй — одной выборкой на партию.
     * <p>
     * Нужно фазе ИИ: своих офицеров нанимают и соседи, а запрос «на игрока» изнутри
     * посчитанного хода заставляет Hibernate сбрасывать в базу всё, что ход успел
     * изменить (см. «Грабли» в CLAUDE.md). Отказавшие в выборку не идут: их не наймёшь.
     *
     * @return игрок → его лидеры; у кого лидеров нет, того в карте нет вовсе
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<PlayerLeaderEntity>> ofAll(List<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PlayerLeaderEntity>> byPlayer = new HashMap<>();
        for (PlayerLeaderEntity row : leaders.findAllByPlayerIdIn(playerIds)) {
            if (row.getState() != LeaderState.DISMISSED) {
                byPlayer.computeIfAbsent(row.getPlayerId(), id -> new ArrayList<>()).add(row);
            }
        }
        return byPlayer;
    }

    /**
     * Экран лидеров целиком — п. 6: предложения, служащие и занятые места.
     * <p>
     * Сила способностей приходит уже пересчитанной под нынешнее звание: экрану незачем
     * повторять формулу роста, а разъехаться с сервером она не должна.
     */
    @Transactional(readOnly = true)
    public LeadersDto state(PlayerEntity player, Integer turn) {
        List<PlayerLeaderEntity> own = of(player);
        Map<UUID, String> systemNames = new HashMap<>();
        own.stream()
                .map(PlayerLeaderEntity::getStarSystemId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .forEach(id -> systems.findById(id)
                        .ifPresent(system -> systemNames.put(id, system.getName())));

        List<LeaderDto> offers = new ArrayList<>();
        List<LeaderDto> colony = new ArrayList<>();
        List<LeaderDto> ship = new ArrayList<>();
        int salary = 0;
        for (PlayerLeaderEntity row : own) {
            LeaderDto dto = toDto(row, systemNames);
            if (row.getState() == LeaderState.OFFERED) {
                offers.add(dto);
                continue;
            }
            salary += row.getSalary();
            Leader leader = catalog.require(row.getLeaderCode());
            if (Boolean.TRUE.equals(leader.has("MEGAWEALTH"))) {
                salary -= LeaderRules.MEGAWEALTH_CREDITS;
            }
            if (row.getKind() == LeaderKind.COLONY) {
                colony.add(dto);
            } else {
                ship.add(dto);
            }
        }

        return new LeadersDto(offers, colony, ship,
                LeaderRules.SLOTS_PER_KIND, LeaderRules.SLOTS_PER_KIND,
                salary, player.getCredits());
    }

    /** Строка службы в вид для экрана: справочник плюс состояние из базы. */
    private LeaderDto toDto(PlayerLeaderEntity row, Map<UUID, String> systemNames) {
        Leader leader = catalog.require(row.getLeaderCode());
        Integer rankIndex = catalog.rankIndex(leader.kind(), row.getExperience());
        Integer startIndex = catalog.rankIndex(leader.kind(), leader.startExperience());

        List<LeaderSkillDto> skills = leader.skills().stream().map(skill -> {
            LeaderCatalog.Ability ability = catalog.ability(skill.ability());
            return new LeaderSkillDto(
                    skill.ability(),
                    ability == null ? skill.ability() : ability.name(),
                    ability == null ? "GENERAL" : ability.kind(),
                    ability == null ? "POINTS" : ability.unit(),
                    rules.skillValue(skill, rankIndex, startIndex),
                    skill.perLevel(),
                    ability == null ? "ALWAYS" : ability.works(),
                    ability == null ? null : ability.description(),
                    ability == null ? null : ability.note());
        }).toList();

        return new LeaderDto(
                row.getId(),
                leader.code(),
                leader.name(),
                leader.title(),
                leader.kind().name(),
                messages.label(leader.kind()),
                leader.raceCode(),
                row.getState().name(),
                messages.label(row.getState()),
                catalog.rank(leader.kind(), row.getExperience()).title(),
                row.getExperience(),
                row.getHireCost(),
                row.getSalary(),
                skills,
                leader.techs(),
                row.getOfferedTurn(),
                row.getOfferedTurn() + LeaderRules.OFFER_LIFETIME_TURNS,
                row.getStarSystemId(),
                systemNames.get(row.getStarSystemId()),
                row.getFleetId(),
                row.getArrivesTurn());
    }

    /**
     * Нанять лидера — п. 6.
     * <p>
     * Плата разовая и списывается сразу; технологии, которые лидер приносит с собой,
     * достаются империи тут же, до всякого назначения, — как в оригинале.
     */
    @Transactional
    public PlayerLeaderEntity hire(PlayerEntity player, UUID leaderRowId, Integer turn) {
        return hire(player, leaderRowId, turn, null);
    }

    /**
     * Тот же наём, но с зерном партии — п. 6: технологию лидера, который приносит одну из
     * нескольких, выбирает жребий, и в прогонах балансировки он должен повторяться.
     * Зерно {@code null} значит «неоткуда взять» (действие игрока через контроллер) — тогда
     * жребий свой у каждого вызова, как и было.
     */
    @Transactional
    public PlayerLeaderEntity hire(PlayerEntity player, UUID leaderRowId, Integer turn, Long seed) {
        PlayerLeaderEntity row = require(player, leaderRowId);
        if (row.getState() != LeaderState.OFFERED) {
            throw new ConflictException("leader.notOffering");
        }
        Leader leader = catalog.require(row.getLeaderCode());
        if (hiredOf(player.getId(), row.getKind()).size() >= LeaderRules.SLOTS_PER_KIND) {
            throw new ConflictException("leader.noSlots", LeaderRules.SLOTS_PER_KIND);
        }
        if (player.getCredits() < row.getHireCost()) {
            throw new ConflictException("leader.hireNoCredits", row.getHireCost(), player.getCredits());
        }

        player.setCredits(player.getCredits() - row.getHireCost());
        /*
          ИГРОКА НАДО СОХРАНИТЬ ЯВНО, и это не перестраховка. Сюда он приходит из
          `GameAccess`, то есть вычитан ВНЕ этой транзакции и в ней отсоединён: правка его
          полей сама в базу не уходит. Без этой строки наём «удавался» даром — лидер
          нанимался, а казна оставалась прежней (замер: две платы по 125 и 175 кредитов, а
          в казне те же 309). У империи ИИ такого не было: она нанимает изнутри
          посчитанного хода, где игрок управляется контекстом, — и платила честно, то есть
          человек получал лидеров даром, а сосед за деньги.

          Тот же приём, что у выкупа стройки (`ColonyService.buyProject`): правку казны
          сопровождает `playerRepository.save`.
        */
        playerRepository.save(player);
        row.setState(LeaderState.HIRED);
        row.setHiredTurn(turn);
        grantTechs(player, leader, turn, seed);
        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.LEADER);
        log.info("Игрок {} нанял лидера {} за {} кр.",
                player.getName(), leader.logName(), row.getHireCost());
        return leaders.save(row);
    }

    /**
     * Отказать или уволить — п. 6.
     * <p>
     * Отказанный лидер не пропадает: в MOO II он предложит службу снова, поднявшись в
     * звании. Уволенный со службы освобождает место и перестаёт получать жалованье.
     */
    @Transactional
    public PlayerLeaderEntity dismiss(PlayerEntity player, UUID leaderRowId) {
        PlayerLeaderEntity row = require(player, leaderRowId);
        if (row.getState() == LeaderState.DISMISSED) {
            throw new ConflictException("leader.alreadyRefused");
        }
        row.setState(LeaderState.DISMISSED);
        row.setStarSystemId(null);
        row.setFleetId(null);
        row.setArrivesTurn(null);
        return leaders.save(row);
    }

    /**
     * Назначить лидера — колониального в систему, корабельного во флот (п. 6).
     * <p>
     * До места лидер добирается пять ходов, и до прибытия его способности не работают —
     * кроме тех, что в оригинале работают всегда (они помечены в справочнике
     * {@code works: ALWAYS}). В систему, где стоит офицерский резерв — родную, — он
     * попадает сразу.
     * <p>
     * В системе может служить только один колониальный лидер: назначенный туда, где уже
     * есть свой, отправляет прежнего обратно в резерв, как в оригинале.
     */
    @Transactional
    public PlayerLeaderEntity assign(PlayerEntity player, UUID leaderRowId,
                                     UUID targetId, Integer turn, UUID homeSystemId) {
        PlayerLeaderEntity row = require(player, leaderRowId);
        if (row.getState() != LeaderState.HIRED) {
            throw new ConflictException("leader.hiredOnly");
        }

        if (targetId == null) {
            row.setStarSystemId(null);
            row.setFleetId(null);
            row.setArrivesTurn(null);
            return leaders.save(row);
        }

        if (row.getKind() == LeaderKind.COLONY) {
            systems.findById(targetId).orElseThrow(
                    () -> new NotFoundException("system.notFound", targetId));
            leaders.findAllByPlayerId(player.getId()).stream()
                    .filter(other -> other.getKind() == LeaderKind.COLONY)
                    .filter(other -> targetId.equals(other.getStarSystemId()))
                    .filter(other -> !other.getId().equals(row.getId()))
                    .forEach(other -> {
                        other.setStarSystemId(null);
                        other.setArrivesTurn(null);
                        leaders.save(other);
                    });
            row.setStarSystemId(targetId);
            row.setFleetId(null);
        } else {
            fleets.findById(targetId).orElseThrow(
                    () -> new NotFoundException("fleet.notFound", targetId));
            row.setFleetId(targetId);
            row.setStarSystemId(null);
        }

        Boolean instant = targetId.equals(homeSystemId);
        row.setArrivesTurn(Boolean.TRUE.equals(instant) ? turn : turn + LeaderRules.TRAVEL_TURNS);
        return leaders.save(row);
    }

    /**
     * Ход лидеров: опыт, жалованье, новые предложения и уход тех, кого не наняли.
     * <p>
     * Зовётся фазой конца хода одним вызовом на партию: лидеры всех игроков читаются
     * одной выборкой — запрос «на игрока» внутри посчитанного хода стоит дороже, чем
     * кажется (см. «Грабли» в CLAUDE.md).
     *
     * @return сколько кредитов каждый игрок потратил на жалованье (минус) или получил
     *         от «Богачей» (плюс)
     */
    @Transactional
    public Map<UUID, Integer> advance(List<PlayerEntity> players, Integer turn,
                                      Map<UUID, RaceEffects> races, Long seed) {
        List<UUID> playerIds = players.stream().map(PlayerEntity::getId).toList();
        List<PlayerLeaderEntity> all = leaders.findAllByPlayerIdIn(playerIds);
        Map<UUID, List<PlayerLeaderEntity>> byPlayer = new HashMap<>();
        all.forEach(row -> byPlayer.computeIfAbsent(row.getPlayerId(), id -> new ArrayList<>()).add(row));

        Map<UUID, Integer> money = new HashMap<>();
        for (PlayerEntity player : players) {
            // Список свой, изменяемый: у игрока без лидеров карта отдавала неизменяемый
            // List.of(), и первое же предложение роняло весь ход UnsupportedOperationException.
            List<PlayerLeaderEntity> own = new ArrayList<>(
                    byPlayer.getOrDefault(player.getId(), List.of()));
            RaceEffects race = races.get(player.getId());
            int balance = 0;

            for (PlayerLeaderEntity row : own) {
                if (row.getState() == LeaderState.OFFERED
                        && turn - row.getOfferedTurn() >= LeaderRules.OFFER_LIFETIME_TURNS) {
                    // Предложение ждало тридцать ходов и уходит: лидер предложит службу
                    // кому-то другому, а к этому игроку может вернуться позже.
                    row.setState(LeaderState.DISMISSED);
                    continue;
                }
                if (row.getState() != LeaderState.HIRED) {
                    continue;
                }
                row.setExperience(row.getExperience() + LeaderRules.EXPERIENCE_PER_TURN);
                Leader leader = catalog.require(row.getLeaderCode());
                balance += Boolean.TRUE.equals(leader.has("MEGAWEALTH"))
                        ? LeaderRules.MEGAWEALTH_CREDITS
                        : -row.getSalary();
            }

            // Жребий выводится из зерна партии, хода и места игрока: та же партия с тем
             // же зерном должна давать тех же лидеров — иначе парные прогоны балансировки
             // сравнивают не расы, а разную удачу (balance-metrics-works.txt, этап 0).
            Random random = new Random(seed * SEED_TURN + turn * SEED_SLOT + player.getSlot());
            offer(player, own, turn, race, random).ifPresent(offered -> own.add(offered));
            money.put(player.getId(), balance);
        }

        leaders.saveAll(all);
        return money;
    }

    /**
     * Новое предложение службы, если сегодня повезло и место есть.
     * <p>
     * Порядок правил тот же, что в оригинале: пока все места рода заняты, лидеры этого
     * рода не приходят; появляются они примерно в порядке званий; отталкивающей расе реже
     * и без дипломатов, обаятельной чаще. Уже предложенного или служащего не предлагают
     * второй раз, а отказанный может прийти снова.
     */
    private java.util.Optional<PlayerLeaderEntity> offer(PlayerEntity player,
                                                         List<PlayerLeaderEntity> own,
                                                         Integer turn,
                                                         RaceEffects race,
                                                         Random random) {
        Boolean repulsive = race != null && Boolean.TRUE.equals(race.repulsive());
        Boolean charismatic = race != null && Boolean.TRUE.equals(race.charismatic());
        if (random.nextInt(100) >= rules.offerChancePercent(repulsive, charismatic)) {
            return java.util.Optional.empty();
        }

        List<String> busy = own.stream()
                .filter(row -> row.getState() != LeaderState.DISMISSED)
                .map(PlayerLeaderEntity::getLeaderCode)
                .toList();
        List<Leader> candidates = catalog.all().stream()
                .filter(leader -> !busy.contains(leader.code()))
                .filter(leader -> Boolean.TRUE.equals(rules.dueByTurn(leader, turn)))
                .filter(leader -> Boolean.TRUE.equals(rules.suitable(leader, repulsive)))
                .filter(leader -> hired(own, leader.kind()) < LeaderRules.SLOTS_PER_KIND)
                .filter(leader -> offered(own, leader.kind()) == 0)
                .toList();
        if (candidates.isEmpty()) {
            return java.util.Optional.empty();
        }

        Leader leader = candidates.get(random.nextInt(candidates.size()));
        PlayerLeaderEntity row = new PlayerLeaderEntity();
        row.setPlayerId(player.getId());
        row.setLeaderCode(leader.code());
        row.setKind(leader.kind());
        row.setState(LeaderState.OFFERED);
        row.setExperience(leader.startExperience());
        row.setOfferedTurn(turn);
        Integer cost = rules.hireCost(leader,
                catalog.rankIndex(leader.kind(), row.getExperience()),
                famousDiscount(own), repulsive, charismatic);
        row.setHireCost(cost);
        row.setSalary(rules.salary(leader, cost));
        log.debug("Игроку {} предлагает службу {} за {} кр.",
                player.getName(), leader.logName(), cost);
        return java.util.Optional.of(leaders.save(row));
    }

    /**
     * Скидка сильнейшей «Знаменитости» на службе — в кредитах.
     * <p>
     * Правило оригинала: из нескольких знаменитостей считается только самая громкая, а не
     * их сумма.
     */
    private Integer famousDiscount(List<PlayerLeaderEntity> own) {
        return own.stream()
                .filter(row -> row.getState() == LeaderState.HIRED)
                .mapToInt(row -> {
                    Leader leader = catalog.require(row.getLeaderCode());
                    return leader.skillsByAbility().containsKey("FAMOUS")
                            ? rules.skillValue(leader.skillsByAbility().get("FAMOUS"),
                            catalog.rankIndex(leader.kind(), row.getExperience()),
                            catalog.rankIndex(leader.kind(), leader.startExperience()))
                            : 0;
                })
                .max()
                .orElse(0);
    }

    private List<PlayerLeaderEntity> hiredOf(UUID playerId, LeaderKind kind) {
        return leaders.findAllByPlayerId(playerId).stream()
                .filter(row -> row.getState() == LeaderState.HIRED && row.getKind() == kind)
                .toList();
    }

    private int hired(List<PlayerLeaderEntity> own, LeaderKind kind) {
        return (int) own.stream()
                .filter(row -> row.getState() == LeaderState.HIRED && row.getKind() == kind)
                .count();
    }

    private int offered(List<PlayerLeaderEntity> own, LeaderKind kind) {
        return (int) own.stream()
                .filter(row -> row.getState() == LeaderState.OFFERED && row.getKind() == kind)
                .count();
    }

    /**
     * Технологии, которые лидер приносит с собой. Неизвестные дереву коды пропускаются:
     * справочник лидеров живёт своей жизнью, и правка дерева не должна ронять наём.
     */
    private void grantTechs(PlayerEntity player, Leader leader, Integer turn, Long seed) {
        if (leader.techs().isEmpty()) {
            return;
        }
        Random random = seed == null
                ? new Random()
                : new Random(seed * SEED_HIRE + turn * SEED_SLOT + leader.code().hashCode());
        List<String> given = Boolean.TRUE.equals(leader.techsRandomOne())
                ? List.of(leader.techs().get(random.nextInt(leader.techs().size())))
                : leader.techs();
        researchService.grantByCode(player, given, turn);
    }

    private PlayerLeaderEntity require(PlayerEntity player, UUID leaderRowId) {
        PlayerLeaderEntity row = leaders.findById(leaderRowId)
                .orElseThrow(() -> new NotFoundException("leader.notFound", leaderRowId));
        if (!row.getPlayerId().equals(player.getId())) {
            throw new NotFoundException("leader.notFound", leaderRowId);
        }
        return row;
    }

}
