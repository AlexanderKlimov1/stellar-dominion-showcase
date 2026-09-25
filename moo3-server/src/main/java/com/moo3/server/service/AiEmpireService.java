package com.moo3.server.service;

import com.moo3.server.domain.Building;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.ColonyProject;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.enums.BuildingEffectType;
import com.moo3.server.domain.enums.GroundCombatTech;
import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.entity.FleetEncounterEntity;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.FleetShipEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PlayerLeaderEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.entity.SpyEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.AiObjective;
import com.moo3.server.domain.enums.AiPersonality;
import com.moo3.server.domain.enums.DiplomacyStance;
import com.moo3.server.domain.enums.DiplomacyTreaty;
import com.moo3.server.domain.enums.EncounterDecision;
import com.moo3.server.domain.enums.EncounterState;
import com.moo3.server.domain.enums.LeaderKind;
import com.moo3.server.domain.enums.LeaderState;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.enums.ShipRole;
import com.moo3.server.domain.enums.SpyMission;
import com.moo3.server.dto.AssignSpyRequest;
import com.moo3.server.dto.ColonyProjectDto;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.FleetShipRepository;
import com.moo3.server.repository.SpyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Империя ИИ играет сама — п. 15.
 * <p>
 * До сих пор сосед только исследовал и переписывался: колонии его строили товары, флот
 * не появлялся вовсе, а галактика из восьми таких империй не могла кончиться ничем.
 * Здесь он делает всё остальное, что делает игрок: <b>распоряжается стройкой колоний,
 * расселяется, шлёт флоты, высаживает десант и решает встречи флотов</b>.
 * <p>
 * <b>Чем он руководствуется.</b> Тем же, чем и в дипломатии: характером правителя
 * ({@link AiPersonality}) и его устремлением ({@link AiObjective}). Промышленник строит
 * заводы, учёный — лаборатории, завоеватель — корабли; беспощадный шлёт десант, едва
 * появится перевес, миролюбивый не шлёт вовсе. Никакого второго набора характеров у
 * колоний нет: сосед в MOO II один и тот же и в переговорах, и на карте.
 * <p>
 * <b>Что он знает.</b> Карту целиком — как и ИИ оригинала, который разведкой не занят.
 * Поэтому цели для расселения и десанта выбираются по настоящему состоянию галактики, а
 * не по разведанному; разведка остаётся делом игрока (п. 15).
 * <p>
 * <b>Как считается.</b> Фаза идёт внутри посчитанного хода, и каждый запрос в базу здесь
 * сбрасывает туда всё, что ход успел изменить. Поэтому всё, что можно, берётся из
 * {@link TurnContext} — галактика, колонии, изученное, — а флоты, их состав, проекты и
 * отношения читаются <b>одной выборкой на партию</b>, а не на игрока.
 */
@Service
public class AiEmpireService {

    private static final Logger log = LoggerFactory.getLogger(AiEmpireService.class);

    /**
     * Порядок стройки колонии ИИ — реконструкция.
     * <p>
     * Точного плана застройки MOO II не публиковала, поэтому взят порядок, которым играет
     * человек: сперва то, что окупается быстрее всего (завод и лаборатория стоят по 60 и
     * дают прибавку сразу), потом еда и деньги, и только затем дорогие здания. Список —
     * данные, а не ветки в коде: новое здание встаёт сюда строкой.
     */
    private static final List<String> DEVELOPMENT = List.of(
            "automated-factory",
            "research-laboratory",
            "hydroponic-farm",
            "space-port",
            "marine-barracks",
            "biospheres",
            "robo-miners",
            "supercomputer",
            "soil-enrichment",
            "cloning-center",
            "stock-exchange",
            "subterranean-farms",
            "galactic-cybernet",
            "astro-university",
            "deep-core-mine",
            "autolab",
            "armor-barracks",
            "weather-controller",
            // Оборона колонии в мирное время идёт последней: она ничего не производит.
            // Звёздная база при этом нужна не только обороне — без неё колония не
            // поднимает корпуса крупнее эсминца (п. 8), и потому стоит первой.
            "star-base",
            "battle-station",
            "missile-base",
            "ground-batteries",
            "fighter-garrison",
            "star-fortress",
            "planetary-flux-shield",
            "artemis-system-net",
            "planetary-barrier-shield");

    /**
     * Оборона колонии, которую воюющая империя строит раньше кораблей, — п. 8, п. 11, п. 12.
     * <p>
     * Порядок здесь и есть порядок предпочтения: сперва казармы (десант приходит чаще, чем
     * флот), потом орбита от базы к крепости, потом наземные батареи. Платформа обороны
     * бьётся тем, что изучено, и переделывать её не нужно — за это как раз и стоит платить
     * раньше нового корабля: корабль уйдёт, а станция останется.
     */
    private static final List<String> DEFENCE = List.of(
            "marine-barracks", "star-base", "battle-station", "star-fortress",
            "missile-base", "ground-batteries", "fighter-garrison",
            // Щиты и мины — последними: они дороже всего и приходят поздними технологиями.
            // Барьер при этом стоит того: пока он цел, колонию не взять десантом вовсе.
            "planetary-flux-shield", "artemis-system-net", "planetary-barrier-shield");

    /**
     * Казармы — п. 12: то, чем колония защищается от десанта.
     * <p>
     * Стоят они шестьдесят единиц против нескольких сотен у боевого корабля, а спасают от
     * того, от чего корабль не спасает вовсе: флот противника можно обойти, колонию без
     * казарм берут первой же высадкой. Поэтому воюющая колония строит их РАНЬШЕ кораблей
     * и раньше расселения, а мирная — в общем порядке развития, между космопортом и
     * биосферами.
     * <p>
     * Место в порядке выбрано замером, а не на глаз: пока казармы стояли только в списке
     * развития, за 150 ходов их получали ровно восемь колоний из сорока — то есть одни
     * родные миры, которым они и так даны с первого хода.
     */
    private static final String MARINE_BARRACKS = "marine-barracks";

    /** Что строит колония голодающей империи в первую очередь. */
    private static final List<String> FOOD = List.of(
            "hydroponic-farm", "soil-enrichment", "subterranean-farms", "weather-controller");

    /**
     * С чего начинается любая колония — до всякого расселения.
     * <p>
     * Автоматический завод стоит 60 и удваивает выработку рабочего: колония окупает его
     * ходов за шесть, а колониальный корабль стоит 500. Строить корабль раньше завода
     * значит строить его втрое дольше — эту ошибку первый прогон и показал: империи
     * расселялись так медленно, что за две сотни ходов ни разу не встретились.
     * <p>
     * <b>Лаборатория стоит здесь по той же причине и попала сюда позже</b> (журнал, п. 3.73).
     * Она тоже стоит 60 и тоже удваивает работника — только учёного. Пока она лежала среди
     * ДВАДЦАТИ СЕМИ зданий развития, до неё не доходила очередь НИ РАЗУ: расселение всегда
     * находится раньше. Летопись сильнейшей империи это и показала — технологию она изучила
     * на 98-м ходу, а здание не построила до конца партии, и наука её за полтораста ходов
     * выросла с шести очков до двенадцати.
     */
    private static final List<String> FOUNDATION = List.of(
            "automated-factory", "research-laboratory");

    /**
     * За сколько ходов здание обязано вернуть свою цену, чтобы строить его РАНЬШЕ
     * расселения, — п. 10, п. 15 (журнал, п. 3.94).
     * <p>
     * <b>Зачем правило вместо списка.</b> Раньше выше расселения стояли ровно два здания
     * ({@link #FOUNDATION}), а остальные двадцать пять ждали, пока селиться станет некуда.
     * Измерено живой партией (HUGE, восемь империй, 200 ходов): 84 колонии и 86 зданий на
     * всех, то есть ОДНО здание на колонию, и 61 % колоний без единой постройки вовсе.
     * Суперкомпьютер знали три империи из восьми и не построили НИ ОДНОГО — очередь до него
     * не доходила никогда. Для прибора балансировки это значит, что двадцать пять зданий из
     * двадцати семи и вся отдача науки сверх корабельных деталей были мертвы: механика, к
     * которой у ИИ нет пути, ничего о себе не говорит.
     * <p>
     * <b>Откуда двадцать ходов.</b> Это не подгонка, а граница размена с расселением:
     * колониальный корабль стоит 500 единиц и приносит колонию с ОДНИМ жителем, которая
     * первые десятки ходов не даёт почти ничего. Здание, возвращающее свою цену быстрее
     * двадцати ходов, выгоднее этого корабля при любом разумном счёте.
     */
    private static final Integer PAYBACK_TURNS = 20;

    /**
     * А это порог, с которым здание идёт ВПЕРЁД расселения, — п. 10, п. 15.
     * <p>
     * Порогов два, и вот почему. Отдача считается по колонии, и у дешёвого здания она велика
     * даже на пустом месте: суперкомпьютер даёт десять очков науки просто фактом
     * существования, то есть окупается за пятнадцать ходов и при нуле учёных. Пропусти такие
     * вперёд расселения — и молодая колония, дающая две единицы производства в ход, засядет
     * строить его семьдесят пять ходов вместо колониальной базы. Это не бережливость, а
     * паралич — та самая ошибка в другую сторону, которой опасна всякая починка ИИ.
     * <p>
     * Поэтому выше расселения идёт только бесспорное — то, что возвращает цену за восемь
     * ходов (завод на пяти рабочих — шесть, робошахты на пяти — семь). Всё прочее ждёт своей
     * очереди НИЖЕ расселения, но выше общего списка развития: очередь до него теперь
     * доходит, а порядок внутри задаёт не список, а сама окупаемость.
     */
    private static final Integer FAST_PAYBACK_TURNS = 8;

    /**
     * Дороже этого хозяйственное здание не выкупается на всю казну — п. 10.
     * <p>
     * Прежнее правило «хозяйство выкупается на всю казну» опиралось на то, что в списке два
     * здания по шестьдесят единиц и дороже 240 кредитов такая покупка не бывает. Правило
     * окупаемости впускает туда здания подороже (глубинная шахта — 250 единиц, то есть до
     * тысячи кредитов), и оставлять им всю казну уже опасно: содержание платится каждый ход.
     * Поэтому на всю казну берутся только дешёвые, остальные — по общему правилу половины.
     */
    private static final Integer FULL_TREASURY_COST = 150;

    /**
     * Во сколько ходов содержания должен укладываться запас, прежде чем закладывать новое, —
     * п. 10, п. 15 (журнал, п. 3.96).
     * <p>
     * <b>Чего не было.</b> Решение строить не смотрело на деньги ВООБЩЕ: ни на казну, ни на
     * доход, ни на содержание того, что уже стоит. Реплей партии показал, чем это кончается:
     * сильнейшая сборка круга 5 ушла в долг на 272-м ходу и закончила партию с −56 690
     * кредитов, продолжая строить. На 62 колонии у неё стояло 42 звёздные базы, 41 ангар
     * истребителей, 41 наземная батарея и 42 казармы — содержания больше, чем вся выручка.
     * <p>
     * <b>Правило.</b> Новое здание закладывается, только если ДОХОД империи покрывает его
     * содержание, а в казне лежит запас на десять ходов этого содержания. Доход берётся
     * тем же {@link ColonyService#income}, каким его считает производство, — второго счёта
     * денег в игре нет.
     * <p>
     * Десять ходов — реконструкция: столько строится среднее здание, то есть это ровно тот
     * срок, на который империя связывает себя решением. Порог нарочно мягкий: он отсекает
     * не «дорогие» здания, а те, что империи сейчас не по карману.
     */
    private static final Integer UPKEEP_RESERVE_TURNS = 10;

    /**
     * Сколько парсеков считается «рядом» при оценке угрозы — п. 15 (журнал, п. 3.94).
     * <p>
     * Реконструкция: это два-три хода хода среднего флота, то есть расстояние, с которого
     * сосед успевает дойти раньше, чем колония достроит оборону. Дальше этого строить
     * оборону в мирное время значит строить её везде и всегда — а это уже не осторожность, а
     * паралич: в MOO II оборону ставят на границе, а не по всей империи.
     */
    private static final Integer THREAT_PARSECS = 12;

    /**
     * С какой доли своей мощи сосед считается опасным — п. 15.
     * <p>
     * Восемьдесят процентов: заметно слабее — не угроза, вровень и сильнее — угроза. Мера
     * та же, по которой ИИ решает, объявлять ли войну ({@link EmpireMightRules}), — второго
     * взгляда на «кто сильнее» в игре нет.
     */
    private static final Integer THREAT_MIGHT_PERCENT = 80;

    /**
     * Что строит колония на границе, пока война ещё не объявлена, — п. 8, п. 11, п. 12.
     * <p>
     * Короче военного списка нарочно: казармы от десанта, звёздная база от флота, ракетная
     * база вдогонку. Всё прочее (крепости, щиты, мины) — дело военного времени: в мирное
     * оно стоит дороже, чем угроза, которую отводит.
     */
    private static final List<String> BORDER_DEFENCE =
            List.of("marine-barracks", "star-base", "missile-base");

    /**
     * Технологии хозяйства, которых империя ИИ хочет наравне с расселением — п. 15.
     * <p>
     * <b>Чего не хватало.</b> Список нужд науки просил ровно три вещи: колониальный корабль,
     * заставу и (на войне) транспорт. Всё остальное отдавалось вкусу правителя, а хозяйство
     * ни одному устремлению не любимо целиком, — и завод с лабораторией изучались только
     * по счастливой случайности. Измерено разбором живой партии (журнал, п. 3.67): за 150
     * ходов восемь империй построили ЧЕТЫРНАДЦАТЬ зданий на тридцать девять колоний, из них
     * хозяйственных ДЕСЯТЬ; двадцать четыре колонии из тридцати девяти не имели ни одной
     * постройки вовсе.
     * <p>
     * Порядок стройки был при этом ни при чём, и это важно: завод стоит в {@link #FOUNDATION},
     * то есть ВЫШЕ расселения, — колония бралась бы за него первой, будь он доступен.
     * Недоставало не решения, а технологии.
     * <p>
     * Просим ЧЕТЫРЕ и ровно те, что окупаются быстрее расселения (см. {@link #PAYBACK_TURNS}):
     * завод и лаборатория удваивают рабочего и учёного за шестьдесят единиц, суперкомпьютер и
     * робошахты — вторая их ступень за полтораста. Последних двух здесь не было, и вот чем
     * это обернулось: за 200 ходов их изучали ТРИ империи из восьми — по случайности, попав
     * разделом в любимый, — а строили НОЛЬ раз. Прочие двадцать три здания развития остаются
     * вкусу: список нужд не должен подменять собой устремление правителя, иначе все империи
     * станут одинаковыми. Коды здесь — ТЕХНОЛОГИЙ, а не зданий (они совпадают по написанию не
     * случайно, но и не по правилу).
     */
    private static final List<String> ECONOMY_TECHNOLOGIES =
            List.of("automated-factory", "research-laboratory", "supercomputer", "robo-miners");

    /**
     * Сколько колониальных кораблей империя держит разом.
     * <p>
     * Один: корабль стоит 500 единиц производства, и вторым молодая империя надолго
     * оставила бы без стройки все свои колонии. Как этот высадится — заложат следующий.
     */
    private static final Integer COLONY_SHIPS_AT_ONCE = 1;

    /**
     * Сколько агентов империя держит — реконструкция.
     * <p>
     * В MOO II число шпионов ничем не ограничено, но каждый стоит содержания и готовится
     * колонией вместо чего-то другого. Трое — столько, чтобы разведка работала и её сила
     * была измерима (balance-metrics-works.txt, п. 6), и при этом не съедала стройку
     * молодой империи. Один из них всегда остаётся дома на контрразведке.
     */
    private static final Integer SPIES_AT_ONCE = 3;

    /**
     * Сколько агентов столица заводит РАНО, поперёд расселения, — реконструкция.
     * <p>
     * Двое, и ровно потому, что первый агент по правилам разведки остаётся дома
     * контрразведкой и заданий не выполняет: с одним агентом счётчик разведки так и
     * показывал бы ноль. Третьего и дальше набирают обычным порядком — когда строить
     * больше нечего.
     */
    private static final Integer SPIES_FROM_CAPITAL = 2;

    /**
     * Сколько разведчиков империя держит в пути разом — реконструкция.
     * <p>
     * Один оказался слишком робок: галактика оставалась неисхоженной, и знакомы были шесть
     * пар империй из двадцати восьми. Трое — столько, чтобы соседей находили, и при этом
     * гарнизон не разъехался: боевых кораблей у молодой империи всё равно немного.
     */
    private static final Integer SCOUTS_AT_ONCE = 3;

    /** Во скольких местах колонии империя ценит находку на планете — п. 4.1, см. worthFor. */
    private static final Integer FIND_WORTH = 3;

    /** Больше этого числа транспортов империя не копит: десант должен успеть вылететь. */
    private static final Integer TRANSPORTS_AT_ONCE = 12;

    /**
     * Сколько боевых кораблей империя заводит, прежде чем взяться за транспорты.
     * <p>
     * Гражданский корабль в MOO II уничтожается первым же нападением, поэтому десант без
     * прикрытия не долетает. Двух хватает: дальше стройка уходит в транспорты, иначе
     * империя копит флот, которым некого высаживать, — так и вышло в первом долгом
     * прогоне: почти десять тысяч боевых кораблей и десять высадок за девять сотен ходов.
     */
    private static final Integer WARSHIPS_BEFORE_TRANSPORTS = 2;

    /**
     * Сколько кораблей-застав империя держит разом — п. 8.
     * <p>
     * Один: застава ставится ради дальности, и следующая понадобится только тогда, когда
     * эта раздвинет пузырь и покажет, куда идти дальше.
     */
    private static final Integer OUTPOST_SHIPS_AT_ONCE = 1;

    /**
     * Какая доля свободных жителей идёт в науку — реконструкция.
     * <p>
     * В MOO II устремление правителя видно по тому, куда он вкладывается: технолог живёт
     * наукой, промышленник — заводами. Здесь это доля учёных среди тех, кого не пришлось
     * ставить к еде; остальные идут в рабочие. Без этого правила наука ИИ стояла на месте
     * всю партию: новые жители по умолчанию становятся рабочими, а переставлять их было
     * некому — первый прогон это и показал.
     */
    private static final Map<AiObjective, Integer> SCIENCE_PERCENT = Map.of(
            AiObjective.TECHNOLOGIST, 60,
            AiObjective.DIPLOMAT, 50,
            AiObjective.ECOLOGIST, 50,
            AiObjective.EXPANSIONIST, 40,
            AiObjective.MILITARIST, 35,
            AiObjective.INDUSTRIALIST, 30);

    /** Доля учёных у правителя без устремления — партия из старого слепка. */
    private static final Integer SCIENCE_PERCENT_DEFAULT = 40;

    /**
     * Сколько боевых кораблей империя держит в мирное время — по одному на колонию.
     * <p>
     * Раньше ИИ не строил боевого корабля вовсе, пока его не втянут в войну: стройка
     * доходила до военной ветки только при {@code atWar}, и прогон показывал ноль боевой
     * силы у всех сорока восьми империй к полутораста ходу. Гарнизон это чинит и
     * укладывается в командные очки империи ({@link CommandRules}: колония даёт два очка,
     * фрегат занимает одно) — флот сверх них проедал бы казну.
     */
    private static final Integer WARSHIPS_PER_COLONY_IN_PEACE = 1;

    /**
     * Сколько боевых кораблей на колонию империя держит НА ВОЙНЕ — п. 8.
     * <p>
     * Два, и это не осторожность, а предел командных очков: колония даёт два очка, фрегат
     * занимает одно ({@link CommandRules}), поэтому два корабля на колонию — ровно то, что
     * империя содержит даром. Больше пришлось бы оплачивать из казны каждый ход.
     * <p>
     * Почему мирной нормы мало. Она равна одному кораблю на колонию, и в поход из неё
     * невозможно увести ни одного: гарнизон — это она и есть. Пока норма была одна на
     * войну и мир, флоты не двигались вовсе, а без них не брали и защищённых колоний:
     * за 150 ходов восьми империй выходило НОЛЬ БОЁВ и одна высадка на партию. Разница
     * между войной и миром должна быть видна в том, что империя строит, иначе воевать ей
     * нечем.
     */
    private static final Integer WARSHIPS_PER_COLONY_AT_WAR = 2;

    /**
     * Во сколько раз десант должен превосходить ОБОРОНУ колонии, чтобы её брали.
     * <p>
     * Наземный бой считается силами сторон (п. 12), поэтому меньший десант просто гибнет.
     * Полуторный перевес — та же осторожность, с какой ИИ объявляет войну.
     * <p>
     * Считается именно оборона, а не население. Пока у колоний не было ни казарм, ни
     * оружия, эти две величины отличались только расовой подготовкой, и перевес брался над
     * числом жителей. С казармами (+50 % и ещё +100 % за бронеказармы) такая оценка стала
     * бы враньём: ИИ вёл бы полуторный десант на вдвое более сильную оборону и терял бы
     * его весь, раз за разом, — а прибор балансировки записал бы это в «десант не
     * случается».
     */
    private static final Integer INVASION_ADVANTAGE_PERCENT = 150;

    /**
     * Сколько кредитов империя не тратит на офицеров — реконструкция.
     * <p>
     * Лидер берёт плату разом, а казна нужна каждый ход: в MOO II пустая казна съедает
     * содержание зданий и рушит колонии. Сотня — цена самой дешёвой особой постройки,
     * ниже которой опускаться незачем.
     * <p>
     * Сверх этого запаса империя оставляет ещё одну цену найма: жалованье — двадцатая
     * доля цены за ход (см. {@code LeaderRules}), то есть отложенное равно двадцати ходам
     * службы. Без такой оглядки ИИ нанимал бы на всё, что есть, и разорялся жалованьем:
     * казна уходит в минус молча, а лидеры службу не бросают.
     */
    private static final Integer LEADER_TREASURY_RESERVE = 100;

    private final ColonyService colonyService;
    private final FleetService fleetService;
    private final ExpeditionService expeditionService;
    private final ShipDesignService shipDesignService;
    private final EncounterService encounterService;
    private final RaceService raceService;
    private final FlightRules flightRules;
    private final PopulationCalculator populationCalculator;
    private final FleetRepository fleetRepository;
    private final FleetShipRepository fleetShipRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final EmpireInfoService empireInfoService;
    private final LeaderService leaderService;
    private final EmpireActivityService activity;
    private final EspionageService espionageService;
    private final ExplorationService explorationService;
    private final SpyRepository spyRepository;

    private final GroundCombatService groundCombatService;
    private final BuildingCatalog buildingCatalog;

    /** Перевозка жителей между своими колониями — п. 4.1.1 (журнал, п. 3.102). */
    private final PopulationTransferService transferService;

    public AiEmpireService(ColonyService colonyService,
                           FleetService fleetService,
                           ExpeditionService expeditionService,
                           ShipDesignService shipDesignService,
                           EncounterService encounterService,
                           RaceService raceService,
                           FlightRules flightRules,
                           PopulationCalculator populationCalculator,
                           FleetRepository fleetRepository,
                           FleetShipRepository fleetShipRepository,
                           DiplomacyRelationRepository relationRepository,
                           EmpireInfoService empireInfoService,
                           LeaderService leaderService,
                           EmpireActivityService activity,
                           EspionageService espionageService,
                           ExplorationService explorationService,
                           SpyRepository spyRepository,
                           GroundCombatService groundCombatService,
                           BuildingCatalog buildingCatalog,
                           PopulationTransferService transferService) {
        this.transferService = transferService;
        this.groundCombatService = groundCombatService;
        this.buildingCatalog = buildingCatalog;
        this.activity = activity;
        this.espionageService = espionageService;
        this.explorationService = explorationService;
        this.spyRepository = spyRepository;
        this.colonyService = colonyService;
        this.fleetService = fleetService;
        this.expeditionService = expeditionService;
        this.shipDesignService = shipDesignService;
        this.encounterService = encounterService;
        this.raceService = raceService;
        this.flightRules = flightRules;
        this.populationCalculator = populationCalculator;
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.relationRepository = relationRepository;
        this.empireInfoService = empireInfoService;
        this.leaderService = leaderService;
    }

    /** Ход всех империй ИИ партии: стройка, расселение, флоты, десант, встречи. */
    public void play(TurnContext context) {
        List<PlayerEntity> empires = context.players().stream()
                .filter(player -> player.getPlayerType() == PlayerType.AI)
                .toList();
        if (empires.isEmpty()) {
            return;
        }

        Galaxy galaxy = load(context, empires);
        resolveEncounters(context, galaxy);
        Map<UUID, List<PlayerLeaderEntity>> officers = leaderService.ofAll(
                empires.stream().map(PlayerEntity::getId).toList());

        for (PlayerEntity empire : empires) {
            landings(context, galaxy, empire);
            // Переселение идёт ДО расстановки: оно меняет население обеих колоний, а
            // расстановка считает фермеров по населению — иначе она считала бы по
            // вчерашнему.
            transfers(context, empire);
            distribute(context, empire);
            leaders(context, galaxy, empire, officers.getOrDefault(empire.getId(), List.of()));
            missions(context, galaxy, empire);
            projects(context, galaxy, empire);
            orders(context, galaxy, empire);
        }
    }

    /**
     * Всё, что нужно решениям, — одной выборкой на партию.
     *
     * @param stance  чужая империя → отношения с ней, по строкам самого ИИ
     * @param speeds  проект корабля → его скорость в парсеках за ход
     * @param designs проект корабля → сам проект: по нему видно, боевой он или гражданский
     */
    private record Galaxy(List<FleetEntity> fleets,
                          Map<UUID, List<FleetShipEntity>> composition,
                          Map<UUID, ShipDesignEntity> designs,
                          Map<UUID, Integer> speeds,
                          Map<UUID, Map<UUID, DiplomacyRelationEntity>> stance,
                          Map<UUID, Integer> might,
                          Map<UUID, StarSystemEntity> systemById,
                          /** Сколько колоний у каждой империи — п. 8: по ним меряется гарнизон. */
                          Map<UUID, Integer> colonies,
                          /** Стороны расы каждой империи: по ним видно телепата — п. 7. */
                          Map<UUID, RaceEffects> races,
                          /** Агенты каждой империи — п. 13: по ним решается, кого куда слать. */
                          Map<UUID, List<SpyEntity>> spies,
                          /**
                           * Что каждая империя РАЗВЕДАЛА — п. 15.
                           * <p>
                           * До этого поля ИИ видел галактику целиком и решал по всей карте:
                           * куда селиться, где ставить заставу, на кого идти. Оттого две
                           * стороны расы были мертвы по построению — «всевидящие» не давали
                           * ему ничего (он и так видел всё), а «скрытным кораблям» не от
                           * кого было прятаться. Теперь он видит столько же, сколько
                           * человек, и решает по разведанному.
                           */
                          Map<UUID, Set<UUID>> explored) {

        /** Знает ли империя эту систему: по разведанному, а не по всей карте. */
        Boolean knows(UUID ownerId, UUID systemId) {
            return explored.getOrDefault(ownerId, Set.of()).contains(systemId);
        }

        /**
         * Видит ли империя этот чужой флот — п. 7, п. 15.
         * <p>
         * <b>Скрытные корабли (п. 7) от планов ИИ прятались только на словах.</b> Игроку
         * они и правда не показывались на карте, а ИИ читал список флотов целиком — и
         * сторона расы за шесть очков не давала против него РОВНО НИЧЕГО. Правило здесь то
         * же, что у сканеров игрока ({@code ExplorationService.occupiedBy}): скрытный флот
         * не виден, если смотрящий не всевидящий и сам не стоит в той же системе. В бою
         * такой флот, разумеется, участвует — прячется он от разведки, а не от пушек.
         */
        Boolean sees(UUID observerId, FleetEntity fleet) {
            if (observerId.equals(fleet.getOwnerPlayerId())) {
                return Boolean.TRUE;
            }
            RaceEffects mine = races.get(observerId);
            if (mine != null && Boolean.TRUE.equals(mine.omniscient())) {
                return Boolean.TRUE;
            }
            RaceEffects theirs = races.get(fleet.getOwnerPlayerId());
            if (theirs == null || !Boolean.TRUE.equals(theirs.stealthyShips())) {
                return Boolean.TRUE;
            }
            // Свои глаза на месте: в своей же системе прятаться не от кого.
            return fleets.stream()
                    .filter(one -> one.getOwnerPlayerId().equals(observerId))
                    .filter(one -> !Boolean.TRUE.equals(one.isInFlight()))
                    .anyMatch(one -> fleet.getStarSystemId() != null
                            && fleet.getStarSystemId().equals(one.getStarSystemId()));
        }

        List<FleetEntity> standing(UUID ownerId) {
            return fleets.stream()
                    .filter(fleet -> fleet.getOwnerPlayerId().equals(ownerId))
                    .filter(fleet -> fleet.getShips() > 0)
                    .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                    .toList();
        }

        /**
         * Сколько у империи кораблей этой роли — во всех флотах, включая летящие.
         * Стройка смотрит именно на всё: корабль в пути уже построен и своё дело сделает.
         */
        Integer owned(UUID ownerId, ShipRole role) {
            return fleets.stream()
                    .filter(fleet -> fleet.getOwnerPlayerId().equals(ownerId))
                    .mapToInt(fleet -> count(fleet, role))
                    .sum();
        }

        /** Сколько у империи колоний: по ним меряется и гарнизон, и командные очки. */
        Integer coloniesOf(UUID ownerId) {
            return colonies.getOrDefault(ownerId, 0);
        }

        /** Есть ли во флоте корабли этой роли и сколько их. */
        Integer count(FleetEntity fleet, ShipRole role) {
            return composition.getOrDefault(fleet.getId(), List.of()).stream()
                    .filter(row -> role(row) == role)
                    .mapToInt(FleetShipEntity::getShips)
                    .sum();
        }

        /** Сколько бойцов везёт флот: сумма груза его транспортов — п. 12. */
        Integer troops(FleetEntity fleet) {
            return composition.getOrDefault(fleet.getId(), List.of()).stream()
                    .filter(row -> role(row) == ShipRole.TRANSPORT)
                    .mapToInt(FleetShipEntity::getColonists)
                    .sum();
        }

        ShipRole role(FleetShipEntity row) {
            ShipDesignEntity design = designs.get(row.getDesignId());
            return design == null ? ShipRole.WARSHIP : design.getRole();
        }

        /** Скорость флота — по самому медленному его кораблю (п. 8). */
        Integer speed(FleetEntity fleet) {
            return composition.getOrDefault(fleet.getId(), List.of()).stream()
                    .map(row -> speeds.getOrDefault(row.getDesignId(), 1))
                    .min(Comparator.naturalOrder())
                    .orElse(1);
        }

        /** Агенты империи — п. 13; порядок задан выборкой, и он важен для повторимости. */
        List<SpyEntity> spiesOf(UUID ownerId) {
            return spies.getOrDefault(ownerId, List.of());
        }

        /**
         * Знакомые империи — п. 15: с кем есть строка отношений, тот и знаком.
         * Порядок по идентификатору: решения ИИ полны выборов «первый подходящий», а
         * партия с одним зерном обязана повторяться.
         */
        List<UUID> knownRivals(UUID ownerId) {
            return stance.getOrDefault(ownerId, Map.of()).keySet().stream()
                    .sorted()
                    .toList();
        }

        DiplomacyStance stanceOf(UUID playerId, UUID otherId) {
            DiplomacyRelationEntity relation = stance.getOrDefault(playerId, Map.of()).get(otherId);
            return relation == null ? null : relation.getStance();
        }

        /** Связывают ли империю договоры: пакт и союз запрещают нападение — п. 15. */
        Boolean attackForbidden(UUID playerId, UUID otherId) {
            DiplomacyRelationEntity relation = stance.getOrDefault(playerId, Map.of()).get(otherId);
            if (relation == null) {
                return Boolean.FALSE;
            }
            Set<DiplomacyTreaty> treaties = relation.getTreaties();
            return treaties.contains(DiplomacyTreaty.ALLIANCE)
                    || treaties.contains(DiplomacyTreaty.NON_AGGRESSION);
        }
    }

    private Galaxy load(TurnContext context, List<PlayerEntity> empires) {
        UUID gameId = context.game().getId();
        // Порядок флотов и их строк задаётся явно: база отдаёт строки как ей удобно, а
        // решения ИИ полны выборов «первый подходящий» и «наименьший из равных». Партия с
        // тем же зерном обязана повторяться до последнего числа — на этом держатся парные
        // прогоны балансировки (balance-metrics-works.txt, этап 0).
        // Ключи порядка — ИГРОВЫЕ, а не идентификаторы строк: id это случайный UUID, и
        // порядок по нему между двумя прогонами одной партии разный (см. выкуп ниже).
        // Место игрока и название звезды выводятся из зерна партии, ход создания — из
        // самой партии.
        Map<UUID, Integer> slotByPlayer = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getSlot));
        Map<UUID, String> systemNames = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, StarSystemEntity::getName));
        List<FleetEntity> fleets = fleetRepository.findAllByGameId(gameId).stream()
                .sorted(Comparator
                        .comparing((FleetEntity fleet) ->
                                slotByPlayer.getOrDefault(fleet.getOwnerPlayerId(), 0))
                        .thenComparing(fleet ->
                                systemNames.getOrDefault(fleet.getStarSystemId(), ""))
                        .thenComparing(FleetEntity::getCreatedTurn)
                        .thenComparing(FleetEntity::getId))
                .toList();
        Map<UUID, ShipDesignEntity> designsById = shipDesignService.designsOfGame(gameId);
        Map<UUID, List<FleetShipEntity>> composition = fleets.isEmpty()
                ? Map.of()
                : fleetShipRepository.findAllByFleetIdIn(
                        fleets.stream().map(FleetEntity::getId).toList()).stream()
                .sorted(Comparator
                        .comparing((FleetShipEntity row) -> {
                            ShipDesignEntity design = designsById.get(row.getDesignId());
                            return design == null ? "" : design.getName();
                        })
                        .thenComparing(FleetShipEntity::getId))
                .collect(Collectors.groupingBy(FleetShipEntity::getFleetId));

        Map<UUID, RaceEffects> races = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, raceService::effects));

        Map<UUID, Map<UUID, DiplomacyRelationEntity>> stance = relationRepository
                .findAllByPlayerIdIn(empires.stream().map(PlayerEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(DiplomacyRelationEntity::getPlayerId,
                        Collectors.toMap(DiplomacyRelationEntity::getOtherPlayerId,
                                relation -> relation, (first, second) -> first)));

        // Агенты — одной выборкой на партию, как флоты и отношения: решение о них
        // принимается за каждую империю, и выборка «на игрока» била бы по всему ходу.
        Map<UUID, List<SpyEntity>> spies = spyRepository
                .findAllByOwnerPlayerIdIn(
                        empires.stream().map(PlayerEntity::getId).toList()).stream()
                .sorted(GameOrder.SPIES)
                .collect(Collectors.groupingBy(SpyEntity::getOwnerPlayerId));

        return new Galaxy(
                fleets,
                composition,
                designsById,
                fleetService.designSpeeds(gameId, races),
                stance,
                empireInfoService.mightByPlayer(context),
                context.systems().stream()
                        .collect(Collectors.toMap(StarSystemEntity::getId, system -> system)),
                // Колонии считаются один раз на партию: гарнизон спрашивает их у каждой
                // колонии каждый ход, а ход галактику вычитывает ровно один раз.
                context.colonies().stream()
                        .filter(colony -> colony.getOwnerPlayerId() != null)
                        .collect(Collectors.groupingBy(PlanetEntity::getOwnerPlayerId,
                                Collectors.reducing(0, colony -> 1, Integer::sum))),
                races,
                spies,
                // Разведка всех империй — ОДНОЙ выборкой: спрашивать её на игрока значило
                // бы три запроса на империю каждый ход изнутри посчитанного хода.
                explorationService.exploredByAll(context.systems(), empires));
    }

    /**
     * Встречи флотов, оставшиеся с прошлого хода, — п. 8.
     * <p>
     * Фаза встреч ищет их <b>после</b> этой (порядок 14 против 8), поэтому решение
     * принимается ходом позже — так же, как у человека: он тоже видит встречу в итогах
     * хода и решает на следующем.
     * <p>
     * Бой считается «авто»: тактическое поле — это ход за ходом двух живых сторон, и
     * ставить туда две империи ИИ незачем. Само решение — от характера: беспощадный
     * нападает почти всегда, миролюбивый расходится, остальные смотрят на силу флотов.
     */
    private void resolveEncounters(TurnContext context, Galaxy galaxy) {
        for (FleetEncounterEntity encounter : encounterService.pendingOf(context.game().getId())) {
            // Одна встреча может дойти до обеих сторон: первый разошёлся — решает второй.
            for (int step = 0; step < 2; step++) {
                if (!Boolean.TRUE.equals(encounter.getState().isPending())) {
                    break;
                }
                UUID deciderId = encounter.getState() == EncounterState.WAITING_FIRST
                        ? encounter.getFirstPlayerId()
                        : encounter.getSecondPlayerId();
                PlayerEntity decider = player(context, deciderId);
                if (decider == null || decider.getPlayerType() != PlayerType.AI) {
                    break;
                }
                UUID opponentId = encounter.opponentOf(deciderId);
                encounterService.decide(decider, context.turn(), encounter.getId(),
                        attacks(decider, opponentId, galaxy)
                                ? EncounterDecision.ATTACK
                                : EncounterDecision.IGNORE,
                        Boolean.TRUE);
            }
        }
    }

    /**
     * Нападать ли на встреченный флот.
     * <p>
     * Договор сильнее характера: пакт о ненападении и союз связывают руки, и проверить
     * это надо <b>до</b> вызова — отказ изнутри посчитанного хода валит весь ход.
     */
    private Boolean attacks(PlayerEntity decider, UUID opponentId, Galaxy galaxy) {
        if (Boolean.TRUE.equals(galaxy.attackForbidden(decider.getId(), opponentId))) {
            return Boolean.FALSE;
        }
        if (galaxy.stanceOf(decider.getId(), opponentId) == DiplomacyStance.WAR) {
            return Boolean.TRUE;
        }
        AiPersonality personality = decider.getAiPersonality();
        if (personality == null || Boolean.TRUE.equals(personality.seeksPeace())) {
            return Boolean.FALSE;
        }
        // Перевес меряется той же мощью империй, что и решение объявить войну (п. 15):
        // нападать на встречный флот, уступая сопернику вдвое, — не характер, а самоубийство.
        Integer mine = galaxy.might().getOrDefault(decider.getId(), 0);
        Integer theirs = galaxy.might().getOrDefault(opponentId, 0);
        return mine * 100 >= theirs * personality.getWarAdvantagePercent();
    }

    /**
     * Что флоты делают там, где уже стоят, — п. 4.1 и п. 12: основывают колонию и
     * высаживают десант. Сюда же попадает готовая колониальная база: селиться по соседству
     * ей можно, не отходя от планеты.
     */
    private void landings(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        settleReadyBases(context, empire);

        for (FleetEntity fleet : galaxy.standing(empire.getId())) {
            StarSystemEntity system = galaxy.systemById().get(fleet.getStarSystemId());
            if (system == null) {
                continue;
            }
            if (galaxy.count(fleet, ShipRole.COLONY) > 0) {
                bestFreePlanet(system, raceService.effects(empire))
                        .ifPresent(planet -> settle(context, empire, fleet, planet));
            }
            if (galaxy.count(fleet, ShipRole.OUTPOST) > 0) {
                outpostSpot(system).ifPresent(planet -> plant(context, empire, fleet, planet));
            }
            if (galaxy.count(fleet, ShipRole.TRANSPORT) > 0) {
                invasionTarget(context, galaxy, empire, system, galaxy.troops(fleet))
                        .ifPresent(planet -> land(context, galaxy, empire, fleet, planet));
            }
            mindControl(context, galaxy, empire, fleet, system);
        }
    }

    /**
     * Готовая колониальная база заселяет соседнюю планету своей системы — п. 4.1.
     * <p>
     * Правило повторено здесь, а не взято у {@code ColonyService.colonize}: тот читает
     * планету из базы и сохраняет её сам, а фаза идёт внутри посчитанного хода, где
     * лишний запрос сбрасывает в базу всё, что ход успел изменить. Копии две, и держать
     * их в ногу приходится руками — счётчик механик про это уже забывали: колония от
     * базы у ИИ не считалась колонией, и в тесной галактике, где ИИ расселяется почти
     * одними базами, замер показывал ноль основанных колоний при тринадцати на карте.
     */
    private void settleReadyBases(TurnContext context, PlayerEntity empire) {
        for (PlanetEntity colony : context.coloniesOf(empire.getId())) {
            if (!Boolean.TRUE.equals(colony.getColonyBaseReady())) {
                continue;
            }
            Optional<PlanetEntity> target = bestFreePlanet(
                    colony.getStarSystem(), context.colonyContext().race(colony));
            if (target.isEmpty()) {
                continue;
            }
            PlanetEntity planet = target.get();
            planet.setOwnerPlayerId(empire.getId());
            planet.setPopulation(1);
            planet.setJobs(populationCalculator.defaultJobs(planet.getClimate(), 1));
            planet.setProjectCode(ColonyProject.TRADE_GOODS);
            planet.setProjectPoints(0);
            colony.setColonyBaseReady(Boolean.FALSE);
            activity.record(context.game().getId(), empire.getId(),
                    EmpireActivityService.COLONIZED);

            context.report().add(empire.getId(), "COLONY",
                    new MessageKey("turn.ai.settledByBase", empire.getName(), planet.getName()),
                    colony.getStarSystem().getId(), planet.getId());
            log.info("ИИ {} заселил {} колониальной базой", empire.getName(), planet.getName());
        }
    }

    /** Высадка колонии с корабля: дальше проверок и правил — дело {@link ExpeditionService}. */
    private void settle(TurnContext context, PlayerEntity empire, FleetEntity fleet, PlanetEntity planet) {
        expeditionService.colonize(empire, fleet.getId(), planet.getId());
        context.report().add(empire.getId(), "COLONY",
                new MessageKey("turn.ai.colonyFounded", empire.getName(), planet.getName()),
                planet.getStarSystem().getId(), planet.getId());
        log.info("ИИ {} основал колонию на {}", empire.getName(), planet.getName());
    }

    /** Застава на планете системы, где стоит флот, — п. 8. */
    private void plant(TurnContext context, PlayerEntity empire, FleetEntity fleet, PlanetEntity planet) {
        expeditionService.outpost(empire, fleet.getId(), planet.getId());
        context.report().add(empire.getId(), "OUTPOST",
                new MessageKey("turn.ai.outpostPlanted", empire.getName(), planet.getName()),
                planet.getStarSystem().getId(), planet.getId());
        log.info("ИИ {} поставил заставу на {}", empire.getName(), planet.getName());
    }

    /**
     * Куда ставить заставу в этой системе — п. 8.
     * <p>
     * Прежде всего на негодную для жизни планету: пояс астероидов или газовый гигант
     * колонии всё равно не достанется, а дальность от него та же. Годная под колонию
     * планета идёт в дело, только если другой в системе нет.
     */
    private Optional<PlanetEntity> outpostSpot(StarSystemEntity system) {
        List<PlanetEntity> free = system.getPlanets().stream()
                .filter(planet -> planet.getOwnerPlayerId() == null)
                .toList();
        return free.stream()
                .filter(planet -> !Boolean.TRUE.equals(planet.getClimate().getColonizable()))
                .findFirst()
                .or(() -> free.stream().findFirst());
    }

    /**
     * Кем работают жители колоний империи — п. 4.1.
     * <p>
     * Расстановка пересчитывается каждый ход: сперва столько фермеров, чтобы колония
     * себя кормила, остальные делятся между рабочими и учёными по устремлению правителя.
     * Игроку так не делают — у него есть экран, — а у ИИ иначе новые жители навсегда
     * оставались бы рабочими, и наука империи стояла бы на месте.
     * <p>
     * <b>Фермеры считаются ПО СВОЕЙ РАСЕ И ЗДАНИЯМ</b>, а не по одному климату. Прежде
     * здесь стоял расчёт, знающий только климат, и империя ИИ расставляла жителей так,
     * будто раса у неё средняя: с плохими фермерами колония получала на четверть меньше
     * нужного и голодала вечно, а с хорошими копила излишек вместо того, чтобы отпустить
     * людей к станку. Сторона расы за −8 очков мерилась при этом силой −18,61 — это была
     * не её цена, а непочиненная расстановка (журнал, п. 3.66).
     * <p>
     * <b>А теперь фермеры считаются ПО ВСЕЙ ИМПЕРИИ, а не по колонии</b> — п. 4.1.1
     * (журнал, п. 3.100). Самопрокорм каждой колонии означал, что империя не
     * специализируется никогда, хотя игра умеет развозить излишек грузовым флотом. Цена
     * этого промаха была не в удобстве, а в замере: кормовая сторона расы прибавляла еду
     * на КАЖДОЙ колонии сразу, и вся кормовая ось оказалась переоценена — «хорошие
     * фермеры» за семь очков мерились восемнадцатью, литоворы за тринадцать —
     * пятьюдесятью. Правило целиком живёт в
     * {@link PopulationCalculator#farmersAcrossEmpire}; здесь только сбор таблиц.
     */
    private void distribute(TurnContext context, PlayerEntity empire) {
        Integer sciencePercent = SCIENCE_PERCENT.getOrDefault(
                empire.getAiObjective(), SCIENCE_PERCENT_DEFAULT);
        List<PlanetEntity> own = context.coloniesOf(empire.getId());
        if (own.isEmpty()) {
            return;
        }
        Farmlands lands = farmlands(context, own);
        List<Integer> farmers = populationCalculator.farmersAcrossEmpire(
                lands.tables(), lands.needs(),
                populationCalculator.freightCapacity(empire.getFreighters()));

        for (int index = 0; index < own.size(); index++) {
            PlanetEntity colony = own.get(index);
            Integer free = colony.getPopulation() - farmers.get(index);
            // ХОТЯ БЫ ОДИН УЧЁНЫЙ, пока есть свободные руки — п. 4.1 (журнал, п. 3.73).
            // Доля считается целыми, и на малой колонии она обращалась в НОЛЬ: при 35 % у
            // правителя-militarist колония из пяти жителей кормит себя тремя фермерами, из
            // двух оставшихся 2 * 35 / 100 = 0 — и колония не давала науке ничего. Колоний
            // такого размера у империи ИИ большинство, отсюда и наука, за полтораста ходов
            // выросшая с шести очков до двенадцати (летопись сильнейшей империи).
            //
            // Единица, а не округление вверх: округление отдало бы науке половину маленькой
            // колонии, а она и кормится-то еле-еле. Один учёный — это ровно «наука не стоит
            // на месте», и ни очком больше.
            Integer scientists = Math.min(free, Math.max(free > 0 ? 1 : 0,
                    free * sciencePercent / 100));
            PopulationJobs wanted = new PopulationJobs(
                    farmers.get(index), free - scientists, scientists);
            if (!wanted.equals(colony.getJobs())) {
                colony.setJobs(wanted);
            }
        }
    }

    /**
     * Колонии империи глазами расстановки фермеров — п. 4.1.1.
     *
     * @param tables еда каждой колонии при 0, 1, 2… фермерах
     * @param needs  сколько съедает каждая колония
     */
    private record Farmlands(List<List<Integer>> tables, List<Integer> needs) {
    }

    /**
     * Собирает таблицы еды по колониям империи — п. 4.1.1 (журнал, п. 3.100).
     * <p>
     * Таблицей, а не формулой: еду колонии считает {@code PopulationCalculator.food} — с
     * процентами зданий, надбавкой ферм и расой, — и обращать её значило бы завести
     * второе правило, которое однажды разъедется с первым. Население колонии измеряется
     * десятками, поэтому таблица дешевле обращения.
     * <p>
     * Ходов в базу здесь нет: и здания, и раса уже лежат в контексте хода.
     */
    private Farmlands farmlands(TurnContext context, List<PlanetEntity> own) {
        ColonyService.ColonyContext colonies = context.colonyContext();
        List<List<Integer>> tables = new ArrayList<>(own.size());
        List<Integer> needs = new ArrayList<>(own.size());
        for (PlanetEntity colony : own) {
            BuildingEffects effects = colonies.effects(colony);
            RaceEffects race = colonies.race(colony);
            List<Integer> table = new ArrayList<>(colony.getPopulation() + 1);
            for (int farmers = 0; farmers <= colony.getPopulation(); farmers++) {
                table.add(populationCalculator.food(colony.getClimate(), farmers, effects, race));
            }
            tables.add(List.copyOf(table));
            needs.add(populationCalculator.foodConsumption(colony.getPopulation(), race));
        }
        return new Farmlands(List.copyOf(tables), List.copyOf(needs));
    }

    /**
     * Сколько грузовиков нужно империи — п. 4.1.1 (журнал, п. 3.100).
     * <p>
     * Столько, чтобы развезти еду при ПОЛНОЙ специализации: в поле одни кормилицы,
     * остальные у станка. Это и есть та цель, ради которой грузовик строится; пока флота
     * не хватает, расстановка сама откатывается к самопрокорму — правило одно, и живёт оно
     * в {@link PopulationCalculator#farmersAcrossEmpire}.
     * <p>
     * <b>Почему это вообще понадобилось.</b> {@code FREIGHTER} не встречался в этой службе
     * НИ РАЗУ: империя ИИ грузовиков не строила, значит подвоз еды по империи был для неё
     * мёртв целиком, а ответ на голод — ровно один, загнать в поле ещё одного жителя.
     */
    private Integer freightersWanted(TurnContext context, PlayerEntity empire) {
        Integer forFood = foodFreighters(context, empire);
        // И РЕЙС ЖИТЕЛЕЙ СВЕРХ ЕДЫ — п. 4.1.1 (журнал, п. 3.102). Без этого слагаемого
        // литоворы не строили ни одного грузовика: еды им не надо вовсе, нужда выходила
        // нулём, — и перевозить жителей им было нечем. А именно им она и нужна больше
        // прочих: расселение у литоворов упирается только в людей.
        Integer forMove = wantedTransfer(context, empire)
                .filter(move -> !Boolean.TRUE.equals(move.sameSystem()))
                .map(move -> populationCalculator.freightersForTransfer(move.population()))
                .orElse(0);
        return forFood + forMove;
    }

    /** Сколько грузовиков нужно империи на один только подвоз еды — п. 4.1.1. */
    private Integer foodFreighters(TurnContext context, PlayerEntity empire) {
        List<PlanetEntity> own = context.coloniesOf(empire.getId());
        if (own.isEmpty()) {
            return 0;
        }
        Farmlands lands = farmlands(context, own);
        return populationCalculator.freightersForFood(
                populationCalculator.freightForSpecialisation(lands.tables(), lands.needs()));
    }

    /**
     * Переселение, которого империя хочет прямо сейчас — п. 4.1.1 (журнал, п. 3.102).
     *
     * @param from       откуда: колония, которой расти уже некуда
     * @param to         куда: колония с самым большим свободным местом
     * @param population сколько жителей
     * @param sameSystem в своей системе жители переходят сразу и без грузовиков
     */
    private record Move(PlanetEntity from, PlanetEntity to, Integer population, Boolean sameSystem) {
    }

    /**
     * Сколько жителей империя увозит за один раз — п. 4.1.1.
     * <p>
     * Пятеро: столько поднимает один грузовик еды, и столько же стоит перевозка пятерых —
     * по грузовику на жителя. Больше значит надолго занять весь флот, меньше — возить
     * бесконечно.
     */
    private static final Integer TRANSFER_POPULATION = 5;

    /**
     * Насколько полной считается колония, из которой пора увозить, — п. 4.1.1.
     * <p>
     * Восемь десятых вместимости: прирост в MOO II наибольший на половине и обращается в
     * НОЛЬ у полной колонии, поэтому держать людей на забитой планете — значит не
     * получать с них ни роста, ни новых колоний.
     */
    private static final Integer CROWDED_PERCENT = 80;

    /**
     * Куда и откуда империя ИИ везёт жителей — п. 4.1.1 (журнал, п. 3.102).
     * <p>
     * <b>Зачем.</b> {@code PopulationTransferService} не встречался в этой службе НИ РАЗУ:
     * перевозка жителей была механикой только для игрока. А без неё разваливается вся
     * стратегия расселения MOO II — плохая планета копит людей и стоит забитой, потому что
     * у полной колонии прирост нулевой, а хорошая растёт с одного жителя десятки ходов.
     * Человек играет наоборот: плохие миры кормят людьми хорошие. Сильнее всего это било по
     * литоворам: еды им не надо вовсе, поэтому грузовиков они не строили ни одного — и
     * возить жителей им тоже стало нечем.
     * <p>
     * <b>Правило.</b> Везём с самой забитой колонии на ту, где больше всего свободного
     * места. Внутри своей системы — даром и сразу; между звёздами — грузовиками, и только
     * тем флотом, что остался сверх подвоза еды: голод дороже расселения.
     * <p>
     * <b>Один рейс за ход на империю.</b> Рейс уходит в {@code TransferPhase} следующего
     * хода (порядок 6, раньше фазы ИИ), поэтому больше одного в воздухе не бывает — и
     * занятые грузовики можно не спрашивать у базы, а вычесть здесь же.
     */
    private Optional<Move> wantedTransfer(TurnContext context, PlayerEntity empire) {
        List<PlanetEntity> own = context.coloniesOf(empire.getId());
        if (own.size() < 2) {
            return Optional.empty();
        }
        ColonyService.ColonyContext colonies = context.colonyContext();

        PlanetEntity crowded = null;
        PlanetEntity roomiest = null;
        int mostRoom = 0;
        int fullest = 0;
        for (PlanetEntity colony : own) {
            Integer capacity = colonyService.maxPopulation(
                    colony, colonies.effects(colony), colonies.race(colony));
            if (capacity <= 0) {
                continue;
            }
            int room = capacity - colony.getPopulation();
            int filled = colony.getPopulation() * 100 / capacity;
            // Строгое сравнение и разбор равенства по имени: партия обязана повторяться,
            // а «первый подходящий из равных» без явного порядка расходится от прогона
            // к прогону.
            if (room > mostRoom
                    || (room == mostRoom && roomiest != null
                            && colony.getName().compareTo(roomiest.getName()) < 0)) {
                mostRoom = room;
                roomiest = colony;
            }
            if (colony.getPopulation() > 1 && filled >= CROWDED_PERCENT
                    && (filled > fullest
                            || (filled == fullest && crowded != null
                                    && colony.getName().compareTo(crowded.getName()) < 0))) {
                fullest = filled;
                crowded = colony;
            }
        }
        if (crowded == null || roomiest == null || crowded.getId().equals(roomiest.getId())) {
            return Optional.empty();
        }
        // Колонию нельзя оставить без населения, а привезти больше, чем поместится, —
        // некуда: оба предела правила перевозки, и оба проверены до отправки.
        int population = Math.min(TRANSFER_POPULATION,
                Math.min(crowded.getPopulation() - 1, mostRoom));
        if (population <= 0) {
            return Optional.empty();
        }
        Boolean sameSystem = crowded.getStarSystem() != null && roomiest.getStarSystem() != null
                && crowded.getStarSystem().getId().equals(roomiest.getStarSystem().getId());
        return Optional.of(new Move(crowded, roomiest, population, sameSystem));
    }

    /**
     * Есть ли этой колонии куда расти — п. 4.1.1 (журнал, п. 3.102).
     * <p>
     * Половина вместимости: по формуле прироста MOO II он наибольший ровно на половине и
     * обращается в ноль у полной колонии, поэтому дома окупаются на молодой колонии и почти
     * ничего не дают подросшей.
     */
    private Boolean roomToGrow(TurnContext context, PlanetEntity colony) {
        ColonyService.ColonyContext colonies = context.colonyContext();
        Integer capacity = colonyService.maxPopulation(
                colony, colonies.effects(colony), colonies.race(colony));
        return colony.getPopulation() * 2 <= capacity;
    }

    /** Отправляет рейс, если он возможен и есть чем везти, — п. 4.1.1. */
    private void transfers(TurnContext context, PlayerEntity empire) {
        Optional<Move> wanted = wantedTransfer(context, empire);
        if (wanted.isEmpty()) {
            return;
        }
        Move move = wanted.get();
        if (!Boolean.TRUE.equals(move.sameSystem())) {
            // Грузовики сперва кормят: еда важнее расселения, и отнимать их у подвоза
            // значит устроить голод ради роста.
            Integer forFood = foodFreighters(context, empire);
            Integer needed = populationCalculator.freightersForTransfer(move.population());
            if (empire.getFreighters() - forFood < needed) {
                return;
            }
        }
        transferService.sendFromTurn(context, empire, move.from(), move.to(), move.population());
    }

    /**
     * Офицеры империи ИИ — п. 6.
     * <p>
     * В MOO II лидеры служат и соседям, поэтому империя ИИ нанимает их теми же правилами,
     * что и человек: те же четыре места на род, та же цена, тот же {@code hire}. Своей
     * ветки правил у неё нет — разъехаться им было бы негде, и предложения ей делает та
     * же фаза лидеров, с той же поправкой на расу.
     * <p>
     * <b>Кого берёт.</b> Самого дорогого из предложенных, кого тянет казна с запасом:
     * цена лидера в этой игре и есть мера его силы — она сложена из звания, числа
     * способностей и принесённых технологий. Выбирать «по способности» значило бы
     * заводить ИИ второй набор предпочтений вдобавок к устремлению правителя.
     * <p>
     * <b>Куда ставит.</b> Туда, где от него больше проку: колониального — в систему самой
     * людной своей колонии, где своего лидера ещё нет (в системе служит один — п. 6),
     * корабельного — в самый крупный стоящий флот. Флот тоже берётся один на лидера:
     * четыре адмирала на один флот дали бы прибавку четырежды, а в оригинале офицер
     * приписан к своему кораблю.
     * <p>
     * <b>Кого отпускает.</b> Того, кому больше не может платить: жалованье в этой игре
     * уводит казну в минус молча (см. {@code LeaderPhase}), и без такого правила ИИ,
     * набравший восьмерых, оставался бы в долгах до конца партии. Уходит самый дорогой —
     * тот, из-за кого долг и вырос.
     */
    private void leaders(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                         List<PlayerLeaderEntity> own) {
        if (own.isEmpty()) {
            return;
        }

        // Казна в минусе — с новыми офицерами это не лечится: империя отпускает самого
        // дорогого из служащих и в этот ход не нанимает никого.
        if (empire.getCredits() < 0) {
            own.stream()
                    .filter(row -> row.getState() == LeaderState.HIRED)
                    .max(Comparator.comparing(PlayerLeaderEntity::getSalary))
                    .ifPresent(row -> {
                        leaderService.dismiss(empire, row.getId());
                        log.info("ИИ {} уволил лидера {}: казна в минусе ({} кр.)",
                                empire.getName(), row.getLeaderCode(), empire.getCredits());
                    });
            return;
        }

        // Сперва пристроить тех, кто уже нанят: место могло появиться позже — новая
        // колония, новый флот. Нанятый без места не делает почти ничего.
        for (PlayerLeaderEntity row : own) {
            if (row.getState() != LeaderState.HIRED
                    || row.getStarSystemId() != null || row.getFleetId() != null) {
                continue;
            }
            UUID target = place(context, galaxy, empire, own, row.getKind());
            if (target == null) {
                continue;
            }
            leaderService.assign(empire, row.getId(), target, context.turn(),
                    homeSystem(context, empire));
        }

        for (LeaderKind kind : LeaderKind.values()) {
            long hired = own.stream()
                    .filter(row -> row.getKind() == kind)
                    .filter(row -> row.getState() == LeaderState.HIRED)
                    .count();
            if (hired >= LeaderRules.SLOTS_PER_KIND) {
                continue;
            }
            // Ставить некуда — значит и нанимать незачем: лидер в резерве жалованье берёт,
            // а работает только теми способностями, что действуют всегда. Первый прогон
            // это и показал: империя ИИ держала семерых, из них при деле был один.
            UUID target = place(context, galaxy, empire, own, kind);
            if (target == null) {
                continue;
            }
            own.stream()
                    .filter(row -> row.getKind() == kind)
                    .filter(row -> row.getState() == LeaderState.OFFERED)
                    .filter(row -> empire.getCredits() - 2 * row.getHireCost()
                            >= LEADER_TREASURY_RESERVE)
                    .max(Comparator.comparing(PlayerLeaderEntity::getHireCost))
                    .ifPresent(row -> {
                        leaderService.hire(empire, row.getId(), context.turn(),
                                context.game().getSeed());
                        leaderService.assign(empire, row.getId(), target, context.turn(),
                                homeSystem(context, empire));
                        log.info("ИИ {} нанял лидера {} за {} кр.",
                                empire.getName(), row.getLeaderCode(), row.getHireCost());
                    });
        }
    }

    /** Куда империи поставить лидера этого рода — п. 6; {@code null} — ставить некуда. */
    private UUID place(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                       List<PlayerLeaderEntity> own, LeaderKind kind) {
        return kind == LeaderKind.COLONY
                ? colonySeat(context, empire, own)
                : flagship(galaxy, empire, own);
    }

    /** Система самой людной колонии, где колониального лидера ещё нет, — п. 6. */
    private UUID colonySeat(TurnContext context, PlayerEntity empire, List<PlayerLeaderEntity> own) {
        Set<UUID> taken = own.stream()
                .filter(row -> row.getState() == LeaderState.HIRED)
                .map(PlayerLeaderEntity::getStarSystemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return context.coloniesOf(empire.getId()).stream()
                .filter(colony -> !taken.contains(colony.getStarSystem().getId()))
                .max(Comparator.comparing(PlanetEntity::getPopulation))
                .map(colony -> colony.getStarSystem().getId())
                .orElse(null);
    }

    /** Самый крупный стоящий флот без своего офицера — п. 6. */
    private UUID flagship(Galaxy galaxy, PlayerEntity empire, List<PlayerLeaderEntity> own) {
        Set<UUID> taken = own.stream()
                .filter(row -> row.getState() == LeaderState.HIRED)
                .map(PlayerLeaderEntity::getFleetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return galaxy.standing(empire.getId()).stream()
                .filter(fleet -> !taken.contains(fleet.getId()))
                .max(Comparator.comparing(FleetEntity::getShips))
                .map(FleetEntity::getId)
                .orElse(null);
    }

    /**
     * Родная система империи — та, где стоит офицерский резерв: назначенный туда лидер
     * приступает сразу, а в прочие добирается пять ходов (п. 6).
     */
    private UUID homeSystem(TurnContext context, PlayerEntity empire) {
        return context.coloniesOf(empire.getId()).stream()
                .filter(colony -> Boolean.TRUE.equals(colony.getHomeworld()))
                .findFirst()
                .map(colony -> colony.getStarSystem().getId())
                .orElse(null);
    }

    /**
     * Задания агентам — п. 13.
     * <p>
     * Первый агент остаётся дома: очки разведки дома и есть контрразведка империи, и без
     * неё соседи обкрадывали бы её безнаказанно. Остальные уходят к самой сильной знакомой
     * империи — красть технологии, а во время войны срывать ей стройку.
     * <p>
     * Задание ставится тем же {@link EspionageService#assign}, которым его ставит игрок:
     * второго свода правил разведки для ИИ нет. Выборка при этом одна на партию — агенты
     * приходят из {@link Galaxy}; сам {@code assign} читает строку агента ещё раз, но
     * зовут его лишь тогда, когда агент и правда без дела, то есть считанные разы за
     * партию.
     */
    private void missions(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        List<SpyEntity> spies = galaxy.spiesOf(empire.getId());
        if (spies.size() < 2) {
            return;
        }
        List<UUID> rivals = galaxy.knownRivals(empire.getId());
        if (rivals.isEmpty()) {
            return;
        }

        // Цель — сильнейший из знакомых: у него и красть выгоднее, и срывать стройку
        // важнее. Мощь берётся та же, по которой ИИ решает, воевать ли (п. 15).
        UUID target = rivals.stream()
                .max(Comparator.comparing(rival -> galaxy.might().getOrDefault(rival, 0)))
                .orElse(null);
        if (target == null) {
            return;
        }
        SpyMission mission = galaxy.stanceOf(empire.getId(), target) == DiplomacyStance.WAR
                ? SpyMission.SABOTAGE
                : SpyMission.STEAL_TECH;

        // Первый агент — дома, остальные в деле. Без дела считается только тот, кто стоит
        // дома: отзывать работающего ради другой цели значило бы каждый ход обнулять его
        // подготовку.
        for (SpyEntity spy : spies.subList(1, spies.size())) {
            if (spy.getMission() != SpyMission.HOME) {
                continue;
            }
            espionageService.assign(empire,
                    new AssignSpyRequest("ai", spy.getId(), mission, target), context.turn());
            log.info("ИИ {} отправил агента с заданием {}", empire.getName(), mission);
        }
    }

    /**
     * Подчинение чужой колонии телепатами — п. 7, п. 12.
     * <p>
     * Телепату не нужен ни десант, ни бомбёжка: крупный корабль на орбите — и колония
     * меняет хозяина вместе с жителями. Для ИИ это прямая замена десанту, и без неё
     * сторона расы «телепаты» была неизмерима прогонами: путь к {@code mindControl} вёл
     * только из контроллера флота, то есть существовал для игрока и не существовал для
     * соседа.
     * <p>
     * Условия проверяются здесь, а не ловятся отказом: отказ прилетел бы изнутри
     * посчитанного хода и уронил бы его целиком.
     */
    private void mindControl(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                             FleetEntity fleet, StarSystemEntity system) {
        RaceEffects race = galaxy.races().get(empire.getId());
        if (race == null || !Boolean.TRUE.equals(race.telepathic()) || !bigShip(galaxy, fleet)) {
            return;
        }
        // Самая людная из тех, что подчинению поддаются. Раньше бралась просто самая
        // людная: условий у подчинения было одно (крейсер), и отказать оно не могло.
        // Теперь их три, и последнее слово — за ExpeditionService.controllable: оборона
        // колонии и лидер-телепат её хозяина (п. 7, журнал п. 3.92). Спрашивается он ПО
        // УБЫВАНИЮ населения и только у своих же кандидатов — щит над лучшей колонией не
        // повод бросить соседнюю, а список тут короткий: это колонии одной системы.
        PlanetEntity target = context.colonies().stream()
                .filter(colony -> system.getId().equals(context.systemOf(colony)))
                .filter(colony -> colony.getOwnerPlayerId() != null)
                .filter(colony -> !empire.getId().equals(colony.getOwnerPlayerId()))
                .filter(colony -> colony.getPopulation() > 0)
                .filter(colony -> galaxy.stanceOf(empire.getId(), colony.getOwnerPlayerId())
                        == DiplomacyStance.WAR)
                .filter(colony -> !Boolean.TRUE.equals(
                        galaxy.attackForbidden(empire.getId(), colony.getOwnerPlayerId())))
                .sorted(Comparator.comparing(PlanetEntity::getPopulation).reversed()
                        .thenComparing(PlanetEntity::getName))
                .filter(colony -> Boolean.TRUE.equals(expeditionService.controllable(colony)))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }

        expeditionService.mindControl(empire, fleet.getId(), target.getId(),
                context.turn(), context.game().getId());
        context.report().add(empire.getId(), "MIND_CONTROL",
                new MessageKey("turn.ai.mindControlled", target.getName()),
                target.getStarSystem().getId(), target.getId());
        log.info("ИИ {} подчинил колонию {} телепатией", empire.getName(), target.getName());
    }

    /** Есть ли во флоте корабль не ниже крейсера — условие подчинения телепатами (п. 7). */
    private Boolean bigShip(Galaxy galaxy, FleetEntity fleet) {
        return galaxy.composition().getOrDefault(fleet.getId(), List.of()).stream()
                .filter(row -> row.getShips() > 0)
                .map(row -> galaxy.designs().get(row.getDesignId()))
                .filter(Objects::nonNull)
                .anyMatch(design -> shipDesignService.hullSize(design)
                        >= ExpeditionService.MIND_CONTROL_HULL_SIZE);
    }

    /** Высадка десанта с транспортов флота — п. 12. */
    private void land(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                      FleetEntity fleet, PlanetEntity planet) {
        GroundCombatService.Outcome outcome = expeditionService.invade(
                empire, fleet.getId(), planet.getId(), context.turn(), context.game().getId());
        context.report().add(empire.getId(), "INVASION",
                new MessageKey(Boolean.TRUE.equals(outcome.captured())
                        ? "turn.ai.invasionWon"
                        : "turn.ai.invasionLost", planet.getName()),
                planet.getStarSystem().getId(), planet.getId());
        log.info("ИИ {} высадил десант на {}: {}", empire.getName(), planet.getName(),
                Boolean.TRUE.equals(outcome.captured()) ? "взята" : "отбит");
    }

    /**
     * Куда высаживать десант в этой системе — п. 12.
     * <p>
     * Годится только колония врага, с которым идёт война и которого не защищает договор,
     * и только если десанта хватает с перевесом: наземный бой считается силами сторон, и
     * слабый десант просто гибнет.
     */
    private Optional<PlanetEntity> invasionTarget(TurnContext context, Galaxy galaxy,
                                                  PlayerEntity empire, StarSystemEntity system,
                                                  Integer troops) {
        if (troops <= 0) {
            return Optional.empty();
        }
        return system.getPlanets().stream()
                .filter(planet -> planet.getOwnerPlayerId() != null)
                .filter(planet -> !planet.getOwnerPlayerId().equals(empire.getId()))
                .filter(planet -> planet.getPopulation() > 0)
                .filter(planet -> galaxy.stanceOf(empire.getId(), planet.getOwnerPlayerId())
                        == DiplomacyStance.WAR)
                .filter(planet -> !Boolean.TRUE.equals(
                        galaxy.attackForbidden(empire.getId(), planet.getOwnerPlayerId())))
                // БАРЬЕРНЫЙ ЩИТ НЕ ПУСКАЕТ ДЕСАНТ ВОВСЕ — п. 11, п. 12, и спросить об этом
                // надо ЗАРАНЕЕ: отказ прилетел бы изнутри посчитанного хода и уронил бы
                // ход целиком. Так и вышло на круге 7 — замер оборвался на 140-й партии из
                // пятисот. Правило живёт в GroundCombatService.landable, рядом с тем самым
                // местом, которое отказывает.
                .filter(planet -> Boolean.TRUE.equals(
                        groundCombatService.landable(planet, context.colonyContext())))
                .filter(planet -> worthLanding(context, empire, planet, troops))
                .max(Comparator.comparing(PlanetEntity::getPopulation));
    }

    /**
     * Хватит ли этого десанта на эту колонию — п. 12.
     * <p>
     * Обе силы считает {@link GroundCombatService#strength} — тот самый, что потом и
     * решит бой: второго свода правил наземного боя в проекте нет, и оценка ИИ обязана
     * считаться тем же способом, что и сам бой. Иначе ИИ водил бы десанты в заведомо
     * проигранные высадки, а понять это можно было бы только по счётчику захватов.
     * <p>
     * Всё нужное лежит в контексте хода: изученное обеими сторонами, расы и постройки
     * колонии. Своих выборок фаза не делает — см. «Грабли» в CLAUDE.md.
     */
    private Boolean worthLanding(TurnContext context, PlayerEntity empire,
                                 PlanetEntity target, Integer troops) {
        ColonyService.ColonyContext colony = context.colonyContext();
        RaceEffects mine = colony.raceEffectsByOwner()
                .getOrDefault(empire.getId(), RaceEffects.NONE);
        RaceEffects theirs = colony.raceEffectsByOwner()
                .getOrDefault(target.getOwnerPlayerId(), RaceEffects.NONE);

        Integer attack = groundCombatService.strength(troops, mine, 0, Boolean.FALSE,
                GroundCombatTech.attackPercent(colony.technologiesByOwner()
                        .getOrDefault(empire.getId(), Set.of())));
        Integer defence = groundCombatService.strength(target.getPopulation(), theirs, 0, Boolean.TRUE,
                GroundCombatTech.defencePercent(colony.technologiesByOwner()
                        .getOrDefault(target.getOwnerPlayerId(), Set.of()))
                        + colony.effects(target).amounts()
                                .getOrDefault(BuildingEffectType.GROUND_DEFENCE_PERCENT, 0));

        return attack * 100 >= defence * INVASION_ADVANTAGE_PERCENT;
    }

    /**
     * Стройка колоний империи — п. 10.
     * <p>
     * Переставляется только то, что стоит без дела: пустая стройка и товары. Начатое
     * здание ИИ не бросает — накопленное производство перешло бы в новую стройку, но
     * бросать начатое каждый ход значило бы не строить ничего.
     */
    private void projects(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        List<PlanetEntity> colonies = context.coloniesOf(empire.getId());
        if (colonies.isEmpty()) {
            return;
        }
        Boolean atWar = atWar(galaxy, empire);
        // Границу считаем ОДИН РАЗ на империю, а не на колонию: перебор «каждая моя
        // колония с каждой чужой» внутри посчитанного хода стоит дороже, чем кажется.
        Set<UUID> border = borderSystems(context, galaxy, empire);
        Set<UUID> alarm = alarmSystems(context, galaxy, empire);

        // Изученное — единственное, что открывает колонии НОВОЕ: здание, корпус, особый
        // проект. Число технологий империи и есть отметка, по которой колония понимает,
        // изменился ли список стройки с тех пор, как она смотрела его в последний раз.
        int known = context.colonyContext().technologiesByOwner()
                .getOrDefault(empire.getId(), Set.of()).size();

        // Грузовой флот считается ОДИН РАЗ на империю — п. 4.1.1 (журнал, п. 3.100): это
        // величина империи, а не колонии, и спрашивать её у каждой значило бы пересобирать
        // таблицы еды по три десятка раз за ход.
        Integer freightersWanted = freightersWanted(context, empire);
        // И закладывает грузовик ОДНА колония за ход. Без этого предела за грузовики
        // взялись бы разом все свободные — цель-то у них общая, — и империя на несколько
        // ходов перестала бы строить что-либо ещё.
        boolean freighterOrdered = false;

        for (PlanetEntity colony : colonies) {
            // КОЛОНИЯ НА ДОМАХ ТОЖЕ ПЕРЕСМАТРИВАЕТ ДЕЛО — п. 4.1.1 (журнал, п. 3.102).
            // Дома бесконечны, и «без дела» колония с ними не окажется никогда: выйти из
            // них нечем, кроме как подрасти. Поэтому условие выхода тут не технология, а
            // само население — пока расти есть куда, колония строит дома и не отвлекается;
            // переросла половину вместимости — возвращается в общую очередь дел.
            if (ColonyProject.HOUSING.equals(colony.getProjectCode())) {
                if (Boolean.TRUE.equals(roomToGrow(context, colony))) {
                    continue;
                }
            } else if (colony.getProjectCode() != null
                    && !ColonyProject.TRADE_GOODS.equals(colony.getProjectCode())) {
                continue;
            }
            // Эта колония уже смотрела список при том же числе технологий и не нашла
            // ничего — значит, не найдёт и сейчас. След лежит у КОЛОНИИ, а не у империи:
            // достроив здание, колония переходит на товары, то есть выглядит свободной, и
            // признак на уровне империи пропускал бы как раз тех, кому дело нужно.
            if (Objects.equals(colony.getAiIdleTech(), known)) {
                continue;
            }
            Map<String, ColonyProjectDto> available = colonyService
                    .available(colony, context.colonyContext()).stream()
                    .collect(Collectors.toMap(ColonyProjectDto::code, project -> project,
                            (first, second) -> first));
            String choice = choose(context, galaxy, empire, colony, available, atWar,
                    border.contains(context.systemOf(colony)),
                    alarm.contains(context.systemOf(colony)),
                    !freighterOrdered && empire.getFreighters() < freightersWanted);
            if (ColonyProject.FREIGHTER.equals(choice)) {
                freighterOrdered = true;
            }
            if (choice == null || choice.equals(colony.getProjectCode())) {
                // Посмотрела и не нашла: до новой технологии список ей пересматривать
                // незачем.
                colony.setAiIdleTech(known);
                continue;
            }
            UUID designId = ColonyProject.shipDesign(choice);
            colony.setProjectCode(designId == null ? choice : ColonyProject.SHIP);
            colony.setProjectDesignId(designId);
            colony.setAiIdleTech(null);
            log.debug("ИИ {}: колония {} строит {}", empire.getName(), colony.getName(), choice);
        }

        buyProject(context, empire, colonies);
    }

    /**
     * Выкуп стройки за кредиты — п. 10: ИИ докупает недостающее производство, как игрок.
     * <p>
     * <b>Зачем.</b> Деньги в игре есть, а девать их империи ИИ было некуда: казна росла и
     * лежала. От этого страдал не только ИИ, но и прибор балансировки — мерило денег
     * (этап 2) меряет доход за ход, а доход, который не во что превратить, ничего не
     * стоит: «богатые» за одиннадцать очков выходили силой +1,09, то есть почти нулём.
     * Механика, к которой у ИИ нет пути, для замера мертва — та же история, что была с
     * разведкой, телепатами и наземным боем.
     * <p>
     * <b>Правило покупки — реконструкция, и вот её смысл.</b> Оригинал говорит, что ИИ
     * выкупает, но не говорит когда. Взято то, как выкупает понимающий игрок:
     * <ul>
     *   <li>ОДНА покупка за ход на всю империю: казна общая, и спустить её за ход на пять
     *       колоний сразу значило бы остаться без содержания зданий;</li>
     *   <li>ПОЛОВИНА казны — потолок цены: так резерв остаётся сам собой, без отдельного
     *       числа, и растёт вместе с империей;</li>
     *   <li>берётся САМАЯ ДЕШЁВАЯ покупка: цена тем ниже, чем больше уже вложено (два
     *       кредита за единицу с половины стройки, четыре до неё —
     *       {@link PopulationCalculator#buyCost}), поэтому дешевле всего дотолкнуть почти
     *       готовое. Это же и самый выгодный размен кредитов на ходы.</li>
     * </ul>
     * Правила самой покупки здесь не повторяются: цену и списание считает
     * {@link ColonyService#buyFromTurn} — тот же путь, каким выкупает игрок.
     */
    private void buyProject(TurnContext context, PlayerEntity empire, List<PlanetEntity> colonies) {
        if (empire.getCredits() <= 0) {
            return;
        }
        // ХОЗЯЙСТВО ВЫКУПАЕТСЯ ПЕРВЫМ И НА ВСЮ КАЗНУ — п. 10 (журнал, п. 3.74).
        PlanetEntity best = cheapest(context, colonies, empire.getCredits(), Kind.ECONOMY);
        // ОБОРОНА ПОД УГРОЗОЙ — ВТОРОЙ ОЧЕРЕДЬЮ И ТОЖЕ НА ВСЮ КАЗНУ (журнал, п. 3.94).
        // Деньги на то и нужны, чтобы успеть: станция, достроенная на ход позже десанта, не
        // стоит ничего. Правило общее с хозяйством — выкупается самое дешёвое из заложенного,
        // то есть ближайшее к готовности, а не самое грозное.
        if (best == null) {
            best = cheapest(context, colonies, empire.getCredits(), Kind.DEFENCE);
        }
        if (best == null) {
            best = cheapest(context, colonies, empire.getCredits() / 2, Kind.ANY);
        }
        if (best != null) {
            colonyService.buyFromTurn(empire, best, context.colonyContext());
        }
    }

    /**
     * Самая дешёвая покупка из доступных; {@code foundationOnly} — только хозяйство.
     * <p>
     * <b>Зачем разделение.</b> Правило «бери самую дешёвую» считает ОСТАТОК, а не цену вещи,
     * и потому систематически выбирало корабли: свежий завод (60 единиц, ничего не вложено)
     * стоит 240 кредитов, а колониальный корабль, достроенный на 460 из 500, — восемьдесят.
     * Корабль и правда выгоднее за кредит, и правило работало ровно как задумано. Измерено
     * летописью (журнал, п. 3.74): за 150 ходов ВСЕ ТРИНАДЦАТЬ покупок ушли в корабли, ни
     * одного здания; сила флота составила 2237 из 2649 мощи, и флот этот не воевал ни разу.
     * Империя обращала всю свою экономику в неподвижный флот.
     * <p>
     * <b>Потолок у хозяйства — вся казна, а не половина.</b> Половина оставалась резервом на
     * содержание зданий, но завод и лаборатория ДОХОД ПОДНИМАЮТ: купив их, империя бережёт
     * резерв, а не проедает. Прежний потолок требовал накопить 480 кредитов ради завода за
     * 240 — сорок ходов при доходе двенадцать, тогда как сам завод окупается за шесть ходов
     * производства. Риск при этом ограничен самим списком: в нём два здания по шестьдесят
     * единиц, дороже 240 кредитов такая покупка не бывает.
     */
    /** Что именно выкупаем: хозяйство, оборону или что угодно. */
    private enum Kind { ECONOMY, DEFENCE, ANY }

    /**
     * Годится ли эта стройка под такой выкуп.
     * <p>
     * Хозяйством считается то, что окупается по {@link #paybackTurns} и стоит не дороже
     * {@link #FULL_TREASURY_COST}: на всю казну берут дешёвое и быстро окупаемое, а
     * глубинная шахта за 250 единиц пусть ждёт общего правила половины.
     */
    private Boolean suits(PlanetEntity colony, Kind kind) {
        if (kind == Kind.ANY) {
            return Boolean.TRUE;
        }
        String code = colony.getProjectCode();
        if (kind == Kind.DEFENCE) {
            return DEFENCE.contains(code);
        }
        Building building = buildingCatalog.byCode().get(code);
        if (building == null || building.cost() > FULL_TREASURY_COST) {
            return Boolean.FALSE;
        }
        Integer turns = paybackTurns(building, colony.getJobs());
        return turns != null && turns <= PAYBACK_TURNS;
    }

    private PlanetEntity cheapest(TurnContext context, List<PlanetEntity> colonies,
                                  Integer limit, Kind kind) {
        if (limit <= 0) {
            return null;
        }
        PlanetEntity best = null;
        Integer bestPrice = null;
        for (PlanetEntity colony : colonies) {
            if (!Boolean.TRUE.equals(suits(colony, kind))) {
                continue;
            }
            Integer price = colonyService.buyPrice(colony, context.colonyContext());
            if (price == null || price > limit) {
                continue;
            }
            if (bestPrice == null || price < bestPrice
                    // При равной цене выбор должен быть определённым, а не случайным:
                    // партия обязана повторяться до последнего числа (этап 0).
                    //
                    // ПО НАЗВАНИЮ, А НЕ ПО ИДЕНТИФИКАТОРУ, и это не придирка: id планеты —
                    // случайный UUID, который Hibernate выдаёт при записи строки, и во
                    // втором прогоне той же партии он выпадет другим. Ничья решалась
                    // определённо ВНУТРИ прогона и по-разному МЕЖДУ прогонами. Поймано
                    // сверкой по колониям: на 67-м ходу две только что основанные колонии
                    // одной системы (Alioth IV и Alioth V) стоили одинаково, и выкуп
                    // доставался то одной, то другой — дальше партии расходились целиком.
                    // Название планеты собрано из имени звезды и номера орбиты, выводится
                    // из зерна партии и уникально.
                    || (price.equals(bestPrice)
                            && colony.getName().compareTo(best.getName()) < 0)) {
                best = colony;
                bestPrice = price;
            }
        }
        return best;
    }

    /**
     * Что строить этой колонии — реконструкция поведения ИИ MOO II.
     * <p>
     * Порядок такой: сперва спасти колонию от голода, потом занять свободные планеты
     * (колониальная база в своей системе дешевле корабля вдвое с половиной и потому идёт
     * первой), потом обзавестись флотом, если идёт война, и только затем строить здания
     * по плану развития. Товары — ответ на «строить больше нечего», как и у человека.
     */
    private String choose(TurnContext context, Galaxy galaxy, PlayerEntity empire, PlanetEntity colony,
                          Map<String, ColonyProjectDto> available, Boolean atWar,
                          Boolean border, Boolean alarm, Boolean wantFreighter) {
        if (colonyService.foodLack(colony, context.colonyContext()) > 0) {
            String food = first(FOOD, available);
            if (food != null) {
                // ЕДА ИДЁТ МИМО ТОРМОЗА ПО СОДЕРЖАНИЮ, и нарочно: голод убивает жителей, а
                // с ними и доход, из которого содержание платится. Экономить на еде —
                // способ разориться быстрее, а не медленнее.
                return food;
            }
        }

        // ГРУЗОВИК — п. 4.1.1 (журнал, п. 3.100), и стоит он здесь, рядом с едой, потому
        // что это та же еда, только чужая: без грузового флота излишек кормовых миров
        // некуда девать, и каждая колония вынуждена кормиться сама. Мимо тормоза по
        // содержанию он проходит по той же причине, что и ферма, — и заодно потому, что
        // содержания у него нет вовсе: грузовик не здание.
        if (Boolean.TRUE.equals(wantFreighter) && available.containsKey(ColonyProject.FREIGHTER)) {
            return ColonyProject.FREIGHTER;
        }

        // ТОРМОЗ ПО СОДЕРЖАНИЮ — п. 10 (журнал, п. 3.96). Дальше по цепочке колония не
        // увидит того, чего империи не прокормить: правило стоит ОДНИМ местом здесь, а не
        // повторяется в каждой ветке, потому что забыть его в одной из семи — вопрос
        // времени. Корабли через него не проходят: их содержание — командные очки, а не
        // кредиты, и меряется оно другим правилом (CommandRules).
        Map<String, Building> catalog = buildingCatalog.byCode();
        Map<String, ColonyProjectDto> affordable = available.entrySet().stream()
                .filter(entry -> {
                    Building building = catalog.get(entry.getKey());
                    return building == null
                            || Boolean.TRUE.equals(affordable(context, empire, building));
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first, LinkedHashMap::new));
        available = affordable;
        // Беззащитная чужая колония под боком — повод отложить мирные дела: десант
        // строится, пока цель есть, а не после того, как кончится всё остальное (п. 12).
        // Раньше транспорт стоял ниже зданий, колониальных кораблей и застав, и империя
        // добиралась до него только когда строить было больше нечего.
        if (Boolean.TRUE.equals(atWar)) {
            String assault = assault(context, galaxy, empire, available);
            if (assault != null) {
                return assault;
            }
        }

        // ХОЗЯЙСТВО, КОТОРОЕ ОКУПАЕТСЯ БЫСТРЕЕ РАССЕЛЕНИЯ, — п. 10 (журнал, п. 3.94).
        // Здесь стоял список из двух зданий; теперь на его месте сравнение, и оно впускает
        // сюда любое здание справочника, которое ЭТОЙ колонии вернёт цену за двадцать ходов.
        // Список остался ниже, в развитии: там ждут своей очереди те, что пока не окупаются.
        // Основание остаётся списком, и это не пережиток. Завод и лаборатория стоят по
        // шестьдесят единиц и нужны КАЖДОЙ колонии, даже пустой: на колонии без рабочих
        // завод окупается за двенадцать ходов, то есть не проходит порог бесспорного — а
        // строить его всё равно надо, потому что ничего лучше у такой колонии нет вовсе.
        // Измерено попыткой обойтись одним правилом: заводов стало 11 вместо 32,
        // лабораторий 8 вместо 30, а голых колоний — 82 % вместо 61 %. Правило хорошо
        // говорит, что ДОБАВИТЬ к основанию, и плохо — что основанием является.
        String foundation = first(FOUNDATION, available);
        if (foundation != null) {
            return foundation;
        }
        String obvious = worthwhile(colony, available, FAST_PAYBACK_TURNS);
        if (obvious != null) {
            return obvious;
        }
        // ТРЕВОГА: над колонией стоит враг или чужой десант — п. 8, п. 12 (журнал, п. 3.94).
        // Единственная оборонительная ветка выше расселения, и список тут полный, военный:
        // выбирать между колониальным кораблём и станцией, когда транспорты уже на орбите,
        // не приходится вовсе.
        if (Boolean.TRUE.equals(alarm)) {
            String guard = first(DEFENCE, available);
            if (guard != null) {
                return guard;
            }
        }
        // РАЗВЕДКА — п. 13, и она стоит ЗДЕСЬ, выше расселения, нарочно.
        //
        // Стояла она в самом низу, после всех двадцати семи зданий развития, и намерение
        // было разумное: шпион ничего не производит, молодой империи он не по карману. На
        // деле очередь до него не доходила НИКОГДА — и не из-за зданий, а из-за
        // расселения: колониальная база, колониальный корабль или застава находятся у
        // растущей империи почти всегда, и решение принималось выше по цепочке. Замер
        // живой партии: НОЛЬ построенных агентов и ноль заданий за 150 ходов у восьми
        // империй. Прибор балансировки мерил не слабость «шпионов», а то, что игра их не
        // трогала, — та же история, что была с наземным боем, защитой кораблей и
        // договорами.
        //
        // Исключение узкое и ограниченное двумя агентами: первый по правилам разведки
        // сидит дома контрразведкой, и заданий не выполняет вовсе, поэтому для живого
        // мерила нужен второй. Дальше разведка снова уходит в самый низ — набирать третьего
        // колония будет, только когда строить ей больше нечего. Берётся за них СТОЛИЦА: у
        // провинций свои дела, а предел SPIES_AT_ONCE иначе выбрали бы все разом.
        if (Boolean.TRUE.equals(colony.getHomeworld())
                && available.containsKey(ColonyProject.SPY)
                && !galaxy.knownRivals(empire.getId()).isEmpty()
                && galaxy.spiesOf(empire.getId()).size() < SPIES_FROM_CAPITAL) {
            return ColonyProject.SPY;
        }

        if (available.containsKey(ColonyProject.COLONY_BASE)) {
            return ColonyProject.COLONY_BASE;
        }
        // Воюющая колония ставит оборону раньше кораблей и раньше расселения — п. 12.
        // Расширяться, теряя колонии, — худший из разменов: см. javadoc MARINE_BARRACKS.
        if (Boolean.TRUE.equals(atWar)) {
            String defence = first(DEFENCE, available);
            if (defence != null) {
                return defence;
            }
        }
        // Совсем без флота империя не живёт даже в мире: первый же чужой корабль отнимает
        // у неё колонию, а встречи флотов (п. 8) без кораблей не случаются вовсе. Один
        // боевой корабль поэтому строится раньше колониального: тот стоит впятеро дороже
        // и оставил бы колонию беззащитной на десятки ходов. Остальной гарнизон — потом,
        // когда строить больше нечего.
        if (galaxy.owned(empire.getId(), ShipRole.WARSHIP) == 0) {
            String first = warship(available);
            if (first != null) {
                return first;
            }
        }

        Set<UUID> reachable = reachable(context, empire);
        if (available.containsKey(ColonyProject.COLONY_SHIP)
                && galaxy.owned(empire.getId(), ShipRole.COLONY) < COLONY_SHIPS_AT_ONCE
                && !settleTargets(context, galaxy, empire, reachable).isEmpty()) {
            return ColonyProject.COLONY_SHIP;
        }
        // Селиться некуда — значит, пора раздвигать дальность: застава ставится на любую
        // планету, и от неё империя дотянется до следующих звёзд (п. 8).
        if (available.containsKey(ColonyProject.OUTPOST_SHIP)
                && galaxy.owned(empire.getId(), ShipRole.OUTPOST) < OUTPOST_SHIPS_AT_ONCE
                && !outpostTargets(context, galaxy, empire, reachable).isEmpty()) {
            return ColonyProject.OUTPOST_SHIP;
        }
        if (Boolean.TRUE.equals(atWar)) {
            String military = military(context, galaxy, empire, available);
            if (military != null) {
                return military;
            }
        }

        // ГРАНИЦА С ОПАСНЫМ СОСЕДОМ — п. 15 (журнал, п. 3.94): оборона строится ДО войны,
        // а не после её объявления. Стоит ветка НИЖЕ расселения нарочно: война — это повод
        // бросить всё (там свой список выше), а сосед под боком — повод не оставлять границу
        // голой, но не повод перестать расти. Иначе империя на большой галактике, где соседи
        // видны почти всем, застроила бы оборону вместо колоний.
        if (Boolean.TRUE.equals(border)) {
            String guard = first(BORDER_DEFENCE, available);
            if (guard != null) {
                return guard;
            }
        }

        // Хозяйство, которое окупается не так быстро, но всё же окупается, — п. 10.
        // Расселение уже получило своё выше, и дальше колония вкладывается в себя, а не
        // ждёт, пока кончатся свободные планеты во всей галактике.
        String worthwhile = worthwhile(colony, available, PAYBACK_TURNS);
        if (worthwhile != null) {
            return worthwhile;
        }

        String building = first(DEVELOPMENT, available);
        if (building != null) {
            return building;
        }

        // ДОМА — п. 4.1.1 (журнал, п. 3.102), вторая половина расселения по-человечески.
        //
        // `HOUSING` не встречался в этой службе НИ РАЗУ: колония ИИ росла только сама собой.
        // А дома в MOO II и есть тот способ, каким производство обращается в жителей, и
        // нужны они там, где расти есть куда: прирост наибольший у полупустой колонии, а у
        // забитой он нулевой — той нужен рейс отсюда, а не новые жители.
        //
        // <b>Стоят они В САМОМ НИЗУ, и это не вкусовщина, а дважды выученный урок.</b>
        // Во-первых, дома — стройка БЕСКОНЕЧНАЯ, колония с ними не освобождается никогда:
        // поставленные выше завода, они на первом же ходу забрали все восемь родных миров и
        // продержали их триста ходов без единой постройки (ноль грузовиков, казна вдесятеро
        // против обычной — деньги копились, потому что их некуда было девать). Во-вторых,
        // поставленные хотя бы выше списка развития, они снова похоронили бы двадцать пять
        // зданий из двадцати семи — ту самую беду, которую чинил п. 3.94: у растущей империи
        // «есть куда расти» почти у каждой колонии почти всегда.
        // Отсюда место: сперва основание, расселение и ВСЁ, что окупается, — а дома
        // достаются тому, у кого дел получше не осталось вовсе. Это ровно бедная планета, и
        // её работа в империи как раз такая: растить людей для хороших миров.
        if (available.containsKey(ColonyProject.HOUSING)
                && Boolean.TRUE.equals(roomToGrow(context, colony))) {
            return ColonyProject.HOUSING;
        }

        // Провинции берутся за агентов, только когда строить им больше нечего.
        if (available.containsKey(ColonyProject.SPY)
                && !galaxy.knownRivals(empire.getId()).isEmpty()
                && galaxy.spiesOf(empire.getId()).size() < SPIES_AT_ONCE) {
            return ColonyProject.SPY;
        }

        // Строить больше нечего — значит, пора думать об обороне (п. 8). Товары остаются
        // ответом только тогда, когда и корабля не поднять: колония, годами делающая
        // товары при пустом небе, — это не мирная империя, а беззащитная.
        String garrison = garrison(galaxy, empire, available, atWar);
        return garrison == null ? ColonyProject.TRADE_GOODS : garrison;
    }

    /**
     * Оборонительный флот — п. 8.
     * <p>
     * В мире по кораблю на колонию: этого хватает, чтобы встретить чужой флот и не отдать
     * колонию первому же десанту. На войне вдвое больше — не ради запаса, а чтобы было чем
     * идти в поход: гарнизон из колонии не уходит никуда, и норма мирного времени
     * оставляла империю без единого свободного корабля. Обе нормы укладываются в командные
     * очки империи (колония даёт два, фрегат занимает одно — {@link CommandRules}), поэтому
     * казну флот не проедает.
     */
    private String garrison(Galaxy galaxy, PlayerEntity empire,
                            Map<String, ColonyProjectDto> available, Boolean atWar) {
        Integer warships = galaxy.owned(empire.getId(), ShipRole.WARSHIP);
        Integer colonies = galaxy.coloniesOf(empire.getId());
        Integer quota = Boolean.TRUE.equals(atWar)
                ? WARSHIPS_PER_COLONY_AT_WAR
                : WARSHIPS_PER_COLONY_IN_PEACE;
        if (warships >= colonies * quota) {
            return null;
        }
        return warship(available);
    }

    /**
     * Десант под настоящую цель — п. 12: транспорты строятся, когда есть кого брать.
     * <p>
     * Целью считается колония врага, до которой империя дотягивается и которую её десант
     * способен взять с перевесом (п. 12). <b>Беззащитную берут и без прикрытия:</b> если в
     * системе цели нет чужого боевого флота, транспорту нечего бояться, и ждать
     * {@link #WARSHIPS_BEFORE_TRANSPORTS} кораблей незачем — в MOO II гражданский корабль
     * гибнет от первого нападения, но нападать там некому. Защищённую цель без флота не
     * берут: транспорт до неё не долетит.
     *
     * @return проект транспорта или {@code null} — цели нет, десанта уже хватает или
     *         строить транспорт нечем
     */
    private String assault(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                           Map<String, ColonyProjectDto> available) {
        if (!available.containsKey(ColonyProject.TRANSPORT)) {
            return null;
        }
        Integer transports = galaxy.owned(empire.getId(), ShipRole.TRANSPORT);
        if (transports >= TRANSPORTS_AT_ONCE) {
            return null;
        }

        PlanetEntity target = invasionChance(context, galaxy, empire);
        if (target == null) {
            return null;
        }
        if (Boolean.TRUE.equals(guarded(context, galaxy, empire, target))
                && galaxy.owned(empire.getId(), ShipRole.WARSHIP) < WARSHIPS_BEFORE_TRANSPORTS) {
            return warship(available);
        }

        Integer needed = target.getPopulation() * INVASION_ADVANTAGE_PERCENT / 100 + 1;
        return transports * ColonyProject.TRANSPORT_TROOPS < needed
                ? ColonyProject.TRANSPORT
                : null;
    }

    /**
     * Самая слабая чужая колония, которую империя и правда может взять, — п. 12.
     * <p>
     * «Может» это три условия разом: до неё дотягивается флот, с её хозяином идёт война и
     * договор не связывает руки, и её население по силам тому десанту, который империя
     * вообще способна собрать ({@link #TRANSPORTS_AT_ONCE} транспортов). Колония, которую
     * не взять и полным трюмом, целью не считается — копить под неё транспорты значит
     * стоять без стройки.
     */
    private PlanetEntity invasionChance(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        return invasionChance(context, galaxy, empire,
                TRANSPORTS_AT_ONCE * ColonyProject.TRANSPORT_TROOPS);
    }

    /** Телепатична ли раса империи — п. 7: от этого зависит, нужен ли ей десант вовсе. */
    private Boolean telepathic(Galaxy galaxy, PlayerEntity empire) {
        RaceEffects race = galaxy.races().get(empire.getId());
        return race != null && Boolean.TRUE.equals(race.telepathic());
    }

    /**
     * Та же цель, но с заданным запасом десанта.
     * <p>
     * Телепату запас не нужен вовсе ({@link Integer#MAX_VALUE}): он берёт колонию
     * подчинением, а не боем, и упираться в число транспортов ему незачем — п. 7.
     */
    private PlanetEntity invasionChance(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                                        Integer mostTroops) {
        Set<UUID> reachable = reachable(context, empire);
        return context.colonies().stream()
                .filter(colony -> colony.getOwnerPlayerId() != null)
                .filter(colony -> !empire.getId().equals(colony.getOwnerPlayerId()))
                .filter(colony -> colony.getPopulation() > 0)
                .filter(colony -> reachable.contains(context.systemOf(colony)))
                // Идти войной на колонию, которой не видел, нельзя.
                .filter(colony -> Boolean.TRUE.equals(
                        galaxy.knows(empire.getId(), context.systemOf(colony))))
                .filter(colony -> galaxy.stanceOf(empire.getId(), colony.getOwnerPlayerId())
                        == DiplomacyStance.WAR)
                .filter(colony -> !Boolean.TRUE.equals(
                        galaxy.attackForbidden(empire.getId(), colony.getOwnerPlayerId())))
                .filter(colony -> colony.getPopulation() * INVASION_ADVANTAGE_PERCENT / 100
                        < mostTroops)
                .min(Comparator.comparing(PlanetEntity::getPopulation))
                .orElse(null);
    }

    /** Стоит ли в системе цели чужой боевой флот: от этого зависит, нужно ли прикрытие. */
    private Boolean guarded(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                            PlanetEntity target) {
        UUID system = context.systemOf(target);
        return galaxy.fleets().stream()
                .filter(fleet -> !fleet.getOwnerPlayerId().equals(empire.getId()))
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .filter(fleet -> system.equals(fleet.getStarSystemId()))
                // Скрытный флот разведке не виден: система кажется пустой, и ИИ пойдёт
                // туда без прикрытия — в этом и состоит сторона расы (п. 7).
                .filter(fleet -> Boolean.TRUE.equals(galaxy.sees(empire.getId(), fleet)))
                .anyMatch(fleet -> galaxy.count(fleet, ShipRole.WARSHIP) > 0);
    }

    /**
     * Чего империи не хватает для её же замысла — п. 15: по этому списку ИИ и выбирает,
     * что изучать ({@code ResearchService.autoChoose}).
     * <p>
     * Порядок нужд — порядок замысла. Сперва расселение: без колониального корабля империя
     * не выйдет за родную звезду вовсе, а прогоны показали, что вкус правителя доводит до
     * Power только двоих устремлений из шести — остальные сидели на одной колонии всю
     * партию. Дальше застава (она раздвигает дальность там, где селиться некуда) и
     * транспорт — но транспорт только тогда, когда война уже идёт: изучать десант в мирной
     * галактике незачем.
     * <p>
     * Считается по тому, что уже загружено ходом: изученное лежит в контексте колоний, а
     * воюет ли империя, спрашивает фаза исследований одной выборкой на партию — запрос
     * «на игрока» внутри посчитанного хода стоит дороже, чем кажется.
     *
     * @param known изученные империей технологии
     * @param atWar идёт ли у неё хоть одна война
     */
    public List<String> wantedTechnologies(Set<String> known, Boolean atWar) {
        List<String> wanted = new ArrayList<>(3);
        if (!known.contains(ColonyProject.COLONY_SHIP_TECH)) {
            wanted.add(ColonyProject.COLONY_SHIP_TECH);
        }
        if (!known.contains(ColonyProject.OUTPOST_SHIP_TECH)) {
            wanted.add(ColonyProject.OUTPOST_SHIP_TECH);
        }
        if (Boolean.TRUE.equals(atWar) && !known.contains(ColonyProject.TRANSPORT_TECH)) {
            wanted.add(ColonyProject.TRANSPORT_TECH);
        }
        // Хозяйство идёт ПОСЛЕ расселения, но раньше вкуса: колониальный корабль стоит 500
        // единиц, и строить его без завода значит строить втрое дольше. Зато и откладывать
        // расселение ради завода нельзя — пока империя сидит на родной звезде, ей не с кем
        // ни воевать, ни меняться (см. ECONOMY_TECHNOLOGIES).
        for (String technology : ECONOMY_TECHNOLOGIES) {
            if (!known.contains(technology)) {
                wanted.add(technology);
            }
        }
        return wanted;
    }

    /**
     * Что строит воюющая империя — п. 8 и п. 12.
     * <p>
     * Сперва корабли прикрытия, потом транспорты: десант без флота гибнет, не долетев, —
     * гражданский корабль в MOO II уничтожается первым же нападением. Как транспортов
     * набралось на высадку, стройка снова уходит в боевые корабли.
     */
    private String military(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                            Map<String, ColonyProjectDto> available) {
        Integer transports = galaxy.owned(empire.getId(), ShipRole.TRANSPORT);
        Integer warships = galaxy.owned(empire.getId(), ShipRole.WARSHIP);

        if (warships < WARSHIPS_BEFORE_TRANSPORTS) {
            return warship(available);
        }
        Integer needed = troopsNeeded(context, galaxy, empire);
        if (available.containsKey(ColonyProject.TRANSPORT)
                && transports < TRANSPORTS_AT_ONCE
                && transports * ColonyProject.TRANSPORT_TROOPS < needed) {
            return ColonyProject.TRANSPORT;
        }
        return warship(available);
    }

    /**
     * Сколько бойцов нужно, чтобы взять хоть одну колонию врага, — п. 12.
     * <p>
     * Считается по самой слабой колонии, до которой империя дотягивается: копить десант
     * под самую сильную незачем — война начинается с окраин.
     */
    private Integer troopsNeeded(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        Set<UUID> reachable = reachable(context, empire);
        Integer weakest = context.colonies().stream()
                .filter(colony -> !empire.getId().equals(colony.getOwnerPlayerId()))
                .filter(colony -> reachable.contains(context.systemOf(colony)))
                // Идти войной на колонию, которой не видел, нельзя.
                .filter(colony -> Boolean.TRUE.equals(
                        galaxy.knows(empire.getId(), context.systemOf(colony))))
                .filter(colony -> galaxy.stanceOf(empire.getId(), colony.getOwnerPlayerId())
                        == DiplomacyStance.WAR)
                .map(PlanetEntity::getPopulation)
                .min(Comparator.naturalOrder())
                .orElse(0);
        return weakest * INVASION_ADVANTAGE_PERCENT / 100 + 1;
    }

    /** Самый сильный корабль, который колония может поднять: за него и берутся. */
    private String warship(Map<String, ColonyProjectDto> available) {
        return available.values().stream()
                .filter(project -> ColonyProject.isShip(project.code()))
                .max(Comparator.comparing(project -> project.cost() == null ? 0 : project.cost()))
                .map(ColonyProjectDto::code)
                .orElse(null);
    }

    /** Первый доступный код из списка предпочтений; ничего из списка нет — {@code null}. */
    private String first(List<String> preferred, Map<String, ColonyProjectDto> available) {
        return preferred.stream().filter(available::containsKey).findFirst().orElse(null);
    }

    /**
     * За сколько ходов здание вернёт колонии свою цену; {@code null} — не вернёт вовсе.
     * <p>
     * Считается по тем же парам «тип эффекта — количество», какими здание описано в
     * справочнике (п. 10): постоянная прибавка плюс прибавка с каждого работника или
     * учёного, которые на этой колонии УЖЕ есть. В этом весь смысл правила — здание
     * оценивается не вообще, а на той колонии, где его собираются ставить: суперкомпьютер
     * при трёх учёных окупается за девять ходов, а при нуле не окупается никогда.
     * <p>
     * Считаются только выработка и наука: это две величины, которые здание возвращает в той
     * же единице, в какой стоит, — в единицах производства. Деньги (космопорт, биржа) идут
     * процентом к доходу, еда — прибавкой к фермеру, и общего курса с производством у них
     * нет; такие здания остаются в общем списке развития и ждут своей очереди. Содержание в
     * расчёт тоже не идёт: оно платится кредитами, а цена стоит в производстве.
     * <p>
     * Метод СТАТИЧЕСКИЙ и открытый нарочно: это числовое правило, и проверяется оно
     * юнит-тестом без базы и без сервера ({@code AiPaybackTest}).
     */
    public static Integer paybackTurns(Building building, PopulationJobs jobs) {
        if (building == null || building.cost() == null || building.cost() <= 0) {
            return null;
        }
        PopulationJobs at = jobs == null ? PopulationJobs.NONE : jobs;
        int gain = amount(building, BuildingEffectType.PRODUCTION_FLAT)
                + amount(building, BuildingEffectType.RESEARCH_FLAT)
                + amount(building, BuildingEffectType.PRODUCTION_PER_WORKER) * at.workers()
                + amount(building, BuildingEffectType.RESEARCH_PER_SCIENTIST) * at.scientists();
        if (gain <= 0) {
            return null;
        }
        return Math.max(1, building.cost() / gain);
    }

    private static Integer amount(Building building, BuildingEffectType type) {
        Integer value = building.effects().get(type);
        return value == null ? 0 : value;
    }

    /**
     * Хозяйственное здание, которое окупится быстрее расселения, — п. 10, п. 15.
     * <p>
     * Из всего, что колония может построить, берётся самое быстроокупаемое, и только если
     * оно укладывается в {@link #PAYBACK_TURNS}. Это и есть сравнение, которого у ИИ не
     * было: прежде порядок задавался неизменным списком, и колония бралась за расселение,
     * даже когда рядом лежал завод, окупающийся за шесть ходов.
     * <p>
     * При равной окупаемости берётся ДЕШЁВОЕ: оно освободит стапель раньше, а колония тем
     * временем вырастет и оценит следующее здание уже по-новому. Ничья решается кодом
     * здания — партия обязана повторяться до последнего числа (этап 0).
     */
    /**
     * Потянет ли империя содержание этого здания — п. 10 (журнал, п. 3.96).
     * <p>
     * Два условия сразу: доход покрывает содержание, и в казне есть запас на
     * {@link #UPKEEP_RESERVE_TURNS} ходов этого содержания. Первое бережёт от медленного
     * сползания в долг, второе — от рывка: доход бывает и сегодняшним, а содержание платится
     * всегда. Бесплатному зданию не мешает ничто.
     */
    private Boolean affordable(TurnContext context, PlayerEntity empire, Building building) {
        Integer upkeep = building.upkeep() == null ? 0 : building.upkeep();
        if (upkeep <= 0) {
            return Boolean.TRUE;
        }
        Integer income = 0;
        for (PlanetEntity colony : context.coloniesOf(empire.getId())) {
            income += colonyService.income(colony, context.colonyContext());
        }
        return income >= upkeep && empire.getCredits() >= upkeep * UPKEEP_RESERVE_TURNS;
    }

    private String worthwhile(PlanetEntity colony, Map<String, ColonyProjectDto> available,
                              Integer horizon) {
        Map<String, Building> catalog = buildingCatalog.byCode();
        String best = null;
        Integer bestTurns = null;
        Integer bestCost = null;
        for (ColonyProjectDto project : available.values()) {
            Building building = catalog.get(project.code());
            Integer turns = paybackTurns(building, colony.getJobs());
            if (turns == null || turns > horizon) {
                continue;
            }
            if (bestTurns == null || turns < bestTurns
                    || (turns.equals(bestTurns) && building.cost() < bestCost)
                    || (turns.equals(bestTurns) && building.cost().equals(bestCost)
                            && building.code().compareTo(best) < 0)) {
                best = building.code();
                bestTurns = turns;
                bestCost = building.cost();
            }
        }
        return best;
    }

    /**
     * Стоит ли на границе с опасным соседом — п. 15 (журнал, п. 3.94).
     * <p>
     * <b>Чего не было.</b> Оборону запускало единственное условие — объявленная война. Ни
     * чужого флота над колонией, ни сильного соседа под боком ИИ не замечал вовсе: замер
     * живой партии показал за 200 ходов ДВЕ ракетные базы на восемь империй, и те из общего
     * списка развития. Сторона расы, живущая обороной, в таком замере молчит.
     * <p>
     * Опасным сосед считается по двум приметам сразу: он не слабее нас
     * ({@link #THREAT_MIGHT_PERCENT}) и его колония стоит ближе {@link #THREAT_PARSECS}
     * парсеков. Знакомство при этом не требуется — чужая колония под боком видна и без
     * переговоров, — а вот РАЗВЕДКА требуется: о системе, которой империя не видела, она
     * не знает ничего (п. 15).
     * <p>
     * Считается ОДИН РАЗ НА ИМПЕРИЮ, набором систем: колоний у неё десятки, соседских
     * столько же, и перебор «каждая с каждой» на каждую колонию обошёлся бы дороже самой
     * фазы.
     */
    /**
     * Свои системы, над которыми УЖЕ стоит опасный чужой флот, — п. 8, п. 15.
     * <p>
     * Это не «потенциальная угроза», а тревога: гость либо воюет с нами, либо привёз
     * десант. Мимо идущий разведчик соседа, с которым мы в мире, тревогой не считается —
     * иначе колония бросала бы расселение всякий раз, когда над ней кто-то пролетел, и
     * оборона строилась бы вместо игры. Ровно по этой причине ветка тревоги — единственная
     * оборонительная, что стоит ВЫШЕ расселения: враг здесь, и звёзды подождут.
     * <p>
     * Видимость обязательна ({@code sees}): о скрытных кораблях (п. 7) империя не знает и
     * реагировать на них не может — в этом и смысл стороны расы.
     */
    private Set<UUID> alarmSystems(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        Set<UUID> mine = context.coloniesOf(empire.getId()).stream()
                .map(context::systemOf)
                .collect(Collectors.toSet());
        if (mine.isEmpty()) {
            return Set.of();
        }
        Set<UUID> alarm = new HashSet<>();
        for (FleetEntity fleet : galaxy.fleets()) {
            if (fleet.getOwnerPlayerId().equals(empire.getId())
                    || Boolean.TRUE.equals(fleet.isInFlight())
                    || fleet.getStarSystemId() == null
                    || !mine.contains(fleet.getStarSystemId())
                    || !Boolean.TRUE.equals(galaxy.sees(empire.getId(), fleet))) {
                continue;
            }
            boolean war = galaxy.stanceOf(empire.getId(), fleet.getOwnerPlayerId())
                    == DiplomacyStance.WAR;
            boolean landing = galaxy.count(fleet, ShipRole.TRANSPORT) > 0
                    && !Boolean.TRUE.equals(
                            galaxy.attackForbidden(empire.getId(), fleet.getOwnerPlayerId()));
            if (war || landing) {
                alarm.add(fleet.getStarSystemId());
            }
        }
        return alarm;
    }

    private Set<UUID> borderSystems(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        Integer mine = galaxy.might().getOrDefault(empire.getId(), 0);
        List<StarSystemEntity> dangerous = context.colonies().stream()
                .filter(colony -> colony.getOwnerPlayerId() != null)
                .filter(colony -> !empire.getId().equals(colony.getOwnerPlayerId()))
                .filter(colony -> Boolean.TRUE.equals(
                        galaxy.knows(empire.getId(), context.systemOf(colony))))
                .filter(colony -> galaxy.might().getOrDefault(colony.getOwnerPlayerId(), 0) * 100
                        >= mine * THREAT_MIGHT_PERCENT)
                .map(colony -> galaxy.systemById().get(context.systemOf(colony)))
                .filter(Objects::nonNull)
                .toList();
        if (dangerous.isEmpty()) {
            return Set.of();
        }
        long limit = (long) THREAT_PARSECS * THREAT_PARSECS;
        Set<UUID> border = new HashSet<>();
        for (PlanetEntity colony : context.coloniesOf(empire.getId())) {
            StarSystemEntity home = galaxy.systemById().get(context.systemOf(colony));
            if (home == null) {
                continue;
            }
            for (StarSystemEntity other : dangerous) {
                if (distanceSquared(home, other) <= limit) {
                    border.add(home.getId());
                    break;
                }
            }
        }
        return border;
    }

    /**
     * Приказы флотам — п. 8.
     * <p>
     * Колониальный корабль уходит к свободной планете, транспорты — к колонии врага, а
     * боевой флот СВЕРХ ГАРНИЗОНА идёт туда же, куда и десант.
     *
     * <p><b>Почему боевой флот теперь ходит.</b> Раньше он не ходил никогда: «в MOO II он и
     * есть оборона системы, а гонять его по галактике без цели незачем». Цена этому вышла
     * такая: за 150 ходов восьми империй — <b>ноль боёв</b> и одна высадка на партию (в иных
     * партиях ни одной). Флоты не встречались, потому что двигались только транспорты, а
     * защищённую цель без прикрытия не берут вовсе — {@link #assault} требует боевых
     * кораблей, которые сидят дома. Механика десанта и корабельного боя для замеров была
     * мертва: прибор балансировки мерил нулём не слабые стороны, а неработающую игру.
     *
     * <p>Гарнизон при этом остаётся: дома всегда стоит по
     * {@link #WARSHIPS_PER_COLONY_IN_PEACE} кораблю на колонию, и в поход уходит только
     * излишек. Пустой дом — это приглашение соседу, и менять одну беду на другую незачем.
     */
    private void orders(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        Set<UUID> reachable = reachable(context, empire);
        if (reachable.isEmpty()) {
            return;
        }

        // Куда идёт война этой империи: та же цель, что выбрана под десант, — прикрытие и
        // транспорты обязаны сходиться в одной системе, иначе прикрытие бесполезно.
        PlanetEntity prize = Boolean.TRUE.equals(atWar(galaxy, empire))
                ? invasionChance(context, galaxy, empire)
                : null;
        // Телепату цель нужна СВОЯ — п. 7. Подчинение не требует ни транспортов, ни
        // десанта: крупный корабль на орбите, и колония меняет хозяина. Но боевой флот
        // ходил только прикрывать высадку, то есть лишь тогда, когда у империи есть
        // транспорты и цель для них, — и телепату идти было некуда. Замер живой партии:
        // НОЛЬ подчинений за 150 ходов у восьми империй, то есть сторона расы «телепаты»
        // мерилась нулём не потому, что слаба, а потому, что игра её не трогала.
        if (prize == null && Boolean.TRUE.equals(telepathic(galaxy, empire))) {
            prize = invasionChance(context, galaxy, empire, Integer.MAX_VALUE);
        }
        UUID front = prize == null ? null : context.systemOf(prize);
        // Излишек боевых кораблей сверх гарнизона: столько и можно увести.
        int spare = galaxy.owned(empire.getId(), ShipRole.WARSHIP)
                - context.coloniesOf(empire.getId()).size() * WARSHIPS_PER_COLONY_IN_PEACE;

        // Ближайшая НЕразведанная система в пределах топлива — цель разведки. Дальность
        // считается по своим опорам и разведанности не требует: лететь в неизвестное
        // можно, на то и разведка, — а вот селиться и воевать вслепую нельзя.
        // Своя колония под чужим флотом — самое важное на этот ход: дом важнее звёзд.
        StarSystemEntity defend = threatened(context, galaxy, empire);
        boolean defenceSent = false;

        StarSystemEntity scoutTarget = null;
        // Один поход телепата за ход: подчинение берёт колонию целиком, и гнать за одной
        // добычей весь флот незачем.
        boolean mindControlSent = false;
        // Разведчиков у империи считанное число: пока они в пути, новых не шлют. Без
        // всякой оговорки разведка съедала весь флот — приказ уходил каждый ход, корабли
        // расползались и домой не возвращались.
        //
        // Сперва разведчик был ОДИН, и это оказалось слишком робко: замер показал, что в
        // партии восьми империй знакомы всего ШЕСТЬ ПАР ИЗ ДВАДЦАТИ ВОСЬМИ. Империи не
        // встречались вовсе — а значит, не воевали, не высаживались и не отнимали колоний:
        // наземное мерило балансировки умирало не от миролюбия, а от одиночества.
        // Летящие разведчики считаются ПО ТОМУ ЖЕ СПИСКУ целей, а не по признаку «летит в
        // неразведанное». Пока цель была одна (белое пятно), признаки совпадали; как
        // только целью стал и знакомый сосед, они разошлись — и полёты к соседям
        // перестали считаться разведкой вовсе. ИИ слал корабль КАЖДЫЙ ХОД без счёта, флот
        // расползался, а расселение и знакомства просели вдвое. Список один — и счёт по
        // нему один.
        List<StarSystemEntity> scoutable = unexplored(context, galaxy, empire, reachable);
        Set<UUID> wanted = scoutable.stream()
                .map(StarSystemEntity::getId)
                .collect(Collectors.toSet());
        long scouts = galaxy.fleets().stream()
                .filter(fleet -> fleet.getOwnerPlayerId().equals(empire.getId()))
                .filter(FleetEntity::isInFlight)
                .filter(fleet -> fleet.getTargetSystemId() != null
                        && wanted.contains(fleet.getTargetSystemId()))
                .count();
        boolean scouted = scouts >= SCOUTS_AT_ONCE;

        for (FleetEntity fleet : galaxy.standing(empire.getId())) {
            StarSystemEntity origin = galaxy.systemById().get(fleet.getStarSystemId());
            if (origin != null && scoutTarget == null) {
                scoutTarget = nearest(origin, scoutable);
            }
            if (origin == null) {
                continue;
            }
            StarSystemEntity target = null;
            if (galaxy.count(fleet, ShipRole.COLONY) > 0) {
                target = nearest(origin, settleTargets(context, galaxy, empire, reachable));
            } else if (galaxy.count(fleet, ShipRole.OUTPOST) > 0) {
                // Застава ставится на дальней окраине: тем и раздвигает пузырь дальности.
                target = farthest(origin, outpostTargets(context, galaxy, empire, reachable));
            } else if (galaxy.troops(fleet) > 0) {
                target = nearest(origin, warTargets(galaxy, empire, reachable, galaxy.troops(fleet)));
                if (target == null) {
                    // Десанта на цель не хватает — транспорты сходятся в кулак. Порознь они
                    // бесполезны: наземный бой считается силами сторон (п. 12), и один
                    // транспорт гибнет, не взяв ничего. Колонии строят их поодиночке, и
                    // без сбора у каждой оставалось по одному — за девять сотен ходов
                    // высадок случилось десять на девяносто пять построенных транспортов.
                    target = staging(context, galaxy, empire, reachable, origin);
                }
            } else if (defend != null && !defenceSent
                    && galaxy.count(fleet, ShipRole.WARSHIP) > 0
                    && !defend.getId().equals(origin.getId())) {
                // ОБОРОНА ДОМА идёт прежде разведки и походов: над колонией стоит чужой
                // боевой флот, и звёзды подождут. Гарнизонная норма здесь не помеха —
                // норма и есть то, что защищает дом, а его как раз и жгут.
                defenceSent = true;
                target = defend;
            } else if (scoutTarget != null && galaxy.count(fleet, ShipRole.WARSHIP) > 0
                    && !scouted) {
                // РАЗВЕДКА — п. 15. Первый же боевой корабль уходит смотреть галактику, и
                // гарнизонная норма ему не помеха: пока империя не видела соседних звёзд,
                // ей нечего ни колонизировать, ни защищать. Так играет и человек — первым
                // делом шлёт корабль к ближайшей незнакомой звезде.
                //
                // Один флот за ход на империю: разведка не должна оголять дом целиком, а
                // звёзды всё равно открываются по одной.
                scouted = true;
                target = scoutTarget;
            } else if (front != null && !mindControlSent
                    && Boolean.TRUE.equals(telepathic(galaxy, empire))
                    && Boolean.TRUE.equals(bigShip(galaxy, fleet))) {
                // ТЕЛЕПАТ ИДЁТ САМ — п. 7. Крейсер на орбите чужой колонии, и она меняет
                // хозяина без единого выстрела: десант телепату не нужен вовсе. Поэтому
                // гарнизонная норма его и не держит — для него этот корабль не прикрытие
                // высадки, а само орудие захвата, как транспорт для прочих.
                //
                // Пока ветки не было, телепаты не подчинили НИ ОДНОЙ колонии: боевой флот
                // уходил только излишком сверх гарнизона, а излишка у молодой империи нет.
                // Сторона расы за три очка не делала ровно ничего — проверено на восьми
                // телепатических империях, ноль подчинений за 150 ходов.
                StarSystemEntity seat = galaxy.systemById().get(front);
                if (seat != null && !seat.getId().equals(origin.getId())) {
                    mindControlSent = true;
                    target = seat;
                }
            } else if (front != null && galaxy.count(fleet, ShipRole.WARSHIP) > 0
                    && spare >= galaxy.count(fleet, ShipRole.WARSHIP)) {
                // Боевой флот идёт к цели десанта — прикрывать высадку и драться с тем, кто
                // её стережёт. Уходит только излишек сверх гарнизона.
                StarSystemEntity war = galaxy.systemById().get(front);
                if (war != null && !war.getId().equals(origin.getId())) {
                    spare -= galaxy.count(fleet, ShipRole.WARSHIP);
                    target = war;
                }
            }
            if (target == null || target.getId().equals(origin.getId())) {
                continue;
            }
            fleetService.dispatch(context.game(), fleet, origin, target, galaxy.speed(fleet));
            log.info("ИИ {} отправил флот {} → {}", empire.getName(), origin.getName(), target.getName());
        }
    }



    /**
     * Своя колония, над которой стоит чужой боевой флот, — п. 8, п. 15.
     * <p>
     * <b>Зачем ИИ оборонительная реакция.</b> До неё империя ИИ не смотрела на чужие флоты
     * ВООБЩЕ: гарнизон стоял по норме на колонию и не двигался, что бы ни происходило над
     * головой. Из-за этого сторона расы «скрытные корабли» не стоила ничего даже после
     * того, как её научили прятаться от планов ИИ, — прятаться было не от чего: ИИ и так
     * не реагировал на видимый флот. Механика, на которую никто не отвечает, неотличима от
     * выключенной.
     * <p>
     * <b>Здесь и работает скрытность.</b> Флот ищется через {@link Galaxy#sees}: скрытную
     * эскадру империя не увидит и подкрепления не пошлёт — чужой десант застанет колонию
     * такой, какой её оставили. Это и есть цена шести очков.
     * <p>
     * Летящие флоты в счёт не идут: в пути их не видит никто, даже сканеры игрока.
     */
    private StarSystemEntity threatened(TurnContext context, Galaxy galaxy, PlayerEntity empire) {
        Set<UUID> mine = context.coloniesOf(empire.getId()).stream()
                .map(context::systemOf)
                .collect(Collectors.toSet());
        if (mine.isEmpty()) {
            return null;
        }
        return galaxy.fleets().stream()
                .filter(fleet -> !fleet.getOwnerPlayerId().equals(empire.getId()))
                .filter(fleet -> !Boolean.TRUE.equals(fleet.isInFlight()))
                .filter(fleet -> fleet.getStarSystemId() != null
                        && mine.contains(fleet.getStarSystemId()))
                .filter(fleet -> galaxy.count(fleet, ShipRole.WARSHIP) > 0
                        || galaxy.count(fleet, ShipRole.TRANSPORT) > 0)
                .filter(fleet -> Boolean.TRUE.equals(galaxy.sees(empire.getId(), fleet)))
                .map(fleet -> galaxy.systemById().get(fleet.getStarSystemId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * Куда лететь смотреть — п. 15. Цель у разведки ДВЕ, и вторая не менее важная.
     * <p>
     * <b>Белые пятна.</b> Системы в пределах топлива, которых империя не знает: без них не
     * найти ни места под колонию, ни соседа. До появления этого списка ИИ не разведывал
     * вовсе — флоты рассылались только под колонизацию, заставу, десант и прикрытие.
     * <p>
     * <b>Незнакомые соседи.</b> Система, где стоит колония империи, с которой мы ещё не
     * знакомы, — тоже цель: знакомство приносит договоры, а с ними доход и науку. Одними
     * белыми пятнами это не покрывается, и вот чем это обернулось. У ВСЕВИДЯЩЕЙ расы (п. 7)
     * неразведанного нет ПО ОПРЕДЕЛЕНИЮ: она знает галактику с первого хода. Значит,
     * список белых пятен у неё всегда пуст, разведчик не выходит никогда, знакомств нет —
     * и сторона, которая должна была помогать, стала вредить: прибор намерил ей −1,95 при
     * цене в одно очко. Теперь всевидящий шлёт корабль прямо к соседу, которого видит, но
     * ещё не встретил, — и его всеведение наконец работает на него.
     * <p>
     * Чужая колония в НЕразведанной системе под второе правило не подпадает и не нужна:
     * такая система и так белое пятно. Ничего лишнего ИИ этим не узнаёт.
     */
    private List<StarSystemEntity> unexplored(TurnContext context, Galaxy galaxy,
                                              PlayerEntity empire, Set<UUID> reachable) {
        Set<UUID> met = Set.copyOf(galaxy.knownRivals(empire.getId()));
        return context.systems().stream()
                .filter(system -> reachable.contains(system.getId()))
                .filter(system -> !Boolean.TRUE.equals(galaxy.knows(empire.getId(), system.getId()))
                        || Boolean.TRUE.equals(stranger(context, system, empire, met)))
                .toList();
    }

    /** Стоит ли в системе колония империи, с которой мы ещё не знакомы, — п. 15. */
    private Boolean stranger(TurnContext context, StarSystemEntity system, PlayerEntity empire,
                             Set<UUID> met) {
        return context.colonies().stream()
                .filter(colony -> system.getId().equals(context.systemOf(colony)))
                .filter(colony -> colony.getOwnerPlayerId() != null)
                .filter(colony -> !colony.getOwnerPlayerId().equals(empire.getId()))
                .anyMatch(colony -> !met.contains(colony.getOwnerPlayerId()));
    }

    /** Системы, до которых империя дотягивается топливом, — п. 8: правило то же, что у игрока. */
    private Set<UUID> reachable(TurnContext context, PlayerEntity empire) {
        Set<String> technologies = context.colonyContext().technologiesByOwner()
                .getOrDefault(empire.getId(), Set.of());
        double range = flightRules.rangeParsecs(technologies);
        double squared = range * range;

        List<StarSystemEntity> bases = context.systems().stream()
                .filter(system -> system.getId().equals(empire.getHomeSystemId())
                        || system.getPlanets().stream()
                        .anyMatch(planet -> empire.getId().equals(planet.getOwnerPlayerId())))
                .toList();
        if (bases.isEmpty()) {
            return Set.of();
        }

        Set<UUID> reachable = new HashSet<>();
        for (StarSystemEntity system : context.systems()) {
            for (StarSystemEntity base : bases) {
                if (distanceSquared(base, system) <= squared) {
                    reachable.add(system.getId());
                    break;
                }
            }
        }
        return reachable;
    }

    /**
     * Системы, где ещё никого нет: туда и уходит корабль-застава — п. 8.
     * <p>
     * Годится любая планета, даже негодная для жизни: застава нужна ради дальности, а не
     * ради жителей. Система, где уже есть чья-то планета, не годится — там и без нас есть
     * от чего мерить топливо, а хозяину это соседство ни к чему.
     */
    private List<StarSystemEntity> outpostTargets(TurnContext context, Galaxy galaxy,
                                                  PlayerEntity empire, Set<UUID> reachable) {
        return context.systems().stream()
                .filter(system -> reachable.contains(system.getId()))
                // Ставить заставу в системе, которой не видел, нельзя: там может не быть
                // ни одной планеты. Человек это узнаёт, слетав, — теперь и ИИ тоже.
                .filter(system -> Boolean.TRUE.equals(galaxy.knows(empire.getId(), system.getId())))
                // Систему под сторожем обходим — п. 11.1: селиться там нельзя, пока
                // чудище живо, и колониальный корабль ушёл бы впустую. ВИДИТ сторожа
                // разведавший систему — или всевидящая раса, которой он виден всюду
                // (п. 7): в этом её преимущество, она обходит чудищ с первого хода, а
                // прочие узнают о них, потеряв корабль.
                .filter(system -> !Boolean.TRUE.equals(system.hasLiveMonster()))
                .filter(system -> system.getPlanets().stream()
                        .allMatch(planet -> planet.getOwnerPlayerId() == null))
                .filter(system -> !system.getPlanets().isEmpty())
                .toList();
    }

    /** Системы со свободной планетой: туда и уходит колониальный корабль — п. 4.1. */
    private List<StarSystemEntity> settleTargets(TurnContext context, Galaxy galaxy,
                                                 PlayerEntity empire, Set<UUID> reachable) {
        // Раса снимается ОДИН РАЗ на империю, а не в фильтре: фильтр проходит по всем
        // системам галактики, и собирать по ней эффекты на каждую значило бы пересобирать
        // их сотню раз за ход.
        RaceEffects race = raceService.effects(empire);
        return context.systems().stream()
                .filter(system -> reachable.contains(system.getId()))
                // Селиться вслепую нельзя — п. 15: пока система не разведана, о её планетах
                // империя не знает ничего, и колониальный корабль ушёл бы наугад.
                .filter(system -> Boolean.TRUE.equals(galaxy.knows(empire.getId(), system.getId())))
                // Систему под сторожем обходим — п. 11.1: селиться там нельзя, пока
                // чудище живо, и колониальный корабль ушёл бы впустую. ВИДИТ сторожа
                // разведавший систему — или всевидящая раса, которой он виден всюду
                // (п. 7): в этом её преимущество, она обходит чудищ с первого хода, а
                // прочие узнают о них, потеряв корабль.
                .filter(system -> !Boolean.TRUE.equals(system.hasLiveMonster()))
                .filter(system -> bestFreePlanet(system, race).isPresent())
                // Чужая система по дороге — не место для колонии: её хозяин и так рядом.
                .filter(system -> system.getPlanets().stream()
                        .noneMatch(planet -> planet.getOwnerPlayerId() != null
                                && !planet.getOwnerPlayerId().equals(empire.getId())))
                .toList();
    }

    /**
     * Куда стягивать десант — п. 12: своя колония, что ближе всех к воюющему соседу.
     * <p>
     * Своя, а не любая: транспорт беззащитен, и ждать пополнения ему стоит там, где стоит
     * своя система, а не в чистом поле. {@code null} — стягиваться некуда или флот уже на
     * месте.
     */
    private StarSystemEntity staging(TurnContext context, Galaxy galaxy, PlayerEntity empire,
                                     Set<UUID> reachable, StarSystemEntity origin) {
        List<StarSystemEntity> enemy = context.systems().stream()
                .filter(system -> system.getPlanets().stream().anyMatch(planet ->
                        planet.getOwnerPlayerId() != null
                                && !planet.getOwnerPlayerId().equals(empire.getId())
                                && planet.getPopulation() > 0
                                && galaxy.stanceOf(empire.getId(), planet.getOwnerPlayerId())
                                == DiplomacyStance.WAR))
                .toList();
        if (enemy.isEmpty()) {
            return null;
        }

        StarSystemEntity front = context.systems().stream()
                .filter(system -> reachable.contains(system.getId()))
                .filter(system -> system.getPlanets().stream().anyMatch(planet ->
                        empire.getId().equals(planet.getOwnerPlayerId())
                                && planet.getPopulation() > 0))
                .min(Comparator.comparing(system -> enemy.stream()
                        .mapToDouble(target -> distanceSquared(system, target))
                        .min()
                        .orElse(Double.MAX_VALUE)))
                .orElse(null);
        return front == null || front.getId().equals(origin.getId()) ? null : front;
    }

    /** Системы, где стоит колония врага по силам десанту, — п. 12. */
    private List<StarSystemEntity> warTargets(Galaxy galaxy, PlayerEntity empire,
                                              Set<UUID> reachable, Integer troops) {
        return galaxy.systemById().values().stream()
                .filter(system -> reachable.contains(system.getId()))
                .filter(system -> system.getPlanets().stream().anyMatch(planet ->
                        planet.getOwnerPlayerId() != null
                                && !planet.getOwnerPlayerId().equals(empire.getId())
                                && planet.getPopulation() > 0
                                && galaxy.stanceOf(empire.getId(), planet.getOwnerPlayerId())
                                == DiplomacyStance.WAR
                                && !Boolean.TRUE.equals(galaxy.attackForbidden(
                                empire.getId(), planet.getOwnerPlayerId()))
                                && troops * 100 >= planet.getPopulation() * INVASION_ADVANTAGE_PERCENT))
                .toList();
    }

    /** Самая дальняя система из списка: застава на окраине раздвигает пузырь дальше всех. */
    private StarSystemEntity farthest(StarSystemEntity origin, List<StarSystemEntity> candidates) {
        return candidates.stream()
                .filter(system -> !system.getId().equals(origin.getId()))
                .max(Comparator.comparing(system -> distanceSquared(origin, system)))
                .orElse(null);
    }

    /** Ближайшая система из списка: лететь дальше, чем нужно, незачем. */
    private StarSystemEntity nearest(StarSystemEntity origin, List<StarSystemEntity> candidates) {
        return candidates.stream()
                .filter(system -> !system.getId().equals(origin.getId()))
                .min(Comparator.comparing(system -> distanceSquared(origin, system)))
                .orElse(null);
    }

    /**
     * Лучшая свободная планета системы — п. 4.1: та, что прокормит больше жителей.
     * Пояса астероидов и газовые гиганты в MOO II колонизировать нельзя.
     * <p>
     * <b>Вместимость считается ДЛЯ СВОЕЙ РАСЫ</b> — п. 7 (журнал, п. 3.102). В самой
     * планете записана вместимость средней расы, а «хорошая планета» у каждой своя:
     * неприхотливым любой мир земной, водным мокрые миры лучше, подземные копают вглубь
     * тем больше, чем крупнее планета. Выбирая по записанному числу, империя таких рас
     * садилась мимо своих лучших миров — и очки, за эти стороны плаченные, пропадали.
     * <p>
     * При равной вместимости берётся первая по имени: название планеты выводится из зерна
     * партии и уникально, а партия обязана повторяться.
     */
    private Optional<PlanetEntity> bestFreePlanet(StarSystemEntity system, RaceEffects race) {
        return system.getPlanets().stream()
                .filter(planet -> planet.getOwnerPlayerId() == null)
                .filter(planet -> planet.getPopulation() <= 0)
                .filter(planet -> Boolean.TRUE.equals(planet.getClimate().getColonizable()))
                .max(Comparator.<PlanetEntity, Integer>comparing(planet -> worthFor(planet, race))
                        .thenComparing(Comparator.comparing(PlanetEntity::getName).reversed()));
    }

    /**
     * Чего планета стоит империи — вместимость плюс находка (п. 4.1).
     * <p>
     * Находка меряется В МЕСТАХ, потому что сравнивать её не с чем: у планеты одно число,
     * и золотая жила должна перевесить пару лишних жителей у соседки. Три — реконструкция
     * и умышленно скромная: пять кредитов за ход это примерно столько же, сколько даст
     * тройка жителей налогом и товарами, а туземцы тремя местами и расплачиваются.
     * <p>
     * <b>Систему это не меняет.</b> Колониальный корабль по-прежнему летит в БЛИЖАЙШУЮ
     * годную ({@code nearest}), а находка решает, какую планету в ней занять. Точка
     * расширения: тянуться за самоцветами в дальний угол — это выбор игрока-человека, и
     * прежде чем учить тому же ИИ, стоит померить, не бросит ли он ближние миры.
     */
    private Integer worthFor(PlanetEntity planet, RaceEffects race) {
        return capacityFor(planet, race) + (planet.getFind() == null ? 0 : FIND_WORTH);
    }

    /** Вместимость планеты для этой расы: записанная плюс расовая прибавка — п. 7. */
    private Integer capacityFor(PlanetEntity planet, RaceEffects race) {
        return planet.getMaxPopulation() + populationCalculator.raceCapacityBonus(
                planet.getPlanetSize(), planet.getClimate(), race);
    }

    /** Воюет ли империя хоть с кем-нибудь: от этого зависит, строить ли флот. */
    private Boolean atWar(Galaxy galaxy, PlayerEntity empire) {
        return galaxy.stance().getOrDefault(empire.getId(), Map.of()).values().stream()
                .anyMatch(relation -> relation.getStance() == DiplomacyStance.WAR);
    }

    private PlayerEntity player(TurnContext context, UUID playerId) {
        return context.players().stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    /** Квадрат расстояния между системами в парсеках: корень для сравнения не нужен. */
    private double distanceSquared(StarSystemEntity first, StarSystemEntity second) {
        double dx = first.getXParsec() - second.getXParsec();
        double dy = first.getYParsec() - second.getYParsec();
        return dx * dx + dy * dy;
    }
}
