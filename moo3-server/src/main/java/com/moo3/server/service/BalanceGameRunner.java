package com.moo3.server.service;

import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.dto.AdvanceTurnsRequest;
import com.moo3.server.dto.BalanceTelemetryDto;
import com.moo3.server.dto.CreateGameRequest;
import com.moo3.server.dto.CreateGameResponse;
import com.moo3.server.dto.StartGameRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Одна партия балансировки от начала до замера — общая часть обоих приборов.
 * <p>
 * Стоит отдельно от {@link BalanceRunService} намеренно, и причина та же, по которой отдельно
 * стоит {@code LeaderBonusService}: оракул сборок ({@link BalanceOracleService}) — это
 * прогон, который заводит служба прогонов, и в то же время он сам просит играть партии.
 * Конструкторами такое кольцо Spring не поднимает вовсе — контекст падает со словами
 * «Requested bean is currently in creation». Кольцо разрывается не подпоркой вроде отложенной
 * ссылки, а разделением: играющая часть не знает ни о прогонах, ни об оракуле, и зависеть от
 * неё может кто угодно.
 * <p>
 * Здесь только чтение игры: завести партию, доиграть до нужного хода, снять замеры, убрать
 * партию за собой. Ни правил оценки, ни правил поиска здесь нет.
 */
@Service
public class BalanceGameRunner {

    /** Имя игрока-наблюдателя: партию заводит он, а империю его ведёт ИИ. */
    private static final String OBSERVER = "Наблюдатель";

    private final GameService gameService;
    private final TurnBatchService turnBatchService;
    private final BalanceTelemetryService telemetryService;
    private final RaceTraitCatalog raceTraits;

    public BalanceGameRunner(GameService gameService,
                             TurnBatchService turnBatchService,
                             BalanceTelemetryService telemetryService,
                             RaceTraitCatalog raceTraits) {
        this.gameService = gameService;
        this.turnBatchService = turnBatchService;
        this.telemetryService = telemetryService;
        this.raceTraits = raceTraits;
    }

    /** Сборка на месте партии: чем играет империя и во сколько очков это обошлось. */
    public record Design(Integer slot, String name, List<String> traits, Integer budget) {
    }

    /**
     * Одна партия названными сборками.
     *
     * @param builds сборки по местам; первая достаётся наблюдателю, прочие — империям ИИ
     */
    public List<BalanceVerdictRules.Empire> playOnce(String galaxySize, Integer empires,
                                                     Integer turns, List<List<String>> builds,
                                                     long seed) {
        List<Design> designs = new ArrayList<>(builds.size());
        for (int slot = 0; slot < builds.size(); slot++) {
            designs.add(new Design(slot, "Сборка " + (slot + 1), builds.get(slot),
                    cost(builds.get(slot))));
        }
        return play(galaxySize, empires, turns, designs, seed);
    }

    /** Та же партия, но сборки уже разложены по местам — так её зовёт замер цен. */
    public List<BalanceVerdictRules.Empire> play(String galaxySize, Integer empires,
                                                 Integer turns, List<Design> designs, long seed) {
        CreateGameResponse created = gameService.createGame(new CreateGameRequest(
                "Прогон " + seed,
                GalaxySize.valueOf(galaxySize),
                OBSERVER,
                null,
                null,
                designs.get(0).name(),
                designs.get(0).traits(),
                seed,
                // СЛУЧАЙНЫЕ СОБЫТИЯ ВКЛЮЧЕНЫ. Здесь стояло «выключить», и это делало сторону
                // расы «Везучие» неизмеримой по построению: всё её действие — в событиях
                // (бед не получает вовсе), а событий в замере не случалось ни одного.
                // Прибор выносил ей приговор «оценена слишком дорого», меряя механику,
                // которой в партии не было. Тот же почерк, что у защиты кораблей,
                // договоров и разведки — журнал, пп. 3.28, 3.38, 3.39.
                //
                // Цена этому известна: события тянут беды к ведущей империи, а удачи к
                // отстающей, то есть подтягивают отстающих и слегка СГЛАЖИВАЮТ разницу
                // между сборками. Шум они добавляют тоже. Но мерить игру без её механик
                // значит мерить не игру: сквозной прогон гасит события своим партиям
                // (там они рушили звёздную базу посреди проверки), а балансовый — нет.
                Boolean.TRUE,
                Boolean.TRUE,
                empires,
                // СОВЕТ ВЫКЛЮЧЕН, и это не про игру, а про замер: прогон играет партию на
                // заданное число ходов и читает её летопись, а избранный правитель
                // обрывает партию раньше срока — партии выходили бы разной длины, и
                // сравнивать их было бы нельзя. Случайные события при этом ВКЛЮЧЕНЫ
                // (см. выше): они механика игры, а совет здесь — условие остановки.
                Boolean.FALSE));
        UUID gameId = created.game().id();
        String token = created.credentials().accessToken();
        try {
            gameService.startGame(gameId, new StartGameRequest(token, designs.stream()
                    .skip(1)
                    .map(design -> new StartGameRequest.AiEmpireDesign(design.name(), design.traits()))
                    .toList()));
            turnBatchService.advance(gameId, new AdvanceTurnsRequest(token, turns));
            return measure(telemetryService.of(gameId, token), designs);
        } finally {
            // Партии удаляются сразу: копить их в базе нельзя, на этом уже погорели — в базе
            // набралось восемь с половиной сотен брошенных партий.
            gameService.deleteGame(gameId, token);
        }
    }

    public Integer cost(List<String> traits) {
        return traits.stream().mapToInt(code -> raceTraits.require(code).picks()).sum();
    }

    /**
     * Замеры одной партии: ДОЛИ, а не сами величины, — галактики разной щедрости иначе
     * смешались бы в одну кучу. Бюджет берётся из заказанной сборки, а не из телеметрии:
     * так он не зависит от того, что раса успела вырастить по ходу партии.
     * <p>
     * Мерил СЕМЬ — п. 2.12 этапа 2 и этап 3. Выработка, наука, доход и сила флотов снимаются
     * с последней строки летописи, разведка — со счётчика доведённых до конца заданий
     * ({@code EmpireActivityService}): единиц за ход у разведки нет вовсе, а задания есть.
     * Пока мерило было одно, целые углы таблицы мерились нулём — и оракул сборок тут же
     * этим воспользовался, собрав верхушку из «продать всё, чего прибор не видит».
     * <p>
     * Партия, в которой мерило не сработало ни разу (ни одного задания разведки — обычное
     * дело), отдаёт по нему ПУСТО, а не нули: ноль у всех — это не «все одинаково слабы»,
     * это «мерить нечем», и приведение к среднему выдало бы каждому одинаковый штраф.
     */
    /**
     * Смягчение знаменателя наземного мерила — этап 2.
     * <p>
     * Доля, посчитанная от одного события, это не доля, а случайность: в партии, где из рук
     * в руки перешла ровно одна колония, взявший получил бы +100, потерявший −100, и такая
     * партия кричала бы впятеро громче той, где переходов было пять. Прибор от этого глохнет
     * весь: измерено на 400 партиях — медианная ошибка ВСЕХ приговоров выросла с 0,79 до
     * 1,19, а число «оценена адекватно» подскочило с 27 до 40. «Адекватно» ведь значит и
     * «не отличается от цены», и «нечем отличить», и второе тут было бы враньём.
     * <p>
     * Пять — столько колоний за партию в среднем и переходит из рук в руки (замер на зёрнах
     * 777, 778 и 780: семь, четыре и пять). С таким смягчением одиночный переход весит
     * вшестеро меньше сотни, а партия из пяти переходов почти не теряет в громкости —
     * ровно то, что нужно: смягчается недостоверное, а не сигнал.
     */
    private static final int GROUND_SMOOTHING = 5;

    /**
     * Доля какого ХОДА идёт в замер — п. 2 этапа 2.
     * <p>
     * <b>Не последнего.</b> Брался именно он, и при партиях в полтораста ходов это было
     * верно: партия к тому времени ещё не решена, и доля выработки говорит о расе. На
     * пятистах ходах партия РЕШЕНА — победитель забрал галактику, у него доля под сотню, у
     * остальных крохи, и замер читает не цену сторон, а то, кому досталась удача. Измерено
     * прямо на переходе к длинным партиям: медианная ошибка приговоров 1,30 (150 ходов) ->
     * 2,93 -> 5,82 (500 ходов), то есть полоска ошибки доросла до всего бюджета, и «оценена
     * адекватно» стало значить «сказать нечего» у сорока восьми сторон из пятидесяти двух.
     * <p>
     * Поэтому берётся МЕДИАНА по второй половине партии: она и длинную партию читает
     * целиком, и не даёт последнему ходу решать всё. Медиана, а не среднее, — потому что
     * захват чужой столицы это скачок, а не тренд.
     */
    private static Integer typical(BalanceTelemetryDto.EmpireTelemetryDto empire,
                                   java.util.function.ToIntFunction<BalanceTelemetryDto.TurnRowDto> field) {
        List<BalanceTelemetryDto.TurnRowDto> rows = empire.history();
        List<Integer> tail = rows.subList(rows.size() / 2, rows.size()).stream()
                .map(field::applyAsInt)
                .sorted()
                .toList();
        return tail.isEmpty() ? 0 : tail.get(tail.size() / 2);
    }

    private List<BalanceVerdictRules.Empire> measure(BalanceTelemetryDto telemetry,
                                                     List<Design> designs) {
        Map<Integer, Design> bySlot = new LinkedHashMap<>();
        designs.forEach(design -> bySlot.put(design.slot(), design));

        Map<Integer, BalanceTelemetryDto.EmpireTelemetryDto> bySlotTelemetry = new LinkedHashMap<>();
        Map<Integer, Integer> production = new LinkedHashMap<>();
        Map<Integer, Integer> research = new LinkedHashMap<>();
        Map<Integer, Integer> espionage = new LinkedHashMap<>();
        Map<Integer, Integer> money = new LinkedHashMap<>();
        Map<Integer, Integer> military = new LinkedHashMap<>();
        Map<Integer, Integer> ground = new LinkedHashMap<>();
        Map<Integer, Integer> technology = new LinkedHashMap<>();
        for (BalanceTelemetryDto.EmpireTelemetryDto empire : telemetry.empires()) {
            if (empire.history().isEmpty()) {
                continue;
            }
            production.put(empire.slot(), typical(empire, BalanceTelemetryDto.TurnRowDto::production));
            research.put(empire.slot(), typical(empire, BalanceTelemetryDto.TurnRowDto::research));
            espionage.put(empire.slot(),
                    empire.used().getOrDefault(EmpireActivityService.ESPIONAGE, 0));
            // Деньги — ДОХОДОМ за ход, а не казной: казна запас, и потративший всё на
            // стройку стоит с нулём в кармане, живя при этом лучше скопидома.
            money.put(empire.slot(), typical(empire, BalanceTelemetryDto.TurnRowDto::income));
            // Военная сила — силой построенных кораблей. Наземный бой сюда не входит вовсе:
            // он виден только в захватах, а их за партию считанные единицы.
            military.put(empire.slot(), typical(empire, BalanceTelemetryDto.TurnRowDto::fleetPower));
            // Наземный бой — РАЗНОСТЬ: взятые колонии минус потерянные. Пока считались одни
            // захваты, половина стороны «бойцы» была неизмерима по построению: ИИ
            // высаживается только при полуторном перевесе над обороной, то есть когда
            // победа уже решена, — значит подготовка бойцов меняет не исход боя, а лишь то,
            // как часто ИИ решается, и это тонет в том, с кем он воюет. Оборона же не была
            // видна вовсе: «хорошие бойцы» проявляются как «у меня реже отнимают».
            // Измерено на 400 партиях: у трёх ступеней «бойцов» выходило 0,73 / 0,56 / 1,04
            // при ценах 2 / 1 / -1 — ни лестницы, ни верного знака.
            ground.put(empire.slot(),
                    empire.used().getOrDefault(EmpireActivityService.CAPTURE, 0)
                            - empire.used().getOrDefault(EmpireActivityService.COLONY_LOST, 0));
            // Широта науки — числом изученного. Мерило науки считает очки за ход и не видит
            // разницы между «изучил уровень целиком» и «изучил из него одно»: очки те же.
            technology.put(empire.slot(), typical(empire, BalanceTelemetryDto.TurnRowDto::technologies));
            bySlotTelemetry.put(empire.slot(), empire);
        }
        int total = production.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0 || production.size() < 2) {
            return List.of();
        }
        int totalResearch = research.values().stream().mapToInt(Integer::intValue).sum();
        int totalEspionage = espionage.values().stream().mapToInt(Integer::intValue).sum();
        int totalMoney = money.values().stream().mapToInt(Integer::intValue).sum();
        int totalMilitary = military.values().stream().mapToInt(Integer::intValue).sum();
        // У наземного мерила сумма по партии тождественно НОЛЬ: всякий захват — это чья-то
        // потеря. Делить долю на неё нельзя, поэтому знаменателем берётся число самих
        // захватов — сколько колоний в этой партии вообще перешло из рук в руки.
        int totalGround = ground.values().stream()
                .mapToInt(value -> Math.max(0, value))
                .sum();
        // И к этому числу прибавляется смягчение: см. GROUND_SMOOTHING.
        if (totalGround > 0) {
            totalGround += GROUND_SMOOTHING;
        }
        int totalTechnology = technology.values().stream().mapToInt(Integer::intValue).sum();

        List<BalanceVerdictRules.Empire> measured = new ArrayList<>();
        for (Map.Entry<Integer, Integer> made : production.entrySet()) {
            // Места в телеметрии считаются с единицы, а сборки раздавались с нуля.
            Design design = bySlot.get(made.getKey() - 1);
            BalanceTelemetryDto.EmpireTelemetryDto empire = bySlotTelemetry.get(made.getKey());
            if (design == null || empire == null) {
                continue;
            }
            measured.add(new BalanceVerdictRules.Empire(
                    new HashSet<>(design.traits()),
                    design.budget(),
                    100.0 * made.getValue() / total,
                    share(research.get(made.getKey()), totalResearch),
                    share(espionage.get(made.getKey()), totalEspionage),
                    share(money.get(made.getKey()), totalMoney),
                    share(military.get(made.getKey()), totalMilitary),
                    // Доля здесь СО ЗНАКОМ и в среднем по партии равна нулю, а не 100/n:
                    // отнявший колонию получает плюс, потерявший — минус ровно столько же.
                    // Приведение к нулю у этого мерила поэтому своё — см. BalanceVerdictRules.
                    share(ground.get(made.getKey()), totalGround),
                    share(technology.get(made.getKey()), totalTechnology),
                    production.size(),
                    empire.nearbyPlanets(),
                    empire.nearestRival()));
        }
        return measured;
    }

    /**
     * Доля империи по мерилу; пусто — мерило в этой партии не сработало ни разу.
     * <p>
     * Отрицательная доля — законный ответ: у наземного боя считается разность взятого и
     * потерянного, и у половины сторон она ниже нуля.
     */
    private Double share(Integer made, int total) {
        return total <= 0 || made == null ? null : 100.0 * made / total;
    }
}
