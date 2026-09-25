package com.moo3.server.service;

import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.AiPersonality;
import com.moo3.server.domain.enums.DiplomacyAction;
import com.moo3.server.domain.enums.DiplomacyStance;
import com.moo3.server.domain.enums.DiplomacyTreaty;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.dto.DiplomacyActionRequest;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Что империи ИИ делают в дипломатии сами — п. 15.
 * <p>
 * До этого дипломатия была улицей с односторонним движением: предлагал только игрок, а
 * соседи лишь отвечали да или нет. В MOO II наоборот — соседи приходят сами: «требования
 * иногда выдвигает союзник, но и другие империи тоже», а подарок делают, «чтобы убедить
 * более сильную империю, что мир выгоднее войны». Здесь то же самое.
 * <p>
 * <b>Решает характер правителя</b> ({@link AiPersonality}) — шесть характеров MOO II:
 * <ul>
 *   <li><b>война</b> — агрессивный «нападёт, как только окажется в выгодном положении»,
 *       беспощадный «нападает почти без повода» и лезет в драку даже слабее соперника,
 *       миролюбивый — только при подавляющем перевесе. Благородный «не нападает на тех,
 *       с кем в хороших отношениях», и щадит их при любом перевесе;</li>
 *   <li><b>мир</b> — миролюбивый «на войне быстрее просит мира»; остальные просят, когда
 *       война складывается не в их пользу;</li>
 *   <li><b>договор</b> — предлагают тем, кому доверяют; дипломат охотнее прочих;</li>
 *   <li><b>дань</b> — требуют у того, кто заметно слабее;</li>
 *   <li><b>подарок</b> — делают тому, кто заметно сильнее: покупают спокойствие.</li>
 * </ul>
 * <p>
 * <b>Одно действие за ход и не каждый ход.</b> В MOO II «империи ИИ теряют терпение и
 * какое-то время отказываются разговаривать, если досаждать им переговорами»; здесь та же
 * мера, только с другой стороны — ИИ и сам не осаждает соседей каждый ход. Жребий берётся
 * от зерна партии, хода и слота: перезагруженное сохранение должно считаться так же.
 * <p>
 * Само действие исполняет {@link DiplomacyService#act} — тот же путь, которым ходит игрок.
 * Своих правил у ИИ нет: он выбирает, что предложить, а согласие, доверие и оповещение
 * второй стороны считаются там же, где и всегда.
 * <p>
 * <b>Отступление от MOO II:</b> там предложение соседа открывает игроку окно с ответом
 * «да или нет». Здесь окна ответа ещё нет, и за игрока отвечает его же доверие к соседу
 * ({@code yourTrust}) — по тому самому правилу, по которому отвечает и сосед: решает
 * всегда тот, кому предлагают. Случившееся игрок видит в итогах хода. Точка расширения:
 * окно ответа встанет между {@link #perform} и {@link DiplomacyService#act}, отложив
 * действие до решения игрока.
 */
@Service
public class AiDiplomacyService {

    private static final Logger log = LoggerFactory.getLogger(AiDiplomacyService.class);

    /**
     * Как часто ИИ вообще заводит разговор, в процентах на ход.
     * <p>
     * <i>Реконструкция:</i> в оригинале мера названа со стороны игрока — империи «теряют
     * терпение, если досаждать им переговорами». Четверть ходов означает разговор раз в
     * четыре хода на соседа: заметно, но не превращает дипломатию в поток сообщений.
     */
    private static final int ACTION_CHANCE_PERCENT = 25;

    /** Во сколько раз надо быть сильнее, чтобы требовать дань, и слабее — чтобы дарить. */
    private static final int TRIBUTE_ADVANTAGE_PERCENT = 200;
    private static final int GIFT_WEAKNESS_PERCENT = 50;

    /** Какую часть казны ИИ отдаёт, покупая спокойствие. */
    private static final int GIFT_SHARE_PERCENT = 20;

    /** Доверие, ниже которого сосед считается врагом, и выше которого — другом. */
    private static final int HOSTILE_TRUST = 40;
    private static final int FRIENDLY_TRUST = 60;

    /**
     * Сколько ходов сосед считается новым знакомым и войны ему не объявляют —
     * реконструкция.
     * <p>
     * Десять ходов: довольно, чтобы «прилетел — и сразу война» стало невозможным, и мало
     * на фоне партии в полторы сотни ходов, чтобы войны от этого перевелись. Чисел MOO II
     * не публиковала, но известно, что сосед сперва присматривается.
     */
    private static final int ACQUAINTANCE_BEFORE_WAR = 10;

    /**
     * Насколько охотнее нападают на отталкивающего соседа — п. 7.
     * <p>
     * <b>Вторая половина стороны, которой не было в игре вовсе.</b> Карточка обещает:
     * «с расой не договариваются: чужие империи ВРАЖДЕБНЫ», — и в оригинале это «their
     * attitude toward you is significantly worse», то есть не только отказ от переговоров.
     * Сделан был один отказ, и он ничего не стоил: измерено пробой на трёх партиях по 150
     * ходов (четверо отталкивающих против четверых обычных, прочие стороны одинаковы) —
     * договоров у отталкивающих ровно ноль против 1,83 у соседей, а колоний ВПОЛТОРА
     * БОЛЬШЕ (3,42 против 1,92) и захватов вдвое. Сторона за −6 очков мерилась силой
     * +3,03: запрет переговоров оказался не платой, а освобождением — договор в этой игре
     * запирает войну, и не иметь его выгодно.
     * <p>
     * Двадцать пять пунктов — это ступень между соседними характерами таблицы
     * ({@link AiPersonality}: 50, 90, 110, 140, 200): отталкивающий сосед сдвигает решение
     * ровно на характер в сторону войны, а не превращает миролюбивого в беспощадного.
     */
    private static final int REPULSIVE_WAR_DISCOUNT = 25;

    /**
     * Ниже перевеса самого беспощадного характера скидка не опускает — п. 15.
     * <p>
     * Иначе отталкивающий сосед делал бы миролюбивого решительнее беспощадного, а характер
     * правителя должен оставаться главной мерой: враждебность сдвигает решение, а не
     * подменяет собой нрав.
     */
    private static final int RUTHLESS_WAR_ADVANTAGE = AiPersonality.RUTHLESS.getWarAdvantagePercent();

    private final DiplomacyRelationRepository relationRepository;
    private final DiplomacyService diplomacyService;
    private final RaceService raceService;
    private final EmpireInfoService empireInfoService;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final ResearchCatalog researchCatalog;

    public AiDiplomacyService(DiplomacyRelationRepository relationRepository,
                              DiplomacyService diplomacyService,
                              RaceService raceService,
                              EmpireInfoService empireInfoService,
                              PlayerTechnologyRepository playerTechnologyRepository,
                              ResearchCatalog researchCatalog) {
        this.relationRepository = relationRepository;
        this.diplomacyService = diplomacyService;
        this.raceService = raceService;
        this.empireInfoService = empireInfoService;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.researchCatalog = researchCatalog;
    }

    /** Ход дипломатии ИИ: каждая империя ИИ может завести один разговор — п. 15. */
    @Transactional
    public void act(TurnContext context) {
        Map<UUID, PlayerEntity> byId = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        // Мощь считается той же мерой, что рисует график окна «Инфо» (п. 11.1): игрок
        // видит на экране ровно то, по чему судит сосед.
        Map<UUID, Integer> might = empireInfoService.mightByPlayer(context);
        // Отношения всех участников — одной выборкой: запрос на каждую империю ИИ стоил
        // семи походов в базу за ход, и каждый сбрасывал туда весь посчитанный ход.
        Map<UUID, List<DiplomacyRelationEntity>> relationsByPlayer = relationRepository
                .findAllByPlayerIdIn(context.players().stream().map(PlayerEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(DiplomacyRelationEntity::getPlayerId));
        // Изученное — тоже ОДНОЙ выборкой на партию, и по той же причине, что отношения:
        // запрос на игрока изнутри посчитанного хода сбрасывает в базу всё, что ход успел
        // изменить. Обмен спрашивает изученное у обеих сторон каждого разговора.
        Map<UUID, Set<String>> technologies = playerTechnologyRepository
                .findAllByPlayerIdIn(context.players().stream().map(PlayerEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(PlayerTechnologyEntity::getPlayerId,
                        Collectors.mapping(PlayerTechnologyEntity::getOptionCode,
                                Collectors.toSet())));
        Map<String, ResearchCatalog.TechnologyPlace> places = researchCatalog.places();

        for (PlayerEntity ai : context.players()) {
            if (ai.getPlayerType() != PlayerType.AI || ai.getAiPersonality() == null) {
                continue;
            }
            // Отталкивающая раса переговоров не ведёт вовсе — только война и мир (п. 7).
            Boolean repulsive = raceService.effects(ai).repulsive();

            Random random = new Random(
                    context.game().getSeed() * 37L + context.turn() * 41L + ai.getSlot());
            if (random.nextInt(100) >= ACTION_CHANCE_PERCENT) {
                continue;
            }

            // ПОРЯДОК ЗДЕСЬ — ЧАСТЬ ПРАВИЛ: собеседник выбирается ИНДЕКСОМ в этом списке
            // (`relations.get(random.nextInt(...))`), а выборка отношений отдаёт строки как
            // удобно базе. Значит без явного ключа ИИ в двух прогонах одной партии говорит
            // с РАЗНЫМИ соседями — и дальше расходится всё. Поймано сверкой по колониям:
            // на 104-м ходу у двух империй появлялись лишние технологии (neural-scanner,
            // heavy-armor) — следы обмена, состоявшегося только в одном прогоне.
            //
            // Ключ — МЕСТО игрока: оно задано партией и от идентификаторов строк не зависит
            // (см. GameOrder о том, почему ни id, ни строковые колонки ключом не годятся).
            List<DiplomacyRelationEntity> relations = new ArrayList<>(
                    relationsByPlayer.getOrDefault(ai.getId(), List.of()));
            relations.sort(Comparator.comparing(one -> {
                PlayerEntity other = byId.get(one.getOtherPlayerId());
                return other == null ? Integer.MAX_VALUE : other.getSlot();
            }));
            if (relations.isEmpty()) {
                continue;
            }
            DiplomacyRelationEntity mine = relations.get(random.nextInt(relations.size()));
            PlayerEntity other = byId.get(mine.getOtherPlayerId());
            if (other == null) {
                continue;
            }

            // Отталкивающей может оказаться и вторая сторона: переговоров не будет и тогда.
            // А вот ВРАЖДЕБНОСТЬ несимметрична — она направлена на отталкивающего соседа, и
            // потому его признак берётся отдельно от общего запрета переговоров.
            Boolean theirsRepulsive = raceService.effects(other).repulsive();
            Boolean noTalks = Boolean.TRUE.equals(repulsive)
                    || Boolean.TRUE.equals(theirsRepulsive);

            // Решает всегда тот, КОМУ предлагают, поэтому обмену нужна встречная строка —
            // мнение соседа о нас (п. 15). Берётся она из той же выборки отношений.
            DiplomacyRelationEntity theirs = relationsByPlayer
                    .getOrDefault(other.getId(), List.of()).stream()
                    .filter(one -> one.getOtherPlayerId().equals(ai.getId()))
                    .findFirst()
                    .orElse(null);
            Exchange trade = exchange(ai, theirs, places,
                    technologies.getOrDefault(ai.getId(), Set.of()),
                    technologies.getOrDefault(other.getId(), Set.of()));

            DiplomacyAction action = decide(ai, other, mine, noTalks, theirsRepulsive, might,
                    random, context.turn(), trade);
            if (action != null) {
                perform(context, ai, other, mine, action, trade);
            }
        }
    }

    /**
     * Что ИИ предпримет с этим соседом — п. 15; {@code null} — на этот раз ничего.
     * <p>
     * Порядок проверок — порядок важности: сперва война и мир, они меняют положение
     * сильнее всего, потом договор, потом дань и подарок. Первое подошедшее и делается:
     * за один ход ИИ говорит об одном.
     */
    private DiplomacyAction decide(PlayerEntity ai, PlayerEntity other,
                                   DiplomacyRelationEntity mine, Boolean noTalks,
                                   Boolean theirsRepulsive,
                                   Map<UUID, Integer> might, Random random, Integer turn,
                                   Exchange trade) {
        AiPersonality character = ai.getAiPersonality();
        Integer trust = mine.getTrust();

        if (mine.getStance() == DiplomacyStance.WAR) {
            // Мира просят миролюбивые и проигрывающие: незачем длить войну, которую не тянешь.
            return Boolean.TRUE.equals(character.seeksPeace()) || losing(might, ai, other)
                    ? DiplomacyAction.PROPOSE_PEACE
                    : null;
        }

        if (readyForWar(ai, other, mine, character, theirsRepulsive, might, random, turn)) {
            // Пакт о ненападении и союз войну запрещают — их сперва РВУТ. До этой ветки ИИ
            // не рвал договоров вовсе: подписав однажды, он держал мир до конца партии, и
            // галактика запиралась наглухо — та же поломка вечного мира, только приходила
            // она не с первого хода, а с третьего десятка. Разрыв стоит доверия (это
            // считает DiplomacyService), и следующим разговором объявляется война.
            DiplomacyTreaty binding = mine.getTreaties().stream()
                    .filter(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))
                    .findFirst()
                    .orElse(null);
            return binding == null ? DiplomacyAction.DECLARE_WAR : DiplomacyAction.BREAK_TREATY;
        }
        if (Boolean.TRUE.equals(noTalks)) {
            // Дальше идут договоры, дань и подарки — с отталкивающей расой их не бывает.
            return null;
        }
        // Договор предлагается только ДРУГУ, а не всякому знакомому. Раньше он шёл, «как
        // только доверия хватает хоть на какой-то», и это запирало галактику в вечный мир:
        // знакомство начинается с половины шкалы, торговый договор стоит меньше половины,
        // поэтому договор подписывался на первом же контакте — а договор запрещает войну, и
        // ИИ его не рвёт никогда.
        //
        // Цена этому измерена: за 150 ходов восьми империй — по 4-10 договоров на каждую и
        // ОДНА воюющая империя из восьми. Ни боёв, ни десанта: прибор балансировки мерил
        // нулём не слабые стороны, а игру, в которой никто не воюет. Порог дружбы — та же
        // отметка, по которой благородный характер щадит друга: с кем дружишь, с тем и
        // договор, а со всеми подряд — это не дипломатия, а формальность знакомства.
        // Порог у КАЖДОГО договора свой (торговый 40, исследовательский 50, ненападение 55,
        // союз 75), и bestTreaty его соблюдает. Общий порог дружбы поверх этого был лишним
        // и оказался хуже той поломки, которую чинил: под него попали торговый и
        // исследовательский договоры, которые войну не запрещают вовсе. Измерено — за 150
        // ходов восьми империй НИ ОДНОГО договора: доверие начинается с половины шкалы,
        // порог дружбы выше неё, а доверие растёт только от согласия на предложение,
        // которого при таком пороге никто не делает. Замок замкнулся сам на себя, а прибор
        // балансировки объявил «отталкивающих» за −6 очков ВЫГОДНОЙ ПОКУПКОЙ (+3,90):
        // сторона, запрещающая переговоры, не отнимала ничего, потому что переговоров и так
        // не было. Теперь отметка дружбы стоит там, где ей место, — на договорах, которые
        // запрещают войну.
        if (bestTreaty(mine) != null) {
            return DiplomacyAction.PROPOSE_TREATY;
        }
        // ОБМЕН ТЕХНОЛОГИЯМИ — п. 15. Стоит он здесь, между договором и данью, потому что
        // это мирное действие, усиливающее ОБОИХ: договор весомее (он меняет саму
        // возможность войны), а дань и подарок — остаток разговора.
        //
        // Зачем это вообще заведено: обмен жил в игре только для игрока, а ИИ не выдавал
        // его НИ РАЗУ — шестой случай механики, к которой у ИИ нет пути. Цену этому платила
        // неизобретательность: в MOO II раса, которой уровень выдаёт случайную технологию,
        // выменивает пропущенное у соседей, и другого пути у неё нет. У нас этот путь был
        // закрыт, и сторона за −1 очко мерилась силой −23,6 — крупнейшая ошибка таблицы.
        // Завод и лаборатория лежат на уровнях из трёх вариантов: обычная раса берёт
        // рекомендованное всегда, неизобретательная — одно из трёх, а уровень не
        // переучивается никогда.
        if (trade != null) {
            return DiplomacyAction.EXCHANGE_TECH;
        }
        if (trust < HOSTILE_TRUST && stronger(might, ai, other, TRIBUTE_ADVANTAGE_PERCENT)) {
            return DiplomacyAction.DEMAND_TRIBUTE;
        }
        if (weaker(might, ai, other) && ai.getCredits() > 0) {
            return DiplomacyAction.GIFT_CREDITS;
        }
        return null;
    }

    /**
     * Пора ли нападать — п. 15.
     * <p>
     * Три условия. Договор запрещает войну, пока не разорван, — его ИИ не рвёт: предателем
     * он становится только по решению игрока. Благородный не бьёт того, с кем в хороших
     * отношениях. И главное — перевес: у каждого характера свой порог, от беспощадного,
     * который лезет и слабее, до миролюбивого, которому нужно тройное превосходство.
     * <p>
     * Непредсказуемый вдобавок бросает монету: «в один год миролюбив, в другой пойдёт
     * войной по любому поводу» — иначе он ничем не отличался бы от прочих.
     */
    private Boolean readyForWar(PlayerEntity ai, PlayerEntity other,
                                DiplomacyRelationEntity mine, AiPersonality character,
                                Boolean theirsRepulsive,
                                Map<UUID, Integer> might, Random random, Integer turn) {
        // Договор здесь НЕ проверяется вовсе: торговый войне не мешает, а пакт и союз
        // сперва разрывают — это решает ветка выше. Прежде тут стояло «есть хоть какой-то
        // договор — не воюем», и после раскупорки дипломатии (журнал, п. 3.38) торговля с
        // соседом запирала войну наглухо.
        // ПРИЛЁТ ЧУЖОГО ФЛОТА — НЕ ПОВОД К ВОЙНЕ. Знакомство начиналось и кончалось одним
        // ходом: корабль игрока входил в систему соседа, тем самым знакомя империи, а
        // следующая же фаза дипломатии видела нового соседа без договоров, с доверием в
        // половину шкалы, и при перевесе объявляла войну. Со стороны это выглядело так,
        // будто ИИ воюет за один факт появления гостя, — а он просто считал знакомого
        // давним. Теперь у знакомства есть срок: пока он не вышел, воевать не с кем, —
        // и это правило оригинала, где сосед сперва присматривается.
        if (turn - mine.getMetTurn() < ACQUAINTANCE_BEFORE_WAR) {
            return Boolean.FALSE;
        }
        // Отталкивающего не щадит и благородный: дружбы с тем, с кем не договариваются, не
        // бывает — трудно звать другом того, кто не разговаривает.
        if (Boolean.TRUE.equals(character.sparesFriends()) && mine.getTrust() >= FRIENDLY_TRUST
                && !Boolean.TRUE.equals(theirsRepulsive)) {
            return Boolean.FALSE;
        }
        if (character == AiPersonality.ERRATIC && random.nextBoolean()) {
            return Boolean.FALSE;
        }
        // И нападают на него охотнее — вторая половина стороны (см. REPULSIVE_WAR_DISCOUNT).
        Integer advantage = character.getWarAdvantagePercent();
        if (Boolean.TRUE.equals(theirsRepulsive)) {
            advantage = Math.max(RUTHLESS_WAR_ADVANTAGE, advantage - REPULSIVE_WAR_DISCOUNT);
        }
        return stronger(might, ai, other, advantage);
    }

    /**
     * Сильнее ли ИИ соседа во столько-то процентов от его мощи.
     * <p>
     * Мощь — сводная величина {@link EmpireMightRules}: флот, население, выработка и
     * изученное. По одному флоту сравнивать нельзя — кораблей империи ИИ пока не строят,
     * и всякая война оказалась бы навсегда невозможной: ноль не больше нуля.
     */
    private Boolean stronger(Map<UUID, Integer> might, PlayerEntity ai, PlayerEntity other,
                             Integer percent) {
        Integer theirs = might.getOrDefault(other.getId(), 0);
        // У соседа без единой колонии и корабля перевес есть у любого, у кого хоть что-то есть.
        if (theirs <= 0) {
            return might.getOrDefault(ai.getId(), 0) > 0;
        }
        return might.getOrDefault(ai.getId(), 0) * 100 >= theirs * percent;
    }

    /** Заметно ли ИИ слабее соседа — тогда он и покупает спокойствие подарком. */
    private Boolean weaker(Map<UUID, Integer> might, PlayerEntity ai, PlayerEntity other) {
        Integer theirs = might.getOrDefault(other.getId(), 0);
        return theirs > 0
                && might.getOrDefault(ai.getId(), 0) * 100 <= theirs * GIFT_WEAKNESS_PERCENT;
    }

    /** Проигрывает ли ИИ эту войну: сосед вдвое сильнее. */
    private Boolean losing(Map<UUID, Integer> might, PlayerEntity ai, PlayerEntity other) {
        return weaker(might, ai, other);
    }

    /**
     * Исполняет решение тем же путём, которым ходит игрок, — {@link DiplomacyService#act}.
     * <p>
     * Отказ соседа это обычный ответ, а не ошибка: ИИ предложил, ему не согласились, доверие
     * подвинулось. А вот конфликт правил (договор запрещает войну, дарить нечего) означает,
     * что решение было невозможным, — такой ход просто пропускается: ронять из-за него
     * пересчёт всей галактики нельзя.
     */
    private void perform(TurnContext context, PlayerEntity ai, PlayerEntity other,
                         DiplomacyRelationEntity mine, DiplomacyAction action,
                         Exchange trade) {
        DiplomacyActionRequest request = new DiplomacyActionRequest(
                ai.getAccessToken(),
                other.getId(),
                action,
                action == DiplomacyAction.PROPOSE_TREATY ? bestTreaty(mine)
                        : action == DiplomacyAction.BREAK_TREATY ? binding(mine) : null,
                action == DiplomacyAction.EXCHANGE_TECH ? trade.offered() : null,
                action == DiplomacyAction.EXCHANGE_TECH ? trade.requested() : null,
                action == DiplomacyAction.GIFT_CREDITS ? giftSize(ai) : null);
        try {
            diplomacyService.act(ai, request, context.turn());
            log.debug("ИИ {} ({}) обратился к {}: {}", ai.getName(),
                    ai.getAiPersonality().getLabel(), other.getName(), action);
        } catch (ConflictException conflict) {
            log.debug("ИИ {} не смог {} с {}: {}", ai.getName(), action, other.getName(),
                    conflict.getMessage());
        }
    }

    /** Сделка обмена: что отдаём и что просим взамен — коды технологий, п. 15. */
    private record Exchange(String offered, String requested) {
    }

    /**
     * Что выменять у соседа — п. 15; {@code null} — меняться нечем или не на что.
     * <p>
     * <b>Правило торга списано с человека:</b> просим самое дорогое, что у соседа есть, а у
     * нас нет, и платим за него САМЫМ ДЕШЁВЫМ из того, что сосед возьмёт. Согласие считает
     * сам {@link DiplomacyService#acceptsExchange} — доверие соседа и правило оригинала
     * «отдать не дешевле, чем получить»; второго свода правил торговли у ИИ нет, и
     * предлагать заведомо невозможное он не должен: отказ стоит доверия.
     * <p>
     * <b>Порядок перебора задан явно</b> — по цене уровня, а при равной цене по коду.
     * Изученное приходит множеством, а у множества порядка нет: без явной сортировки та же
     * партия на том же зерне выменивала бы то одну технологию, то другую, и парные прогоны
     * балансировки сравнивали бы не расы, а разную удачу.
     */
    private Exchange exchange(PlayerEntity ai, DiplomacyRelationEntity theirs,
                              Map<String, ResearchCatalog.TechnologyPlace> places,
                              Set<String> ours, Set<String> yours) {
        if (theirs == null || ours.isEmpty() || yours.isEmpty()) {
            return null;
        }
        Comparator<ResearchCatalog.TechnologyPlace> cheapestFirst =
                Comparator.<ResearchCatalog.TechnologyPlace, Integer>comparing(
                                ResearchCatalog.TechnologyPlace::levelCost)
                        .thenComparing(place -> place.option().code());
        List<ResearchCatalog.TechnologyPlace> wanted = yours.stream()
                .filter(code -> !ours.contains(code))
                .map(places::get)
                .filter(place -> place != null)
                .sorted(cheapestFirst.reversed())
                .toList();
        List<ResearchCatalog.TechnologyPlace> payment = ours.stream()
                .filter(code -> !yours.contains(code))
                .map(places::get)
                .filter(place -> place != null)
                .sorted(cheapestFirst)
                .toList();
        for (ResearchCatalog.TechnologyPlace ask : wanted) {
            for (ResearchCatalog.TechnologyPlace give : payment) {
                if (Boolean.TRUE.equals(diplomacyService.acceptsExchange(ai, theirs, give, ask))) {
                    return new Exchange(give.option().code(), ask.option().code());
                }
            }
        }
        return null;
    }

    /**
     * Какой договор предложить: самый весомый из тех, что сосед потянет по доверию;
     * {@code null} — предлагать нечего (всё подписано или доверия мало даже на торговлю).
     * <p>
     * Договоры перечислены в {@link DiplomacyTreaty} по возрастанию требуемого доверия,
     * поэтому «самый весомый» — последний подходящий. Предлагать союз тому, кто едва
     * терпит, значит получить отказ и уронить доверие ещё ниже.
     */
    private DiplomacyTreaty bestTreaty(DiplomacyRelationEntity mine) {
        DiplomacyTreaty best = null;
        for (DiplomacyTreaty treaty : DiplomacyTreaty.values()) {
            if (mine.getTreaties().contains(treaty) || mine.getTrust() < treaty.getRequiredTrust()) {
                continue;
            }
            // Договор, запрещающий войну, подписывают только с ДРУГОМ — та же отметка, по
            // которой благородный характер щадит друга. Это и было починкой вечного мира:
            // ИИ такой договор не рвёт никогда, и подписанный при первом знакомстве он
            // запирал галактику. Экономические договоры под это правило не подпадают.
            if (Boolean.TRUE.equals(treaty.forbidsWar()) && mine.getTrust() < FRIENDLY_TRUST) {
                continue;
            }
            best = treaty;
        }
        return best;
    }

    /** Какой договор держит ИИ от войны: его и рвут — п. 15. */
    private DiplomacyTreaty binding(DiplomacyRelationEntity mine) {
        return mine.getTreaties().stream()
                .filter(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))
                .findFirst()
                .orElse(null);
    }

    /** Сколько ИИ отдаёт, покупая спокойствие: пятая часть казны, но хотя бы кредит. */
    private Integer giftSize(PlayerEntity ai) {
        return Math.max(1, ai.getCredits() * GIFT_SHARE_PERCENT / 100);
    }
}
