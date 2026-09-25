package com.moo3.server.service;

import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.AiPersonality;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.DiplomacyAction;
import com.moo3.server.domain.enums.DiplomacyStance;
import com.moo3.server.domain.enums.DiplomacyTreaty;
import com.moo3.server.dto.DiplomacyActionRequest;
import com.moo3.server.dto.DiplomacyRelationDto;
import com.moo3.server.dto.TechTradeDto;
import com.moo3.server.domain.enums.FuelTech;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashSet;

/**
 * Дипломатия — п. 15.
 * <p>
 * Империи, которые ещё не встретились, друг о друге не знают вовсе: отношений между ними
 * нет, и в списке дипломатии их не видно.
 * <p>
 * <b>Знакомятся двумя путями, и главный из них — дальность.</b> В MOO II контакт
 * возникает, «когда одна из сторон в состоянии послать корабли без дополнительных баков
 * в системы, занятые другой»: лететь никуда не нужно, достаточно оказаться в пределах
 * досягаемости — {@link #contactByRange}. Второй путь — разведка чужой системы
 * ({@link ExplorationService}): увидел чужую колонию, значит, познакомился с хозяином.
 * Он остаётся для случая, когда флот дотянулся дальше собственной дальности империи,
 * прыгая от колонии к колонии.
 * <p>
 * Знакомство начинается с нейтралитета и доверия в половину шкалы. Дальше — договоры
 * MOO II: торговый и исследовательский поднимают доход и науку обеих сторон, пакт о
 * ненападении запрещает войну, союз добавляет к нему общую разведку. Мир и войну стороны
 * объявляют друг другу, дань требуют, договоры разрывают — каждое действие сдвигает
 * доверие.
 * <p>
 * Состояние войны и мира общее — сервис держит его одинаковым в обеих строках, как и
 * набор договоров; доверие у каждой стороны своё: одна империя может считать другую
 * другом, пока та готовит удар.
 * <p>
 * <b>У каждой империи ИИ свой характер</b> ({@link com.moo3.server.domain.enums.AiPersonality})
 * — шесть характеров MOO II, от беспощадного до миролюбивого. Он решает две вещи: насколько
 * сильно империя принимает к сердцу хорошее и плохое ({@link #gainTrust}, {@link #loseTrust})
 * и насколько охотно соглашается ({@link #agrees}, {@link #treatyBias}). Ксенофоб «вдвое
 * слабее принимает хорошее и вдвое сильнее плохое», благородный не прощает ударов без
 * повода, миролюбивый быстрее идёт на мир. Сам ИИ ведёт дипломатию в
 * {@code AiDiplomacyService} — там же и решение о войне.
 * <p>
 * <b>Обмен и подарки</b> (п. 15) — то, чем в MOO II занимаются чаще всего остального.
 * Обмен технологиями требует согласия и подчиняется правилу оригинала: ИИ соглашается
 * только на выгодную себе сделку, то есть когда получает не дешевле, чем отдаёт.
 * Подарок согласия не требует вовсе — его не отвергают, — и поднимает доверие.
 */
@Service
public class DiplomacyService {

    private static final Logger log = LoggerFactory.getLogger(DiplomacyService.class);

    /** Доверие при первом знакомстве — середина шкалы. */
    private static final int INITIAL_TRUST = 50;

    /** С какого доверия принимают мир. */
    private static final int PEACE_TRUST = 50;

    /** Насколько доверие меняется от согласия, отказа, разрыва и объявления войны. */
    private static final int AGREEMENT_BONUS = 15;
    private static final int REFUSAL_PENALTY = 10;
    private static final int BREAK_PENALTY = 25;
    private static final int WAR_PENALTY = 40;

    /** Доверие, ниже которого дань платят из страха, и выше которого — по дружбе. */
    private static final int TRIBUTE_FEAR_TRUST = 25;
    private static final int TRIBUTE_FRIEND_TRUST = 75;

    /** Какую часть казны отдаёт данник и сколько это стоит его доверию. */
    private static final int TRIBUTE_PERCENT = 25;
    private static final int TRIBUTE_TRUST_PENALTY = 10;

    /**
     * С какого доверия соглашаются на обмен технологиями.
     * <p>
     * Ниже мира: обмен выгоден обеим сторонам и на войну не влияет, поэтому торгуют
     * знанием и те, кто мириться ещё не готов. Реконструкция: в MOO II порог не назван,
     * зато названо главное правило — ИИ соглашается лишь на выгодную себе сделку,
     * и оно вынесено в {@link #agreesToExchange}.
     */
    private static final int EXCHANGE_TRUST = 35;

    /**
     * Сколько доверия приносит подарок — п. 15.
     * <p>
     * Считается от размера подарка относительно казны дарящего: отдать половину казны
     * значит куда больше, чем бросить монету со стола. Потолок не даёт купить дружбу
     * одним переводом — в MOO II подарки располагают, но не заменяют политику.
     */
    private static final int GIFT_TRUST_MAX = 20;

    /** Доверие за подаренную технологию: знание дороже денег, и помнят его дольше. */
    private static final int GIFT_TECH_TRUST = 25;

    /**
     * На сколько характер двигает планку согласия — п. 15.
     * <p>
     * <i>Реконструкция:</i> MOO II называет склонности («миролюбивый дорожит миром»,
     * «ксенофоб не доверяет никому»), но не числа. Пятнадцать очков — заметно, но за
     * характер не решает: договор всё равно надо заслужить.
     */
    private static final int TREATY_BIAS = 15;

    private final DiplomacyRelationRepository relationRepository;
    private final EmpireActivityService activity;
    private final Messages messages;
    private final PlayerRepository playerRepository;
    private final RaceService raceService;
    private final PlayerRoster playerRoster;
    private final PlayerEventService playerEvents;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final ResearchCatalog researchCatalog;
    private final LeaderBonusService leaderBonuses;

    public DiplomacyService(Messages messages,
                            DiplomacyRelationRepository relationRepository,
                            PlayerRepository playerRepository,
                            RaceService raceService,
                            PlayerRoster playerRoster,
                            PlayerEventService playerEvents,
                            PlayerTechnologyRepository playerTechnologyRepository,
                            ResearchCatalog researchCatalog,
                            LeaderBonusService leaderBonuses,
                            EmpireActivityService activity) {
        this.leaderBonuses = leaderBonuses;
        this.activity = activity;
        this.messages = messages;
        this.relationRepository = relationRepository;
        this.playerRepository = playerRepository;
        this.raceService = raceService;
        this.playerRoster = playerRoster;
        this.playerEvents = playerEvents;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.researchCatalog = researchCatalog;
    }

    /** С кем игрок знаком, на каких условиях и по каким договорам — п. 15. */
    public List<DiplomacyRelationDto> relations(PlayerEntity player) {
        List<DiplomacyRelationEntity> relations = relationRepository.findAllByPlayerId(player.getId());
        if (relations.isEmpty()) {
            return List.of();
        }

        Map<UUID, PlayerEntity> others = playerRepository.findAllById(
                        relations.stream().map(DiplomacyRelationEntity::getOtherPlayerId).toList()).stream()
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        Map<String, String> raceNames = playerRoster.raceNames();
        // Доверие соседей к игроку — их собственные строки: по ним они и решают.
        Map<UUID, Integer> theirTrust = relationRepository.findAllByOtherPlayerId(player.getId())
                .stream()
                .collect(Collectors.toMap(DiplomacyRelationEntity::getPlayerId,
                        DiplomacyRelationEntity::getTrust, (first, second) -> first));

        return relations.stream()
                .map(relation -> toDto(relation, others.get(relation.getOtherPlayerId()), raceNames,
                        theirTrust.get(relation.getOtherPlayerId()), null, player))
                .sorted((first, second) -> first.metTurn().compareTo(second.metTurn()))
                .toList();
    }

    /**
     * Знакомство по дальности — п. 15, главный путь контакта в MOO II.
     * <p>
     * Правило оригинала: империи узнают друг о друге, когда одна из них может послать
     * корабли <b>без дополнительных баков</b> в системы, занятые другой. Лететь при этом
     * никуда не надо — соседей видно и так: по радиопереговорам, по чужим кораблям на
     * границе, по чему угодно. Поэтому дипломатия в оригинале начинается рано, сама
     * собой, и растёт вместе с империей и с топливом; знакомство «только когда флот
     * доберётся» оставляло список дипломатии пустым половину партии.
     * <p>
     * Дальность считается от <b>занятых систем</b> — тех, где у империи есть колония.
     * Достаточно одной стороны: если сосед дотягивается до меня, знакомы мы оба, даже
     * когда мой флот до него не долетит. Аванпостов в игре пока нет; когда появятся,
     * они встанут сюда же — в MOO II дальность меряется и от них.
     */
    @Transactional
    public void contactByRange(TurnContext context) {
        List<PlayerEntity> players = context.players();
        if (players.size() < 2) {
            return;
        }

        Map<UUID, StarSystemEntity> systemsById = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, Function.identity()));

        // Занятые системы и дальность — по разу на игрока: пар в галактике на восьмерых
        // двадцать восемь, и ходить за этим на каждую пару значило бы читать одно и то же.
        // Изученное читается одной выборкой на всех: дальность зависит от топлива.
        Map<UUID, Set<String>> technologies = technologiesOf(players);
        // Кто с кем уже знаком — одной выборкой: запросом на пару это двадцать восемь
        // походов в базу за ход, и каждый из них сбрасывал туда же всё, что ход успел
        // изменить. На конце хода это была самая дорогая фаза после исследований.
        Set<String> known = relationRepository
                .findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList()).stream()
                .map(relation -> relation.getPlayerId() + ":" + relation.getOtherPlayerId())
                .collect(Collectors.toSet());
        Map<UUID, List<StarSystemEntity>> occupied = new LinkedHashMap<>();
        Map<UUID, Integer> rangeParsecs = new HashMap<>();
        for (PlayerEntity player : players) {
            List<StarSystemEntity> mine = context.coloniesOf(player.getId()).stream()
                    .map(context::systemOf)
                    .distinct()
                    .map(systemsById::get)
                    .filter(Objects::nonNull)
                    .toList();
            occupied.put(player.getId(), mine);
            // Карта в парсеках (п. 4.2), дальность топлива — тоже: переводить нечего.
            rangeParsecs.put(player.getId(),
                    FuelTech.baseRangeParsecs(technologies.getOrDefault(player.getId(), Set.of())));
        }

        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                PlayerEntity first = players.get(i);
                PlayerEntity second = players.get(j);
                if (known.contains(first.getId() + ":" + second.getId())) {
                    continue;
                }
                Integer reach = Math.max(rangeParsecs.get(first.getId()), rangeParsecs.get(second.getId()));
                if (withinReach(occupied.get(first.getId()), occupied.get(second.getId()), reach)
                        && Boolean.TRUE.equals(meet(first, second, context.turn()))) {
                    announce(context, first, second);
                }
            }
        }
    }

    /** Дотягивается ли хоть одна занятая система одной империи до системы другой. */
    private Boolean withinReach(List<StarSystemEntity> mine, List<StarSystemEntity> theirs,
                                Integer reachParsecs) {
        double limit = (double) reachParsecs * reachParsecs;
        for (StarSystemEntity from : mine) {
            for (StarSystemEntity to : theirs) {
                double dx = from.getXParsec() - to.getXParsec();
                double dy = from.getYParsec() - to.getYParsec();
                // Сравниваем квадраты: корень здесь не нужен, а на восьми империях с
                // десятками колоний это единственная горячая точка знакомства.
                if (dx * dx + dy * dy <= limit) {
                    return Boolean.TRUE;
                }
            }
        }
        return Boolean.FALSE;
    }

    /** Обе стороны узнают о знакомстве в итогах хода: событие касается каждой. */
    private void announce(TurnContext context, PlayerEntity first, PlayerEntity second) {
        context.report().add(first.getId(), "DIPLOMACY",
                new MessageKey("turn.diplomacy.met", second.getName()));
        context.report().add(second.getId(), "DIPLOMACY",
                new MessageKey("turn.diplomacy.met", first.getName()));
    }

    /** Изученное участниками партии одной выборкой — по нему считается дальность кораблей. */
    private Map<UUID, Set<String>> technologiesOf(List<PlayerEntity> players) {
        return playerTechnologyRepository
                .findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(
                        technology -> technology.getPlayerId(),
                        Collectors.mapping(technology -> technology.getOptionCode(),
                                Collectors.toSet())));
    }

    /**
     * Знакомство с хозяевами колоний разведанной системы — п. 15.
     *
     * @return империи, с которыми игрок познакомился именно сейчас
     */
    @Transactional
    public List<PlayerEntity> meetOwnersOf(PlayerEntity player, StarSystemEntity system, Integer turn) {
        Set<UUID> owners = system.getPlanets().stream()
                .map(PlanetEntity::getOwnerPlayerId)
                .filter(Objects::nonNull)
                .filter(owner -> !owner.equals(player.getId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<PlayerEntity> met = new ArrayList<>();
        for (UUID ownerId : owners) {
            PlayerEntity other = playerRepository.findById(ownerId).orElse(null);
            if (other != null && Boolean.TRUE.equals(meet(player, other, turn))) {
                met.add(other);
                // Знакомство касается обоих: одному встретилась чужая колония, другому —
                // чужой разведчик. Оба узнают об этом в итогах хода.
                UUID gameId = player.getGame().getId();
                playerEvents.record(gameId, player.getId(), turn, "DIPLOMACY",
                        new MessageKey("turn.diplomacy.met", other.getName()), system.getId(), null);
                playerEvents.record(gameId, other.getId(), turn, "DIPLOMACY",
                        new MessageKey("turn.diplomacy.metYou", player.getName()), system.getId(), null);
            }
        }
        return met;
    }

    /**
     * Действие дипломатии — п. 15.
     * <p>
     * Согласия другой стороны требуют мир, договоры и дань; война и разрыв договора —
     * решение одной стороны: напасть и предать можно всегда.
     * <p>
     * <b>Конфликт не откатывает транзакцию</b> ({@code noRollbackFor}). Отсюда ходит не
     * только игрок: этот же метод зовёт {@link AiDiplomacyService} из фазы конца хода, и
     * зовёт внутри той самой транзакции, в которой считается весь ход. Обычный
     * {@link ConflictException} пометил бы её rollback-only, и невозможное предложение
     * соседа (договор с отталкивающей расой, война при действующем пакте) роняло бы
     * пересчёт хода целиком — так и вышло, пока пометки здесь не было. Откатывать при этом
     * нечего: конфликт всегда бросается до первой правки, проверками в начале действия.
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public DiplomacyRelationDto act(PlayerEntity player, DiplomacyActionRequest request, Integer turn) {
        DiplomacyRelationEntity mine = requireRelation(player.getId(), request.targetPlayerId());
        DiplomacyRelationEntity theirs = requireRelation(request.targetPlayerId(), player.getId());
        PlayerEntity other = playerRepository.findById(request.targetPlayerId())
                .orElseThrow(() -> new NotFoundException("empire.notFound", request.targetPlayerId()));

        requireNotRepulsive(player, other, request.action());

        /*
          Ответ собирается КЛЮЧОМ, а не текстом (п. 3.5): язык известен только в момент
          ответа, и по ключу же видно, был ли отказ, — раньше это решала проверка
          answer.startsWith("Отказ"), то есть управление шло по показываемому тексту.
        */
        MessageKey answer = switch (request.action()) {
            case DECLARE_WAR -> declareWar(player, other, mine, theirs);
            case PROPOSE_PEACE -> proposePeace(player, other, mine, theirs);
            case PROPOSE_TREATY -> proposeTreaty(player, other, mine, theirs, requireTreaty(request));
            case BREAK_TREATY -> breakTreaty(player, other, mine, theirs, requireTreaty(request));
            case DEMAND_TRIBUTE -> demandTribute(player, other, mine, theirs);
            case EXCHANGE_TECH -> exchangeTech(player, other, mine, theirs, request, turn);
            case GIFT_CREDITS -> giftCredits(player, other, theirs, request);
            case GIFT_TECH -> giftTech(player, other, theirs, request, turn);
        };

        // Вторая сторона узнаёт о случившемся в итогах своего хода: дипломатию с ней ведут,
        // не спрашивая, и без этого события чужая война свалилась бы как снег на голову.
        notifyOther(player, other, turn, request, answer);

        relationRepository.saveAll(List.of(mine, theirs));
        return toDto(mine, other, playerRoster.raceNames(), theirs.getTrust(),
                messages.get(answer.key(), answer.args()), player);
    }

    /**
     * Договориться с отталкивающей расой нельзя — п. 15, п. 7.
     * <p>
     * В MOO II у такой империи в переговорах остаются только объявление войны, мир и
     * капитуляция: договоров, союзов и дани для неё не существует. Правило смотрит на
     * <b>обе</b> стороны — переговоры ведут двое, и одной отталкивающей достаточно, чтобы
     * их не было: соседи такую империю не переносят ровно так же, как она их.
     * <p>
     * За это раса и возвращает шесть очков в конструкторе — самый щедрый недостаток
     * в игре; до этой проверки он ничего не стоил.
     */
    private void requireNotRepulsive(PlayerEntity player, PlayerEntity other, DiplomacyAction action) {
        if (action == DiplomacyAction.DECLARE_WAR || action == DiplomacyAction.PROPOSE_PEACE) {
            return;
        }
        if (Boolean.TRUE.equals(raceService.effects(player).repulsive())) {
            throw new ConflictException("diplomacy.youRepulsive");
        }
        if (Boolean.TRUE.equals(raceService.effects(other).repulsive())) {
            throw new ConflictException("diplomacy.theyRepulsive", other.getName());
        }
    }

    /**
     * Что договоры дают колониям империи — п. 15.
     * <p>
     * Торговый поднимает доход, исследовательский — науку; действуют они так же, как
     * здания и раса, и складываются с ними. Несколько договоров одного вида с разными
     * соседями складываются: чем шире торговая сеть, тем богаче империя.
     * <p>
     * Лидер-торговец добавляет процентов к торговому договору — п. 6, и только к нему:
     * в MOO II он ведает торговлей, а не наукой.
     */
    public BuildingEffects treatyEffects(UUID playerId) {
        return treatyEffects(Set.of(playerId)).getOrDefault(playerId, BuildingEffects.NONE);
    }

    /**
     * То же самое СРАЗУ НА ВСЕХ владельцев колоний — п. 15.
     * <p>
     * <b>Зачем отдельный метод.</b> Контекст колоний собирается каждый ход и звал прежний
     * {@code treatyEffects} по одному на игрока, а внутри каждого вызова сидели ТРИ
     * обращения в базу: отношения игрока, его лидеры и он сам. Шесть империй — восемнадцать
     * запросов каждый ход, изнутри посчитанного хода, где любой запрос заставляет Hibernate
     * сбросить в базу всё, что ход успел изменить. Это ровно тот запрет, что записан в
     * граблях проекта, и замер его подтвердил: выборка хода съедала 35 % всего конца хода —
     * больше, чем любая фаза.
     * <p>
     * Здесь те же три выборки делаются ОДИН РАЗ на всех: отношения, лидеры и игроки берутся
     * набором. Правило подсчёта не изменилось ни на йоту — сменился только способ достать
     * данные.
     */
    public Map<UUID, BuildingEffects> treatyEffects(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<DiplomacyRelationEntity>> byPlayer =
                relationRepository.findAllByPlayerIdIn(playerIds).stream()
                        .collect(Collectors.groupingBy(DiplomacyRelationEntity::getPlayerId));
        if (byPlayer.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LeaderBonusService.Bonuses> bonuses = leaderBonuses.of(byPlayer.keySet());
        // Прирождённые торговцы берут с договора на четверть больше — п. 7, MOO II:
        // «прибыль от торговых соглашений получает прибавку в 25%».
        Map<UUID, Integer> raceByPlayer = new HashMap<>();
        playerRepository.findAllById(byPlayer.keySet()).forEach(player ->
                raceByPlayer.put(player.getId(), raceService.effects(player).tradeTreatyPercent()));

        Map<UUID, BuildingEffects> effects = new HashMap<>();
        byPlayer.forEach((playerId, relations) -> {
            Integer trader = bonuses.containsKey(playerId)
                    ? bonuses.get(playerId).empireValue("TRADER")
                    : 0;
            Integer race = raceByPlayer.getOrDefault(playerId, 0);

            Map<BuildingEffectType, Integer> amounts = new EnumMap<>(BuildingEffectType.class);
            for (DiplomacyRelationEntity relation : relations) {
                for (DiplomacyTreaty treaty : relation.getTreaties()) {
                    if (treaty.getEffect() != null && treaty.getEffect().getColonyEffect() != null) {
                        Integer amount = treaty == DiplomacyTreaty.TRADE
                                ? treaty.getAmount() * (100 + trader + race) / 100
                                : treaty.getAmount();
                        amounts.merge(treaty.getEffect().getColonyEffect(), amount, Integer::sum);
                    }
                }
            }
            effects.put(playerId, new BuildingEffects(Map.copyOf(amounts), 0));
        });
        return effects;
    }

    /** Империи, с которыми у игрока союз — п. 15: их разведка общая. */
    /**
     * Союзники сразу многих империй — одной выборкой на всех.
     * <p>
     * Нужна разведке ИИ ({@link ExplorationService#exploredByAll}): союзники делятся
     * разведанным, и спрашивать их по одному значило бы запрос на игрока изнутри
     * посчитанного хода.
     */
    public Map<UUID, Set<UUID>> alliesOfAll(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> allies = new HashMap<>();
        relationRepository.findAllByPlayerIdIn(playerIds).stream()
                .filter(relation -> relation.getTreaties().contains(DiplomacyTreaty.ALLIANCE))
                .forEach(relation -> allies
                        .computeIfAbsent(relation.getPlayerId(), one -> new HashSet<>())
                        .add(relation.getOtherPlayerId()));
        return allies;
    }

    public Set<UUID> allies(UUID playerId) {
        return relationRepository.findAllByPlayerId(playerId).stream()
                .filter(relation -> relation.getTreaties().contains(DiplomacyTreaty.ALLIANCE))
                .map(DiplomacyRelationEntity::getOtherPlayerId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Запрещает ли договор нападение на эту империю — п. 15: пакт о ненападении и союз
     * держат руки связанными, пока их не разорвут.
     */
    public void requireAttackAllowed(UUID playerId, UUID targetPlayerId) {
        relationRepository.findByPlayerIdAndOtherPlayerId(playerId, targetPlayerId).ifPresent(relation -> {
            Set<DiplomacyTreaty> treaties = relation.getTreaties();
            if (treaties.contains(DiplomacyTreaty.ALLIANCE)) {
                throw new ConflictException("diplomacy.allyAttack");
            }
            if (treaties.contains(DiplomacyTreaty.NON_AGGRESSION)) {
                throw new ConflictException("diplomacy.nonAggression");
            }
        });
    }

    /** Знакомы ли империи — п. 15; иначе действия с ней невозможны. */
    public void requireKnown(UUID playerId, UUID otherPlayerId) {
        requireRelation(playerId, otherPlayerId);
    }

    /**
     * Дипломатический скандал: доверие пострадавшей стороны падает — п. 13.
     * Так отзываются удавшиеся операции шпионов.
     * <p>
     * Насколько сильно — решает характер пострадавшего (п. 15): благородный «вдвое
     * сильнее отзывается на нападения без повода и на диверсии», ксенофоб — на всё
     * враждебное разом. Кража и поимка агента как раз такие случаи.
     */
    @Transactional
    public void incident(UUID victimId, UUID culpritId, Integer trustPenalty) {
        relationRepository.findByPlayerIdAndOtherPlayerId(victimId, culpritId).ifPresent(relation -> {
            loseTrust(relation, playerRepository.findById(victimId).orElse(null), trustPenalty);
            relationRepository.save(relation);
        });
    }

    private MessageKey declareWar(PlayerEntity player, PlayerEntity other,
                              DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs) {
        if (mine.getStance() == DiplomacyStance.WAR) {
            throw new ConflictException("diplomacy.alreadyAtWar", other.getName());
        }
        // Войну запрещает не ВСЯКИЙ договор, а только тот, что её запрещает: пакт о
        // ненападении и союз ({@code DiplomacyTreaty.forbidsWar}). Здесь стояло «есть хоть
        // какой-то договор — сначала разорвите», и торговое соглашение запирало войну
        // наглухо. Измерено: империи ИИ пытались объявить войну 86 раз за партию и смогли
        // ТРИ — остальные 83 отказа приходились на торговый и исследовательский договоры,
        // которые к войне отношения не имеют. Галактика при этом стояла в вечном мире, а
        // наземное мерило балансировки не находило ни одного захвата.
        if (mine.getTreaties().stream().anyMatch(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))) {
            throw new ConflictException("diplomacy.breakTreatiesFirst", other.getName());
        }
        // Война обрывает то, что от договоров осталось: торговать и делиться наукой с тем,
        // кому только что объявил войну, нельзя. Пакт и союз сюда не попадают — их рвут
        // ДО объявления, иначе война обходила бы их молча.
        List.copyOf(mine.getTreaties()).forEach(treaty -> removeTreaty(mine, theirs, treaty));

        setStance(mine, theirs, DiplomacyStance.WAR);
        loseTrust(theirs, other, WAR_PENALTY);
        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.WAR);
        log.info("Игрок {} объявил войну империи {}", player.getName(), other.getName());
        return new MessageKey("diplomacy.answer.war");
    }

    private MessageKey proposePeace(PlayerEntity player, PlayerEntity other,
                                DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs) {
        if (mine.getStance() == DiplomacyStance.PEACE) {
            throw new ConflictException("diplomacy.alreadyAtPeace", other.getName());
        }
        if (theirs.getTrust() < charmed(player, PEACE_TRUST) + treatyBias(other)) {
            loseTrust(mine, player, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в мире", other.getName(), player.getName());
            return new MessageKey("diplomacy.refusal.trust");
        }

        setStance(mine, theirs, DiplomacyStance.PEACE);
        reward(player, other, mine, theirs);
        log.info("Империя {} приняла мир с игроком {}", other.getName(), player.getName());
        return new MessageKey("diplomacy.answer.peace");
    }

    private MessageKey proposeTreaty(PlayerEntity player, PlayerEntity other,
                                 DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                                 DiplomacyTreaty treaty) {
        if (mine.getTreaties().contains(treaty)) {
            throw new ConflictException("diplomacy.treatyExists", treaty, other.getName());
        }
        if (mine.getStance() == DiplomacyStance.WAR) {
            throw new ConflictException("diplomacy.peaceFirst");
        }
        if (!agrees(player, other, theirs, treaty)) {
            loseTrust(mine, player, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в договоре {}",
                    other.getName(), player.getName(), treaty.getLabel());
            return new MessageKey("diplomacy.refusal.trust");
        }

        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.TREATY);
        activity.record(player.getGame().getId(), other.getId(), EmpireActivityService.TREATY);
        addTreaty(mine, theirs, treaty);
        // Союз подразумевает ненападение: воевать с союзником нельзя и без отдельного пакта.
        if (treaty == DiplomacyTreaty.ALLIANCE) {
            addTreaty(mine, theirs, DiplomacyTreaty.NON_AGGRESSION);
            setStance(mine, theirs, DiplomacyStance.PEACE);
        }
        reward(player, other, mine, theirs);
        log.info("Империя {} подписала с игроком {} договор {}",
                other.getName(), player.getName(), treaty.getLabel());
        return new MessageKey("diplomacy.answer.treatySigned", treaty);
    }

    private MessageKey breakTreaty(PlayerEntity player, PlayerEntity other,
                               DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                               DiplomacyTreaty treaty) {
        if (!mine.getTreaties().contains(treaty)) {
            throw new ConflictException("diplomacy.noTreaty", treaty, other.getName());
        }

        removeTreaty(mine, theirs, treaty);
        // Разорванный союз тянет за собой ненападение: держать его в одиночку незачем.
        if (treaty == DiplomacyTreaty.ALLIANCE) {
            removeTreaty(mine, theirs, DiplomacyTreaty.NON_AGGRESSION);
        }
        loseTrust(theirs, other, BREAK_PENALTY);
        log.info("Игрок {} разорвал договор {} с империей {}",
                player.getName(), treaty.getLabel(), other.getName());
        return new MessageKey("diplomacy.answer.treatyBroken", treaty);
    }

    /**
     * Требование дани — п. 15.
     * <p>
     * Платят от страха (доверие низкое — соперник считает войну возможной) или по большой
     * приязни. В обоих случаях дань бьёт по доверию: её помнят.
     */
    private MessageKey demandTribute(PlayerEntity player, PlayerEntity other,
                                 DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs) {
        if (!agreesToTribute(player, theirs)) {
            loseTrust(theirs, other, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в дани", other.getName(), player.getName());
            return new MessageKey("diplomacy.refusal.tribute");
        }

        Integer tribute = Math.max(0, other.getCredits()) * TRIBUTE_PERCENT / 100;
        other.setCredits(other.getCredits() - tribute);
        player.setCredits(player.getCredits() + tribute);
        playerRepository.saveAll(List.of(player, other));

        loseTrust(theirs, other, TRIBUTE_TRUST_PENALTY);
        log.info("Империя {} выплатила игроку {} дань в {} кредитов",
                other.getName(), player.getName(), tribute);
        return new MessageKey("diplomacy.answer.tribute", tribute);
    }

    /**
     * Что можно обменять с этой империей — п. 15.
     * <p>
     * Обе половины сделки считаются вычитанием: моё за вычетом их и их за вычетом моего.
     * Технологии, которые есть у обоих, в обмене бессмысленны, и показывать их значило бы
     * предлагать игроку заведомый отказ.
     * <p>
     * Список того, что есть у соседа, — не утечка: в MOO II контакт как раз и «даёт
     * сведения о сопернике с момента знакомства», и обмен без такого списка невозможен —
     * пришлось бы угадывать код чужой технологии.
     */
    @Transactional(readOnly = true)
    public TechTradeDto tradeableWith(PlayerEntity player, UUID otherPlayerId) {
        requireRelation(player.getId(), otherPlayerId);

        Set<String> ours = technologiesOf(player.getId());
        Set<String> theirs = technologiesOf(otherPlayerId);
        return new TechTradeDto(offers(ours, theirs), offers(theirs, ours));
    }

    /** Технологии первой стороны, которых нет у второй, — в порядке дерева. */
    private List<TechTradeDto.TechnologyOfferDto> offers(Set<String> have, Set<String> lack) {
        return have.stream()
                .filter(code -> !lack.contains(code))
                .map(researchCatalog::place)
                .sorted(Comparator
                        .comparing(ResearchCatalog.TechnologyPlace::categoryCode)
                        .thenComparing(ResearchCatalog.TechnologyPlace::levelOrder))
                .map(place -> new TechTradeDto.TechnologyOfferDto(
                        place.option().code(),
                        place.option().name(),
                        place.categoryCode(),
                        place.levelOrder(),
                        place.levelCost()))
                .toList();
    }

    /**
     * Обмен технологиями — п. 15, самое ходовое действие дипломатии MOO II.
     * <p>
     * Одна сторона называет технологию, которую хочет, и ту, которую отдаёт взамен.
     * Правило согласия — из оригинала: «компьютер всегда предлагает обмен, выгодный ему
     * самому», то есть соглашается, лишь когда получает не дешевле, чем отдаёт. Ценность
     * меряется стоимостью уровня в очках исследований — другой меры дерево не даёт.
     * <p>
     * Обмен идёт в обе стороны сразу: технологию получает и тот, кто просил, и тот, кто
     * согласился. В MOO II это разовая сделка, а не договор, поэтому в отношениях от неё
     * остаётся только выросшее доверие.
     */
    private MessageKey exchangeTech(PlayerEntity player, PlayerEntity other,
                                DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                                DiplomacyActionRequest request, Integer turn) {
        ResearchCatalog.TechnologyPlace offered = requireTech(request.offeredTech(), "diplomacy.tech.offered");
        ResearchCatalog.TechnologyPlace requested = requireTech(request.requestedTech(), "diplomacy.tech.requested");
        if (offered.option().code().equals(requested.option().code())) {
            throw new ConflictException("diplomacy.sameTech");
        }

        Set<String> ours = technologiesOf(player.getId());
        Set<String> yours = technologiesOf(other.getId());
        if (!ours.contains(offered.option().code())) {
            throw new ConflictException("diplomacy.youLackTech", offered.option().name());
        }
        if (!yours.contains(requested.option().code())) {
            throw new ConflictException("diplomacy.theyLackTech", other.getName(), requested.option().name());
        }
        if (ours.contains(requested.option().code())) {
            throw new ConflictException("diplomacy.youHaveTech", requested.option().name());
        }
        if (yours.contains(offered.option().code())) {
            throw new ConflictException("diplomacy.theyHaveTech", offered.option().name(), other.getName());
        }

        if (!agreesToExchange(player, theirs, offered, requested)) {
            loseTrust(theirs, other, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в обмене {} на {}", other.getName(),
                    player.getName(), offered.option().name(), requested.option().name());
            return new MessageKey("diplomacy.refusal.exchange");
        }

        activity.record(player.getGame().getId(), player.getId(),
                EmpireActivityService.TECH_EXCHANGE);
        activity.record(player.getGame().getId(), other.getId(),
                EmpireActivityService.TECH_EXCHANGE);
        grantTechnology(player, requested, turn);
        grantTechnology(other, offered, turn);
        reward(player, other, mine, theirs);
        log.info("Игрок {} обменял с империей {}: {} на {}", player.getName(), other.getName(),
                offered.option().name(), requested.option().name());
        return new MessageKey("diplomacy.answer.exchange",
                requested.option().name(), offered.option().name());
    }

    /**
     * Подарок деньгами — п. 15: согласия не требует, подарок не отвергают.
     * <p>
     * Доверие растёт от доли казны, а не от суммы: тысяча кредитов от нищего значит
     * больше, чем от богача. Потолок в {@link #GIFT_TRUST_MAX} не даёт купить дружбу
     * одним переводом — в MOO II подарки располагают, но политику не заменяют.
     */
    private MessageKey giftCredits(PlayerEntity player, PlayerEntity other,
                               DiplomacyRelationEntity theirs, DiplomacyActionRequest request) {
        Integer amount = request.credits();
        if (amount == null || amount <= 0) {
            throw new ConflictException("diplomacy.giftAmount");
        }
        if (player.getCredits() < amount) {
            throw new ConflictException("diplomacy.giftNoCredits", player.getCredits());
        }

        Integer before = player.getCredits();
        player.setCredits(before - amount);
        other.setCredits(other.getCredits() + amount);
        playerRepository.saveAll(List.of(player, other));

        Integer share = before <= 0
                ? GIFT_TRUST_MAX
                : Math.min(GIFT_TRUST_MAX, amount * GIFT_TRUST_MAX / before);
        gainTrust(theirs, other, share);
        log.info("Игрок {} подарил империи {} {} кредитов: доверие +{}",
                player.getName(), other.getName(), amount, share);
        return new MessageKey("diplomacy.answer.giftCredits", amount, share);
    }

    /** Подарок технологией — п. 15: то же, что и деньгами, только платят знанием. */
    private MessageKey giftTech(PlayerEntity player, PlayerEntity other,
                            DiplomacyRelationEntity theirs, DiplomacyActionRequest request,
                            Integer turn) {
        ResearchCatalog.TechnologyPlace gift = requireTech(request.offeredTech(), "diplomacy.tech.gift");
        if (!technologiesOf(player.getId()).contains(gift.option().code())) {
            throw new ConflictException("diplomacy.youLackTech", gift.option().name());
        }
        if (technologiesOf(other.getId()).contains(gift.option().code())) {
            throw new ConflictException("diplomacy.theyHaveTech", gift.option().name(), other.getName());
        }

        grantTechnology(other, gift, turn);
        gainTrust(theirs, other, GIFT_TECH_TRUST);
        log.info("Игрок {} подарил империи {} технологию {}",
                player.getName(), other.getName(), gift.option().name());
        return new MessageKey("diplomacy.answer.giftTech", gift.option().name(), GIFT_TECH_TRUST);
    }

    /**
     * Согласна ли другая сторона на обмен — п. 15.
     * <p>
     * Два условия. Сделка должна быть ей выгодна: получить не дешевле, чем отдать, — это
     * прямое правило MOO II. И разговаривать она должна быть готова: с тем, кому вовсе
     * не доверяют, знанием не делятся ни на каких условиях.
     */
    /**
     * Согласится ли сосед на такой обмен — п. 15; спрашивают до предложения.
     * <p>
     * Нужно это ИИ: он перебирает пары «что отдать за что получить» и не должен предлагать
     * заведомо невозможное — отказ стоит доверия, и империя, тыкающая наугад, растеряла бы
     * его на ровном месте. Считает тот же {@link #agreesToExchange}, которым решается и сам
     * обмен: второго свода правил торговли у ИИ нет.
     */
    public Boolean acceptsExchange(PlayerEntity asking, DiplomacyRelationEntity theirs,
                                   ResearchCatalog.TechnologyPlace offered,
                                   ResearchCatalog.TechnologyPlace requested) {
        return agreesToExchange(asking, theirs, offered, requested);
    }

    private Boolean agreesToExchange(PlayerEntity asking, DiplomacyRelationEntity theirs,
                                     ResearchCatalog.TechnologyPlace offered,
                                     ResearchCatalog.TechnologyPlace requested) {
        return theirs.getTrust() >= charmed(asking, EXCHANGE_TRUST)
                && offered.levelCost() >= requested.levelCost();
    }

    /** Технология по коду; пустой код — ошибка запроса, а не молчаливый пропуск. */
    private ResearchCatalog.TechnologyPlace requireTech(String code, String what) {
        if (code == null || code.isBlank()) {
            throw new ConflictException("diplomacy.techMissing", new MessageKey(what));
        }
        return researchCatalog.place(code);
    }

    /** Что империя уже знает — по кодам технологий. */
    private Set<String> technologiesOf(UUID playerId) {
        return playerTechnologyRepository.findAllByPlayerIdIn(List.of(playerId)).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
    }

    /**
     * Записывает полученную не исследованием технологию — п. 15.
     * <p>
     * Строка та же, что у изученного: дальше игре безразлично, добыто знание своими
     * учёными, обменом или подарком, — важно, что оно есть. Ход записывается текущий:
     * по нему видно, когда технология появилась у империи.
     */
    private void grantTechnology(PlayerEntity player, ResearchCatalog.TechnologyPlace place,
                                 Integer turn) {
        PlayerTechnologyEntity technology = new PlayerTechnologyEntity();
        technology.setPlayerId(player.getId());
        technology.setCategoryCode(place.categoryCode());
        technology.setLevelOrder(place.levelOrder());
        technology.setOptionCode(place.option().code());
        technology.setAcquiredTurn(turn);
        playerTechnologyRepository.save(technology);
    }

    /**
     * Что узнает вторая сторона — п. 15.
     * <p>
     * Отказы не рассылаются: отказала как раз она сама, и новости в этом нет. А вот
     * война, договор, разрыв и дань меняют её положение, и увидеть это она должна.
     */
    private void notifyOther(PlayerEntity player, PlayerEntity other, Integer turn,
                             DiplomacyActionRequest request, MessageKey answer) {
        if (answer != null && answer.key().startsWith("diplomacy.refusal.")) {
            return;
        }
        UUID gameId = player.getGame().getId();
        MessageKey message = switch (request.action()) {
            case DECLARE_WAR -> new MessageKey("turn.diplomacy.warDeclared", player.getName());
            case PROPOSE_PEACE -> new MessageKey("turn.diplomacy.peaceMade", player.getName());
            case PROPOSE_TREATY -> new MessageKey("turn.diplomacy.treatySigned",
                    player.getName(), requireTreaty(request));
            case BREAK_TREATY -> new MessageKey("turn.diplomacy.treatyBroken",
                    player.getName(), requireTreaty(request));
            case DEMAND_TRIBUTE -> new MessageKey("turn.diplomacy.tributePaid", player.getName());
            case EXCHANGE_TECH -> new MessageKey("turn.diplomacy.techExchanged", player.getName());
            case GIFT_CREDITS -> new MessageKey("turn.diplomacy.giftCredits", player.getName());
            case GIFT_TECH -> new MessageKey("turn.diplomacy.giftTech", player.getName());
        };
        playerEvents.record(gameId, other.getId(), turn, "DIPLOMACY", message);
    }

    /**
     * Согласна ли другая сторона на договор: у каждого свой порог доверия.
     * <p>
     * Лидер-дипломат опускает этот порог на свои очки — п. 6: в MOO II он и нужен затем,
     * чтобы договориться там, где своими силами не выходит. Служит он всегда, где бы ни
     * стоял, поэтому надбавка берётся по всей империи.
     */
    private Boolean agrees(PlayerEntity player, PlayerEntity other,
                           DiplomacyRelationEntity theirs, DiplomacyTreaty treaty) {
        Integer diplomat = leaderBonuses.of(player.getId()).empireValue("DIPLOMAT");
        return theirs.getTrust()
                >= charmed(player, treaty.getRequiredTrust()) + treatyBias(other) - diplomat;
    }

    /**
     * Насколько характер поднимает или опускает планку согласия — п. 15.
     * <p>
     * Миролюбивый «дорожит мирными отношениями» и договаривается охотнее; дипломат к тому
     * же и стремится; ксенофоб «не доверяет никому», и уговорить его труднее.
     * <i>Реконструкция:</i> величину оригинал не называет — названы только склонности.
     */
    private Integer treatyBias(PlayerEntity other) {
        if (other == null || other.getAiPersonality() == null) {
            return 0;
        }
        int bias = 0;
        if (Boolean.TRUE.equals(other.getAiPersonality().seeksPeace())) {
            bias -= TREATY_BIAS;
        }
        if (other.getAiPersonality() == AiPersonality.XENOPHOBIC) {
            bias += TREATY_BIAS;
        }
        if (other.getAiObjective() != null
                && Boolean.TRUE.equals(other.getAiObjective().favoursDiplomacy())) {
            bias -= TREATY_BIAS;
        }
        return bias;
    }

    /**
     * Платят дань от страха или по большой приязни; посередине — отказывают. Обаяние
     * просящего опускает планку приязни — п. 7.
     */
    private Boolean agreesToTribute(PlayerEntity asking, DiplomacyRelationEntity theirs) {
        return theirs.getTrust() <= TRIBUTE_FEAR_TRUST
                || theirs.getTrust() >= charmed(asking, TRIBUTE_FRIEND_TRUST);
    }

    /** Заводит отношения в обе стороны; {@code false} — империи уже знакомы. */
    private Boolean meet(PlayerEntity player, PlayerEntity other, Integer turn) {
        if (relationRepository.findByPlayerIdAndOtherPlayerId(player.getId(), other.getId()).isPresent()) {
            return Boolean.FALSE;
        }
        relationRepository.saveAll(List.of(
                relation(player.getId(), other.getId(), turn),
                relation(other.getId(), player.getId(), turn)));
        log.info("Игрок {} познакомился с империей {}", player.getName(), other.getName());
        return Boolean.TRUE;
    }

    /**
     * Галактика объявляет войну тому, кто не подчинился Высшему совету, — п. 3.
     * <p>
     * В MOO II проигравший вправе не признать избрания, и тогда все, кто голосовал за
     * избранного, объявляют отказнику войну. Это <b>не</b> обычное объявление войны, и
     * потому оно живёт здесь отдельным методом, а не зовёт {@code act}:
     * <ul>
     *   <li><b>договоры не спасают</b>: пакт о ненападении и союз рвутся приговором
     *       совета — иначе отказ был бы бесплатным для того, кто заранее подписал пакт
     *       с каждым;</li>
     *   <li><b>согласия не спрашивают</b> ни у кого: воюет галактика, а не империя.</li>
     * </ul>
     * Второго свода правил войны при этом не заводится: стойка и разрыв договоров идут
     * теми же {@code setStance} и {@code removeTreaty}, что и у всякой другой войны.
     *
     * @return сколько империй объявило войну
     */
    @Transactional
    public Integer warOnCouncilRebel(PlayerEntity rebel, List<PlayerEntity> supporters, Integer turn) {
        int declared = 0;
        for (PlayerEntity supporter : supporters) {
            if (supporter.getId().equals(rebel.getId())) {
                continue;
            }
            DiplomacyRelationEntity theirs = requireOrCreate(supporter.getId(), rebel.getId(), turn);
            DiplomacyRelationEntity mine = requireOrCreate(rebel.getId(), supporter.getId(), turn);
            if (theirs.getStance() == DiplomacyStance.WAR) {
                continue;
            }
            List.copyOf(theirs.getTreaties()).forEach(treaty -> removeTreaty(theirs, mine, treaty));
            setStance(theirs, mine, DiplomacyStance.WAR);
            // Доверие падает у ОБЕИХ сторон, и это не симметрия ради симметрии: отказника
            // бьёт объявленная ему война (как всякого, на кого напали), а сам он для
            // галактики преступник — не признал приговора совета, — поэтому и они ему
            // больше не верят. В обычной войне падает доверие только у жертвы: там
            // виноват нападающий, а здесь виноваты оба.
            loseTrust(mine, rebel, WAR_PENALTY);
            loseTrust(theirs, supporter, WAR_PENALTY);
            activity.record(supporter.getGame().getId(), supporter.getId(), EmpireActivityService.WAR);
            declared++;
            log.info("Империя {} объявила войну отказнику {}: приговор совета", supporter.getName(),
                    rebel.getName());
        }
        relationRepository.flush();
        return declared;
    }

    /** Строка отношений этой пары; знакомства могло и не быть — тогда заводится новая. */
    private DiplomacyRelationEntity requireOrCreate(UUID playerId, UUID otherPlayerId, Integer turn) {
        return relationRepository.findByPlayerIdAndOtherPlayerId(playerId, otherPlayerId)
                .orElseGet(() -> relationRepository.save(relation(playerId, otherPlayerId, turn)));
    }

    private DiplomacyRelationEntity relation(UUID playerId, UUID otherPlayerId, Integer turn) {
        DiplomacyRelationEntity relation = new DiplomacyRelationEntity();
        relation.setPlayerId(playerId);
        relation.setOtherPlayerId(otherPlayerId);
        relation.setStance(DiplomacyStance.NEUTRAL);
        relation.setTrust(INITIAL_TRUST);
        relation.setMetTurn(turn);
        return relation;
    }

    private void setStance(DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs, DiplomacyStance stance) {
        mine.setStance(stance);
        theirs.setStance(stance);
    }

    /** Договор подписывают обе стороны — набор у них общий. */
    private void addTreaty(DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs, DiplomacyTreaty treaty) {
        Set<DiplomacyTreaty> ours = new LinkedHashSet<>(mine.getTreaties());
        ours.add(treaty);
        mine.setTreaties(ours);
        theirs.setTreaties(ours);
    }

    private void removeTreaty(DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs, DiplomacyTreaty treaty) {
        Set<DiplomacyTreaty> ours = new LinkedHashSet<>(mine.getTreaties());
        ours.remove(treaty);
        mine.setTreaties(ours);
        theirs.setTreaties(ours);
    }

    /** Удавшаяся договорённость поднимает доверие обеих сторон. */
    private void reward(PlayerEntity player, PlayerEntity other,
                        DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs) {
        gainTrust(mine, player, AGREEMENT_BONUS);
        gainTrust(theirs, other, AGREEMENT_BONUS);
    }

    private DiplomacyTreaty requireTreaty(DiplomacyActionRequest request) {
        if (request.treaty() == null) {
            throw new ConflictException("diplomacy.treatyMissing");
        }
        return request.treaty();
    }

    private DiplomacyRelationEntity requireRelation(UUID playerId, UUID otherPlayerId) {
        return relationRepository.findByPlayerIdAndOtherPlayerId(playerId, otherPlayerId)
                .orElseThrow(() -> new ConflictException("diplomacy.noContact"));
    }

    /**
     * Отношения для экрана — п. 15.
     * <p>
     * <b>Доверие показывается чужое, а не своё.</b> Отношения хранятся двумя строками, и
     * все решения соседа принимает его строка: он соглашается на договор, когда доверяет
     * <i>он</i>. Показывать своё отношение к нему значило бы дать игроку число, которое
     * ничего не предсказывает: подарок поднимал бы чужое доверие, а на экране ничего не
     * менялось бы — так и было, пока подарков не появилось и проверить это было нечем.
     *
     * Своё доверие уходит в {@code yourTrust}: оно не предсказывает чужой ответ, зато
     * именно его подтачивают чужие кражи и поимки агентов (п. 13), и по нему игрок судит,
     * как сам отнесётся к соседу.
     *
     * @param theirTrust доверие соседа к игроку; {@code null} — обратной строки нет,
     *                   тогда показывается своё, чтобы поле не осталось пустым
     */
    private DiplomacyRelationDto toDto(DiplomacyRelationEntity relation, PlayerEntity other,
                                       Map<String, String> raceNames, Integer theirTrust,
                                       String answer) {
        return toDto(relation, other, raceNames, theirTrust, answer, null);
    }

    /**
     * То же, но с самим игроком: по нему считается признак «переговоры ведутся».
     * <p>
     * Отталкивающая раса ЛЮБОЙ из двух сторон отменяет переговоры (см.
     * {@code requireNotRepulsive}), поэтому одного соседа тут мало — нужен и тот, кто
     * смотрит. Без игрока признак остаётся правдой: так строка ведёт себя как прежде.
     */
    private DiplomacyRelationDto toDto(DiplomacyRelationEntity relation, PlayerEntity other,
                                       Map<String, String> raceNames, Integer theirTrust,
                                       String answer, PlayerEntity player) {
        List<DiplomacyTreaty> treaties = List.copyOf(relation.getTreaties());
        return new DiplomacyRelationDto(
                relation.getOtherPlayerId(),
                other == null ? null : other.getName(),
                other == null ? null : raceService.raceName(other, raceNames::get),
                other == null ? null : other.getColor(),
                relation.getStance().name(),
                messages.label(relation.getStance()),
                theirTrust == null ? relation.getTrust() : theirTrust,
                relation.getTrust(),
                treaties.stream().map(Enum::name).toList(),
                treaties.stream().map(messages::label).toList(),
                character(other),
                relation.getMetTurn(),
                answer,
                negotiates(player, other));
    }

    /** Ведутся ли переговоры этих двоих: отталкивающая раса любой стороны отменяет их. */
    private Boolean negotiates(PlayerEntity player, PlayerEntity other) {
        if (other == null) {
            return Boolean.TRUE;
        }
        if (player != null && Boolean.TRUE.equals(raceService.effects(player).repulsive())) {
            return Boolean.FALSE;
        }
        return !Boolean.TRUE.equals(raceService.effects(other).repulsive());
    }

    /**
     * Правитель соседа одной строкой — «Агрессивный промышленник» окна Report MOO II.
     * <p>
     * По этим двум словам игрок и судит, чего от соседа ждать: первое — как он ведёт себя
     * в дипломатии, второе — куда вкладывается. У человека их нет: за него решает человек.
     * <p>
     * Строка нужна и окну «Инфо» ({@link EmpireInfoService}) — там она стоит рядом со
     * сторонами расы соседа, — поэтому собирается здесь, в одном месте на всю игру.
     */
    public String character(PlayerEntity other) {
        if (other == null || other.getAiPersonality() == null) {
            return null;
        }
        return other.getAiObjective() == null
                ? messages.label(other.getAiPersonality())
                : messages.get("diplomacy.character", other.getAiPersonality(),
                        messages.label(other.getAiObjective()).toLowerCase());
    }

    private Integer clamp(Integer trust) {
        return Math.max(0, Math.min(100, trust));
    }

    /**
     * Прибавка доверия с поправкой на характер — п. 15.
     * <p>
     * Хорошее в дипломатии — договор, подарок, удачный обмен — каждый принимает по-своему.
     * Ксенофоб, по описанию MOO II, «вдвое слабее принимает хорошее», и это единственная
     * поправка на прибавку; у остальных характеров она полная. У человека характера нет:
     * за него решает он сам.
     *
     * @param owner чья это строка отношений — её доверие и правится
     */
    private void gainTrust(DiplomacyRelationEntity relation, PlayerEntity owner, Integer amount) {
        Integer charm = diplomacyPercentOf(relation.getOtherPlayerId());
        Integer value = scaled(scaled(amount, 100 + charm), gainPercent(owner));
        relation.setTrust(clamp(relation.getTrust() + value));
    }

    /**
     * Убавка доверия с поправкой на характер — п. 15.
     * <p>
     * Плохое — отказ, разрыв, война, дань, кража — ксенофоб и благородный принимают
     * «вдвое сильнее»: первый не доверяет никому, второй не прощает удара без повода.
     */
    private void loseTrust(DiplomacyRelationEntity relation, PlayerEntity owner, Integer amount) {
        // Обаяние смягчает удар: у MOO II «отрицательные действия приносят вдвое меньше
        // вреда», а вдвое меньше — это и есть сотня процентов обаяния в знаменателе.
        Integer charm = diplomacyPercentOf(relation.getOtherPlayerId());
        Integer value = scaled(amount * 100 / (100 + charm), lossPercent(owner));
        relation.setTrust(clamp(relation.getTrust() - value));
    }

    /**
     * Насколько империя приятна в переговорах — п. 7, п. 14: обаятельные и телепаты.
     * <p>
     * Спрашивается у самой империи, чьё дело меняет доверие: доверие держит мнение
     * <i>о ней</i>, и в строке отношений она стоит вторым игроком.
     */
    private Integer diplomacyPercentOf(UUID playerId) {
        return playerRepository.findById(playerId)
                .map(player -> raceService.effects(player).diplomacyPercent())
                .orElse(0);
    }

    /**
     * Планка согласия с поправкой на обаяние просящего — п. 7.
     * <p>
     * <b>Реконструкция.</b> В MOO II обаятельная раса получает «50% бонус на то, что
     * сторона согласится», то есть прибавку к вероятности. Здесь согласие считается не
     * броском, а порогом доверия, поэтому обаяние опускает сам порог: вдвое у
     * обаятельных, на пятую часть у телепатов.
     */
    private Integer charmed(PlayerEntity asking, Integer required) {
        return required * 100 / (100 + raceService.effects(asking).diplomacyPercent());
    }

    /** Доля от величины в процентах, но не меньше единицы: поправка не должна всё съедать. */
    private Integer scaled(Integer amount, Integer percent) {
        return amount <= 0 ? amount : Math.max(1, amount * percent / 100);
    }

    private Integer gainPercent(PlayerEntity owner) {
        return owner == null || owner.getAiPersonality() == null
                ? 100
                : owner.getAiPersonality().getGainPercent();
    }

    private Integer lossPercent(PlayerEntity owner) {
        return owner == null || owner.getAiPersonality() == null
                ? 100
                : owner.getAiPersonality().getLossPercent();
    }
}
