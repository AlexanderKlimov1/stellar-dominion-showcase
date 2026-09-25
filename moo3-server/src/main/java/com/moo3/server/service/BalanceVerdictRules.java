package com.moo3.server.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Правила оценки цен сторон расы — этап 2 плана (`balance-metrics-works.txt`).
 * <p>
 * Здесь и только здесь лежат числа, которыми замер превращается в приговор цене. Сам
 * замер приходит извне — прогон партий, — а это правила чтения: что считать силой
 * стороны, когда её вообще можно считать измеренной и с какого расхождения цена
 * объявляется завышенной или заниженной.
 *
 * <p><b>Чем меряется сила.</b> Долей ВЫРАБОТКИ империи в партии, а не мощью. Мощь —
 * суррогат исхода (она и правда предсказывает победителя на 150-м ходу почти в девяти
 * случаях из десяти), но в неё входят флот и изученное, а они у ИИ гуляют от случая
 * сильнее, чем от расы: на 240 партиях разрыв крайних бюджетов по мощи тонул в
 * собственной ошибке, а по выработке был виден уверенно. Доля, а не сама выработка:
 * галактики разной щедрости иначе смешались бы в одну кучу.
 *
 * <p><b>Как считается — п. 2.2 этапа 2: регрессией, а не сравнением пар.</b> Сперва
 * сторона сравнивалась у взявших с невзявшими, и это путало сторону с её соседями по
 * сборке: стороны берутся не поодиночке, и сильная тянула за собой всё, что обычно стоит
 * с ней рядом. Регрессия их разводит: вес стороны — это то, что она добавляет <i>сверх</i>
 * всего остального, что есть в сборке.
 *
 * <p>Три вещи, без которых регрессия соврала бы:
 * <ul>
 *   <li><b>доля приводится к нулю внутри партии</b> — партия почти нулевая сумма, и её
 *       среднее известно заранее (сто процентов на число империй). Вычитая его, мы
 *       убираем эффект галактики целиком, и отдельный столбец на партию не нужен;</li>
 *   <li><b>стартовый угол идёт столбцами</b> — число пригодных планет рядом и расстояние
 *       до соседа. Без них регрессия примет щедрость карты за силу расы;</li>
 *   <li><b>гребневая поправка</b> — столбцы сборки линейно зависимы по устройству
 *       конструктора (из группы берут ровно одну сторону), и чистые нормальные уравнения
 *       на такой матрице вырождаются.</li>
 * </ul>
 *
 * <p><b>Откуда берётся «справедливая цена».</b> Из самих замеров: по различимым сторонам
 * строится прямая «цена — сила», и её наклон и есть курс очка. Единого курса «очко →
 * сила», снятого по бюджетам, для этого не годится: он оказался неотличим от нуля, потому
 * что сильные стороны в случайной сборке гасятся слабыми.
 */
@Service
public class BalanceVerdictRules {

    /** Сколько империй должно взять сторону, чтобы её замер вообще что-то значил. */
    public static final int MIN_TAKERS = 12;

    /**
     * Во сколько ошибок должно укладываться расхождение, чтобы цену считать верной.
     * <p>
     * Две — обычный порог различимости. Меньше двух ошибок — это разброс партий, а не
     * свойство стороны, и гоняться за ним значило бы менять цены вечно (п. 5 плана).
     */
    public static final double ERRORS_TO_JUDGE = 2.0;

    /**
     * Сторона, которую взяли почти все, так не меряется вовсе: «не взявшие» её — это те,
     * кто купил вместо неё что-то другое из той же группы, и сравнение получается не с
     * отсутствием стороны, а с её заменой. Бесплатная диктатура стоит в 97 % сборок.
     */
    public static final double MAX_SHARE_OF_TAKERS = 0.5;

    /**
     * Сколько империй должно взять ОБЕ стороны, чтобы связку вообще считать — п. 2.5.
     * <p>
     * Столько же, сколько нужно одной стороне: связка — такой же столбец замера, и слабее
     * обеспеченный столбец врёт ровно так же. Порог здесь дороже: две стороны, каждая в
     * десятой доле сборок, сходятся вместе в сотой, и связок с носителями в прогоне
     * заведомо меньше, чем самих сторон.
     */
    public static final int MIN_PAIR_TAKERS = 12;

    /**
     * Сколько связок берётся в расчёт — самых частых.
     * <p>
     * Пар из полусотни сторон больше тысячи, и столбец на каждую сделал бы матрицу шире
     * выборки: замер выродился бы весь, а не только связки. Берём те, у которых носителей
     * больше всего, — прочие всё равно не измеримы.
     */
    public static final int MAX_PAIRS = 40;

    /**
     * Сколько ошибок нужно связке, чтобы её считать находкой, когда проверяют СРАЗУ МНОГО.
     * <p>
     * Двух ошибок — обычного порога — здесь мало, и это не придирка. Проверяется не одна
     * заранее названная пара, а сорок разом, и самый крайний из сорока независимых замеров
     * уходит от нуля примерно на {@code sqrt(2 * ln 40)} ≈ 2,7 ошибки САМ ПО СЕБЕ, без
     * всякой связки: так устроен максимум многих случайных величин, и к игре это отношения
     * не имеет. На первом же настоящем прогоне (60 партий, 480 империй) прибор так и выдал
     * ровно одну «находку» — «всевидящие + хорошие бойцы», 1,87 ± 0,79, то есть 2,4
     * ошибки, — связку, которой неоткуда взяться: эти стороны не касаются друг друга
     * ничем.
     * <p>
     * Порог поэтому растёт с числом проверенных пар, а не стоит на месте. Цена этому —
     * слабые настоящие связки будут названы независимыми; это правильный обмен: ложная
     * связка уводит цены не туда, а пропущенная просто ждёт прогона подлиннее.
     */
    private double errorsForPairs(int pairs) {
        return Math.max(ERRORS_TO_JUDGE, Math.sqrt(2 * Math.log(Math.max(2, pairs))));
    }

    /**
     * Во сколько раз прибавка связки должна превышать ФОН, чтобы её считать находкой.
     * <p>
     * У столбцов связок есть общий перекос, и он не случайный: гребневая поправка сжимает
     * веса самих сторон к нулю, поэтому у взявших сильную сторону остаток модели немного
     * положителен, а у взявших вредную — отрицателен. Столбец связки — подмножество и тех
     * и других, и он этот остаток подбирает. На выдуманных данных, где связки нет вовсе,
     * прибор так насчитал ±0,44 при собственной ошибке 0,2 — то есть уверенный приговор
     * на пустом месте: ошибка веса о перекосе не знает и учесть его не может.
     * <p>
     * Зато перекос виден по самим связкам: у большинства пар связки нет, и медиана
     * прибавок по всем парам — это и есть фон. Находкой считается то, что из фона торчит
     * втрое. Настоящая связка торчит куда сильнее: в той же проверке заложенная прибавка
     * дала 4,2 против фона 0,44 — на порядок.
     */
    public static final double PAIR_ABOVE_BACKGROUND = 3.0;

    /**
     * Во сколько бюджетов может уложиться «справедливая цена», чтобы её стоило называть.
     * <p>
     * Справедливая цена — это сила, делённая на курс очка, а курс мал: деление на него
     * даёт сотни очков при бюджете в пятнадцать. Такое число не осторожная оценка, а
     * мусор, и показывать его нельзя — приговор при этом остаётся: он сравнивает силу с
     * обещанной ценой и от деления не зависит.
     */
    public static final double MAX_FAIR_PRICE_BUDGETS = 2.0;

    /**
     * Шкала цен и шкала ценностей разошлись, и это главный вывод этапа 2.
     * <p>
     * Очко стоит около 0,05 п.п. доли выработки, то есть ВЕСЬ бюджет в пятнадцать очков
     * стоит трёх четвертей пункта. А одни «хорошие промышленники» за четыре очка дают два
     * с половиной пункта — больше трёх полных бюджетов. Таблица цен такого сказать не
     * умеет: в ней нет числа, которым это выразить.
     * <p>
     * Поэтому справедливая цена, прежде чем стать советом, ЗАЖИМАЕТСЯ в то, что таблица
     * выразить может — от потолка анти-выбора до бюджета. Совет «поставьте двадцать пять
     * очков при бюджете пятнадцать» — не совет, а признание, что мерить нечем; зажатый
     * же говорит верное: «эта сторона стоит столько, сколько вообще бывает».
     */
    private Integer clamp(double price, Map<String, Integer> prices) {
        int highest = prices.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        int lowest = prices.values().stream().mapToInt(Integer::intValue).min().orElse(-1);
        return (int) Math.round(Math.max(lowest, Math.min(highest, price)));
    }

    /** Ковариаты стартового угла: пригодные планеты рядом и расстояние до соседа. */
    private static final int COVARIATES = 2;

    /**
     * Гребневая поправка — долей от числа наблюдений.
     * <p>
     * Вырожденность здесь не случайная, а <b>встроенная в правила игры</b>: каждая сборка
     * стоит ровно бюджет, поэтому взвешенная ценами сумма столбцов — константа, и она
     * линейно зависима со свободным членом. Символическая поправка (было 1e-6) систему
     * формально решала, но ошибки весов при этом улетали в сотни и тысячи процентных
     * пунктов: обращение почти вырожденной матрицы даёт огромные диагональные элементы, и
     * приговором становилось безразличное «адекватно» для каждой стороны подряд.
     * <p>
     * Поправка берётся долей от числа наблюдений, а не числом: тогда с ростом выборки
     * сжатие остаётся соразмерным, и цены не начинают прыгать оттого, что прогон стал
     * длиннее.
     */
    private static final double RIDGE_SHARE = 0.02;

    /**
     * Насколько цена сдвигается к измеренной за один пересчёт — п. 5 плана.
     * <p>
     * Не в точку, а долей пути: ценность зависит от цены (изменив цену, мы меняем и то,
     * в каких сборках сторона окажется), и прыжок в измеренную точку раскачивал бы цены
     * вместо того, чтобы их успокаивать. На киборгах это уже показали: удешевление разнесло
     * вредную сторону по трети сборок и испортило замер всему прогону.
     */
    public static final double DAMPING = 0.4;

    /**
     * Сколько весит НАУКА рядом с выработкой — п. 2.12 этапа 2.
     * <p>
     * Столько же. Выработка и наука — две половины хозяйства MOO II: жителя ставят либо к
     * станку, либо в лабораторию, и цена стороны обязана считать обе. Пока мерило было
     * одно, целый угол таблицы мерился нулём — не потому, что стороны слабы, а потому, что
     * их работа в это мерило не входила вовсе: «плохие учёные» возвращали три очка с
     * измеренной силой +1,19, то есть выходили выгодной покупкой.
     */
    public static final double RESEARCH_WEIGHT = 1.0;

    /**
     * Сколько весит РАЗВЕДКА — п. 2.12 этапа 2.
     * <p>
     * Половину. Разведка не поток хозяйства, а орудие: её доля считается не по единицам за
     * ход, а по горстке доведённых до конца заданий за всю партию, и в иных партиях их нет
     * вовсе. Такая доля прыгает много сильнее выработки, и равный вес затащил бы этот
     * разброс во все приговоры подряд. Половина — осознанно спорное число: это ЕДИНСТВЕННОЕ
     * место, где мерила сравниваются между собой, и менять его нужно здесь.
     */
    public static final double ESPIONAGE_WEIGHT = 0.5;

    /**
     * Сколько весят ДЕНЬГИ — этап 3.
     * <p>
     * Половину. Деньги — третий поток хозяйства рядом с выработкой и наукой, но поток
     * вторичный: в MOO II они обращаются в производство по два-четыре кредита за единицу,
     * то есть быть первым по деньгам дешевле, чем быть первым по станкам. Мерятся ДОХОДОМ
     * за ход, а не казной: казна — запас, и империя, потратившая всё на стройку, стоит с
     * нулём в кармане, живя при этом лучше скопидома.
     * <p>
     * Заведено после первого прогона оракула (п. 3.7): верхушка сплошь продавала «бедных»
     * (-4) и прочее, чего прибор не видит, — и для прибора это и правда было бесплатно.
     */
    public static final double MONEY_WEIGHT = 0.5;

    /**
     * Сколько весит ВОЕННАЯ СИЛА — этап 3.
     * <p>
     * Половину, и по той же причине: флот не поток хозяйства, а его вложение, и меряется
     * запасом — силой построенных кораблей. Империя, не строившая флот вовсе, по этому
     * мерилу ноль, и это верно: воевать ей нечем.
     * <p>
     * ЧЕГО ЭТО МЕРИЛО ВСЁ ЕЩЁ НЕ ВИДИТ — наземный бой. Сила десанта в силу флота не входит
     * вовсе, а проявляется только в захватах, которых за партию считанные единицы. Стороны
     * «бойцы» так и остаются почти неизмеримыми, и цена им назначена волевым решением
     * хозяина проекта (1 и 2 очка), а не замером. Честнее это назвать, чем прятать.
     */
    public static final double MILITARY_WEIGHT = 0.5;

    /**
     * Сколько весит НАЗЕМНЫЙ БОЙ — этап 3.
     * <p>
     * Половину, и мерится он РАЗНОСТЬЮ: колонии, взятые десантом, минус колонии, отнятые
     * десантом у самой империи. Десант — единственное, что наземный бой в игре делает, и в
     * силу флота или выработку он не входит.
     * <p>
     * <b>Почему разность, а не одни захваты.</b> Так мерило считало сначала, и оно не
     * различало бойцов вовсе: на 400 партиях у трёх ступеней выходило 0,73 / 0,56 / 1,04
     * при ценах 2 / 1 / -1 — ни лестницы, ни верного знака у «плохих». Причина в том, что
     * захват — событие НАПАДАЮЩЕГО, а ИИ высаживается только при полуторном перевесе над
     * обороной, то есть когда победа заранее решена: подготовка бойцов меняет не исход боя,
     * а лишь то, как часто ИИ решается, — и это тонет в том, с кем он воюет и докуда
     * дотягивается. Вторая же половина стороны — оборона — не была видна прибору в
     * принципе: «хорошие бойцы» проявляются как «у меня реже отнимают колонии», а такого
     * счётчика не существовало. Теперь он есть
     * ({@link com.moo3.server.service.EmpireActivityService#COLONY_LOST}).
     * <p>
     * У разности есть и побочная польза: она сама себя приводит к нулю. Сумма по партии
     * тождественно ноль (всякий захват — чья-то потеря), поэтому не воевать на земле
     * значит получить ровно ноль, а не штраф в размере среднего, как было.
     * <p>
     * ЧЕСТНАЯ ОГОВОРКА, которую нельзя опускать: захваченная колония поднимает и долю
     * выработки — то есть частью это мерило повторяет то, что уже сказано. Вес в половину
     * взят и поэтому тоже. А главное — переходов за партию считанные единицы, и в партии,
     * где их не было вовсе, мерило молчит (пусто, а не ноль).
     */
    public static final double GROUND_WEIGHT = 0.5;

    /**
     * Сколько весит ШИРОТА НАУКИ — число изученных технологий (этап 3).
     * <p>
     * Половину, и заведено оно ради одной стороны, которую прибор не видел вовсе:
     * ИЗОБРЕТАТЕЛЬНОСТЬ. Её польза в MOO II не в скорости науки, а в ШИРОТЕ — уровень
     * выдаётся весь, со всеми вариантами, вместо одного выбранного. Мерило науки считает
     * очки исследований за ход, и такой разницы не видит в принципе: очки те же самые.
     * Отсюда и замер: изобретательность за десять очков мерилась силой +0,13 — «сторона не
     * даёт ничего», чего про неё сказать нельзя.
     * <p>
     * Половина, а не полный вес, потому что технологии — не третий поток хозяйства, а его
     * итог: изученное превращается в выработку, флот и деньги, которые уже посчитаны
     * своими мерилами. Здесь считается лишь то, чего в них нет: сколько ВСЕГО империя
     * успела изучить.
     */
    public static final double TECHNOLOGY_WEIGHT = 0.5;

    /**
     * Один замер империи: что она взяла, на сколько очков, сколько взяла себе по каждому
     * мерилу и в каком углу галактики начинала.
     *
     * @param productionShare доля выработки партии, в процентах
     * @param researchShare   доля науки партии, в процентах; пусто — прогон старый, мерила
     *                        тогда ещё не было
     * @param espionageShare  доля доведённых до конца заданий разведки, в процентах
     * @param moneyShare      доля дохода партии за ход, в процентах
     * @param militaryShare   доля боевой силы флотов партии, в процентах
     * @param groundShare     наземный бой: взятые десантом колонии МИНУС потерянные, в
     *                        процентах от числа перешедших в партии колоний. В среднем по
     *                        партии ноль, а не 100/n — всякий захват это чья-то потеря
     * @param technologyShare доля изученных в партии технологий, в процентах
     * @param empiresInGame сколько империй было в партии: по нему доля приводится к нулю
     * @param nearbyPlanets сколько пригодных планет рядом с родной звездой — главная
     *                      ковариата стартового угла
     * @param nearestRival  до ближайшего соседа, парсеков
     */
    public record Empire(Set<String> traits, Integer budget, Double productionShare,
                         Double researchShare, Double espionageShare,
                         Double moneyShare, Double militaryShare, Double groundShare,
                         Double technologyShare,
                         Integer empiresInGame, Integer nearbyPlanets, Double nearestRival) {
    }

    /** Чем меряется сила стороны. {@link #TOTAL} — то, по чему выносится приговор цене. */
    public enum Yardstick {
        PRODUCTION, RESEARCH, ESPIONAGE, MONEY, MILITARY, GROUND, TECHNOLOGY, TOTAL
    }

    /**
     * Доля империи по одному мерилу, ПРИВЕДЁННАЯ К НУЛЮ внутри партии.
     * <p>
     * Наружу она смотрит ради оракула сборок (этап 3): он сравнивает сборки между собой, и
     * мерило у него обязано быть то же самое, что у приговоров ценам. Двух разных мерил в
     * одном приборе быть не может — иначе оценщик и взломщик спорили бы не о ценах, а о
     * том, что считать силой.
     * <p>
     * Среднее партии известно заранее — сто процентов на число империй, — и вычитая его, мы
     * убираем эффект галактики целиком, без отдельного столбца на партию. Пустая доля (у
     * старых прогонов её нет, а в иных партиях разведка не сработала ни разу) читается как
     * «сигнала нет» и даёт ровно ноль, а не минус среднее: иначе отсутствие замера стало бы
     * замером, и все стороны такой партии получили бы одинаковый штраф.
     */
    public double value(Empire empire, Yardstick yardstick) {
        double expected = empire.empiresInGame() == null || empire.empiresInGame() == 0
                ? 0
                : 100.0 / empire.empiresInGame();
        return switch (yardstick) {
            case PRODUCTION -> centred(empire.productionShare(), expected);
            case RESEARCH -> centred(empire.researchShare(), expected);
            case ESPIONAGE -> centred(empire.espionageShare(), expected);
            case MONEY -> centred(empire.moneyShare(), expected);
            case MILITARY -> centred(empire.militaryShare(), expected);
            // Наземный бой приводится к нулю СВОИМ способом: он и так разность взятого и
            // потерянного, и среднее по партии у него тождественно ноль. Вычесть отсюда
            // 100/n значило бы наказать всех, кто в этой партии не воевал на земле, — а не
            // воевать не значит быть слабым.
            case GROUND -> centred(empire.groundShare(), 0);
            case TECHNOLOGY -> centred(empire.technologyShare(), expected);
            case TOTAL -> centred(empire.productionShare(), expected)
                    + RESEARCH_WEIGHT * centred(empire.researchShare(), expected)
                    + ESPIONAGE_WEIGHT * centred(empire.espionageShare(), expected)
                    + MONEY_WEIGHT * centred(empire.moneyShare(), expected)
                    + MILITARY_WEIGHT * centred(empire.militaryShare(), expected)
                    + GROUND_WEIGHT * centred(empire.groundShare(), 0)
                    + TECHNOLOGY_WEIGHT * centred(empire.technologyShare(), expected);
        };
    }

    private double centred(Double share, double expected) {
        return share == null ? 0 : share - expected;
    }

    /**
     * Приговор цене одной стороны.
     *
     * @param strength  измеренная сила по ВСЕМ мерилам разом — то, по чему и выносится
     *                  приговор
     * @param error     стандартная ошибка этой разницы
     * @param production из чего сила сложилась: доля выработки
     * @param research   она же по науке
     * @param espionage  она же по разведке
     * @param money      она же по деньгам (доход за ход)
     * @param military   она же по военной силе (сила флотов)
     * @param ground     она же по наземному бою (захваченные колонии)
     * @param technology она же по широте науки (число изученных технологий)
     * @param fairPrice        цена, которую эта сила заслуживает по измеренному курсу очка
     * @param recommendedPrice  куда двигать цену ОДНИМ шагом: не в измеренную точку, а
     *                          долей пути к ней (п. 5 плана). Пусто — двигать не надо
     * @param verdict           что с ценой делать
     */
    public record Judgement(String code, Integer price, Integer takers,
                            Double strength, Double error,
                            Double production, Double research, Double espionage,
                            Double money, Double military, Double ground, Double technology,
                            Double fairPrice, Integer recommendedPrice, Verdict verdict) {
    }

    /** Приговор цене — то, ради чего весь замер и делается. */
    public enum Verdict {
        FAIR("оценена адекватно"),
        TOO_EXPENSIVE("оценена слишком дорого"),
        TOO_CHEAP("оценена слишком дёшево"),
        NOT_MEASURED("не измерена");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * Что даёт связка двух сторон СВЕРХ суммы своих половин — п. 2.5 этапа 2.
     * <p>
     * Отдельно взвешенная сторона отвечает на вопрос «сколько она стоит в среднем по
     * сборкам», и на этот вопрос у половины таблицы честный ответ «нисколько»: киборги
     * сами по себе только едят, а с промышленниками и голодным миром они и есть раса
     * Меклар. Такие стороны покупают не поодиночке, и мерить их поодиночке — значит мерить
     * не то, за чем игрок к ним идёт.
     * <p>
     * Поэтому в замер добавляется столбец на связку: единица там, где взяты обе стороны
     * сразу. Его вес — ровно то, что связка даёт сверх своих половин: слагаемые уже
     * посчитаны своими столбцами. Плюс — стороны усиливают друг друга, минус — мешают,
     * около нуля — живут независимо, и тогда цена каждой половины сама по себе и есть
     * правда.
     *
     * <p>
     * <b>Сторон в связке две или три.</b> Тройка — не прихоть: замысел хозяина проекта был
     * именно о ней («киборги + антибонус к еде + промышленники»), и пара на такой вопрос не
     * отвечает. У тройки в замере свой столбец — единица там, где взяты все три, — и стоит
     * он рядом со столбцами всех трёх пар внутри неё. Поэтому его вес отвечает на точный
     * вопрос: даёт ли тройка что-то СВЕРХ ТОГО, что уже дают её пары и одиночки.
     *
     * @param members  стороны связки: две или три
     * @param pairs    у скольких империй взяты они все
     * @param price    что связка стоит по таблице: сумма цен её сторон
     * @param extra    вес связки — прибавка сверх всего, что дают её части поодиночке и
     *                 более мелкими связками
     * @param error    стандартная ошибка этой прибавки
     * @param together вся сила связки: её стороны, её внутренние пары и сама прибавка
     */
    public record Synergy(List<String> members, Integer pairs, Integer price,
                          Double extra, Double error, Double together, Pairing verdict) {
    }

    /** Приговор связке — не цене половины, а тому, меняет ли вторая половина расклад. */
    public enum Pairing {
        SYNERGY("стороны усиливают друг друга"),
        ANTI("стороны мешают друг другу"),
        PLAIN("стороны независимы");

        private final String label;

        Pairing(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Итог прогона: приговоры по сторонам, найденные связки и курс очка. */
    public record Assessment(List<Judgement> judgements, List<Synergy> synergies,
                             Double pointValue, Integer measured) {
    }

    /**
     * Оценивает цены по замерам прогона.
     *
     * @param empires замеры всех империй всех партий прогона
     * @param prices  цены сторон на миг запуска — снимок, а не нынешний файл
     */
    public Assessment assess(List<Empire> empires, Map<String, Integer> prices) {
        return assess(empires, prices, List.of());
    }

    /**
     * То же, но прогон нёс ПОДСАЖЕННУЮ связку (п. 2.14 этапа 2), и её надо взвесить, даже
     * если сама по себе она в список самых частых пар не попала бы.
     */
    public Assessment assess(List<Empire> empires, Map<String, Integer> prices,
                             List<String> combination) {
        Map<String, Integer> takers = takers(empires, prices, combination);
        Map<String, Measurement> measurements = measure(empires, takers, Yardstick.TOTAL);
        // Из чего сила сложилась — по мерилам порознь. Приговор выносится по сумме, но без
        // разбивки его нечем проверить: «сильно» и «сильно, но только в науке» — разные
        // вещи, и решает их хозяин проекта, а не прибор.
        Map<String, Measurement> production = measure(empires, takers, Yardstick.PRODUCTION);
        Map<String, Measurement> research = measure(empires, takers, Yardstick.RESEARCH);
        Map<String, Measurement> espionage = measure(empires, takers, Yardstick.ESPIONAGE);
        Map<String, Measurement> money = measure(empires, takers, Yardstick.MONEY);
        Map<String, Measurement> military = measure(empires, takers, Yardstick.MILITARY);
        Map<String, Measurement> ground = measure(empires, takers, Yardstick.GROUND);
        Map<String, Measurement> technology = measure(empires, takers, Yardstick.TECHNOLOGY);
        double pointValue = pointValue(measurements, prices);
        double priceLimit = MAX_FAIR_PRICE_BUDGETS
                * prices.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        List<Judgement> judgements = new ArrayList<>();
        for (Map.Entry<String, Integer> priced : prices.entrySet()) {
            String code = priced.getKey();
            Integer price = priced.getValue();
            Measurement measurement = measurements.get(code);
            if (measurement == null) {
                judgements.add(new Judgement(code, price, 0, null, null, null, null, null,
                        null, null, null, null, null, null, Verdict.NOT_MEASURED));
                continue;
            }
            // Справедливая цена — та, при которой сторона легла бы на общую прямую.
            Double fair = pointValue == 0 ? null : measurement.strength() / pointValue;
            if (fair != null && Math.abs(fair) > priceLimit) {
                fair = null;
            }
            Verdict verdict = verdict(measurement, price, pointValue);
            // Совет держится в пределах таблицы, а показанная справедливая цена — нет:
            // пусть видно, насколько сторона выходит за то, что ценами выразимо.
            Double reachable = fair == null ? null : (double) clamp(fair, prices);
            judgements.add(new Judgement(code, price, measurement.takers(),
                    round(measurement.strength()), round(measurement.error()),
                    strengthOf(production, code), strengthOf(research, code),
                    strengthOf(espionage, code),
                    strengthOf(money, code), strengthOf(military, code),
                    strengthOf(ground, code), strengthOf(technology, code),
                    fair == null ? null : round(fair),
                    recommended(price, reachable, verdict),
                    verdict));
        }
        judgements.sort(Comparator.comparing((Judgement one) ->
                one.strength() == null ? Double.MAX_VALUE : -one.strength()));
        return new Assessment(judgements, synergies(empires, prices, combination),
                round(pointValue),
                (int) judgements.stream().filter(one -> one.verdict() != Verdict.NOT_MEASURED).count());
    }

    /**
     * Ищет связки — п. 2.5 этапа 2.
     * <p>
     * <b>Считается вторым замером, а не первым.</b> Столбцы связок меняют и веса самих
     * сторон: то, что раньше приписывалось стороне, частью уходит в связку. Это верно для
     * связок и неверно для приговора цене: цену игрок платит за сторону, в какой бы сборке
     * она ни оказалась, и мерить её надо в среднем по сборкам. Поэтому приговоры остаются
     * с первого замера, а второй отвечает на свой вопрос — меняет ли соседство расклад.
     * <p>
     * Пары берутся не все: их из полусотни сторон больше тысячи. Отбираются те, у которых
     * носителей хватает ({@link #MIN_PAIR_TAKERS}), и из них самые частые — остальные
     * измерить всё равно нечем.
     */
    private List<Synergy> synergies(List<Empire> empires, Map<String, Integer> prices,
                                    List<String> combination) {
        List<String> sides = new ArrayList<>(takers(empires, prices, combination).keySet());
        Map<List<String>, Integer> counts = new LinkedHashMap<>();
        List<List<String>> combos = new ArrayList<>();
        for (int i = 0; i < sides.size(); i++) {
            for (int j = i + 1; j < sides.size(); j++) {
                List<String> pair = canonical(List.of(sides.get(i), sides.get(j)));
                int both = together(empires, pair);
                if (both >= MIN_PAIR_TAKERS) {
                    combos.add(pair);
                    counts.put(pair, both);
                }
            }
        }
        combos.sort(Comparator.comparingInt(one -> -counts.get(one)));
        if (combos.size() > MAX_PAIRS) {
            combos = new ArrayList<>(combos.subList(0, MAX_PAIRS));
        }

        // Подсаженная связка идёт в замер ВСЕГДА — её ради прогон и затевался, — и вместе
        // со всеми парами внутри себя: без них вес тройки собрал бы в себя и то, что дают
        // её пары, и ответ «тройка меняет расклад» получался бы у любой тройки, у которой
        // сильна хоть одна пара.
        for (List<String> planted : subsets(combination)) {
            if (!sides.containsAll(planted) || combos.contains(planted)) {
                continue;
            }
            int all = together(empires, planted);
            if (all >= MIN_PAIR_TAKERS) {
                combos.add(planted);
                counts.put(planted, all);
            }
        }
        if (combos.isEmpty() || empires.size() <= sides.size() + combos.size() + COVARIATES + 1) {
            return List.of();
        }

        LeastSquares.Fit fit = solve(empires, sides, combos, Yardstick.TOTAL);
        // Фон — медиана прибавок по всем связкам: у большинства связки нет, и то, что
        // прибор им приписал, и есть его собственный перекос (см. PAIR_ABOVE_BACKGROUND).
        double[] sizes = new double[combos.size()];
        for (int index = 0; index < combos.size(); index++) {
            sizes[index] = Math.abs(fit.weights()[sides.size() + index]);
        }
        java.util.Arrays.sort(sizes);
        double background = sizes[sizes.length / 2];

        List<Synergy> found = new ArrayList<>();
        for (int index = 0; index < combos.size(); index++) {
            List<String> combo = combos.get(index);
            double extra = fit.weights()[sides.size() + index];
            double error = fit.errors()[sides.size() + index];
            boolean stands = error > 0
                    && Math.abs(extra) >= errorsForPairs(combos.size()) * error
                    && Math.abs(extra) >= PAIR_ABOVE_BACKGROUND * background;
            Pairing verdict = !stands ? Pairing.PLAIN
                    : extra > 0 ? Pairing.SYNERGY : Pairing.ANTI;
            found.add(new Synergy(combo, counts.get(combo),
                    combo.stream().mapToInt(prices::get).sum(),
                    round(extra), round(error),
                    round(whole(combo, combos, sides, fit)), verdict));
        }
        // Связка, которая ничего не меняет, — не находка: наверх идут самые сильные.
        found.sort(Comparator.comparing((Synergy one) -> -Math.abs(one.extra())));
        return found;
    }

    /** У скольких империй взяты ВСЕ стороны связки сразу. */
    private int together(List<Empire> empires, List<String> combo) {
        return (int) empires.stream()
                .filter(one -> one.traits().containsAll(combo))
                .count();
    }

    /**
     * Вся сила связки: её стороны поодиночке, её внутренние связки и она сама.
     * <p>
     * У пары это просто «половины плюс прибавка». У тройки внутрь входят ещё и три её пары,
     * и без них «вместе» у тройки оказалось бы меньше, чем у любой из её пар, — число,
     * которое читать нельзя.
     */
    private double whole(List<String> combo, List<List<String>> combos, List<String> sides,
                         LeastSquares.Fit fit) {
        double sum = combo.stream().mapToDouble(one -> fit.weights()[sides.indexOf(one)]).sum();
        for (int index = 0; index < combos.size(); index++) {
            List<String> other = combos.get(index);
            if (combo.containsAll(other)) {
                sum += fit.weights()[sides.size() + index];
            }
        }
        return sum;
    }

    /**
     * Сама связка и все связки внутри неё: у тройки это она сама и три её пары, у пары —
     * только она сама.
     */
    private List<List<String>> subsets(List<String> combination) {
        if (combination == null || combination.size() < 2) {
            return List.of();
        }
        List<List<String>> all = new ArrayList<>();
        all.add(canonical(combination));
        if (combination.size() > 2) {
            for (int i = 0; i < combination.size(); i++) {
                for (int j = i + 1; j < combination.size(); j++) {
                    all.add(canonical(List.of(combination.get(i), combination.get(j))));
                }
            }
        }
        return all;
    }

    /**
     * Связка — это НАБОР сторон, и порядок в ней ничего не значит; в замере она обязана
     * быть записана всегда одинаково.
     * <p>
     * Иначе та же пара попадает в модель ДВАЖДЫ — раз в порядке перебора сторон, раз в
     * порядке, каким её назвал хозяин прогона, — а два одинаковых столбца гребень делит
     * между собой пополам. На первом же прогоне с подсаженной тройкой это и вышло: пара
     * «киборги + плохие фермеры» встала двумя строками по 3,90 вместо одной, и связка
     * вдвое сильнее объявлялась вдвое слабее.
     */
    private List<String> canonical(List<String> combo) {
        return combo.stream().sorted().toList();
    }

    private Double strengthOf(Map<String, Measurement> measurements, String code) {
        Measurement measurement = measurements.get(code);
        return measurement == null ? null : round(measurement.strength());
    }

    /**
     * Приговор одной цене: сравнивается не сила с нулём, а измеренная сила с той, которую
     * обещает цена. Сторона за десять очков, дающая столько же, сколько сторона за одно,
     * оценена дорого — хотя сама по себе и сильна.
     */
    private Verdict verdict(Measurement measurement, Integer price, double pointValue) {
        if (measurement.takers() < MIN_TAKERS || measurement.error() <= 0) {
            return Verdict.NOT_MEASURED;
        }
        double promised = pointValue * price;
        double difference = measurement.strength() - promised;
        if (Math.abs(difference) < ERRORS_TO_JUDGE * measurement.error()) {
            return Verdict.FAIR;
        }
        return difference < 0 ? Verdict.TOO_EXPENSIVE : Verdict.TOO_CHEAP;
    }

    /**
     * Куда двигать цену одним шагом — п. 2.4 этапа 2.
     * <p>
     * Только когда замер уверенно спорит с ценой (приговор не «адекватно») и только долей
     * пути к измеренной: {@code c + эта * (справедливая - c)}. Шаг меньше очка не
     * считается движением — цена целая, и округление вернуло бы её на место.
     */
    private Integer recommended(Integer price, Double fair, Verdict verdict) {
        if (fair == null || verdict == Verdict.FAIR || verdict == Verdict.NOT_MEASURED) {
            return null;
        }
        long moved = Math.round(price + DAMPING * (fair - price));
        return moved == price ? null : (int) moved;
    }

    /**
     * Курс очка: наклон прямой «цена — измеренная сила» по ВСЕМ измеренным сторонам,
     * взвешенным точностью.
     * <p>
     * Прямая строится через начало координат: сторона ценой ноль обязана давать ноль, и
     * свободный член тут был бы не свойством игры, а смещением выборки.
     * <p>
     * <b>Почему по всем, а не по различимым.</b> Сперва в прямую шли только стороны,
     * уверенно отличимые от нуля, — и это оказалось отбором по самой измеряемой величине:
     * в выборку попадали ровно те, у кого сила велика, и курс завышался впятеро (0,41
     * против 0,056 на одних и тех же данных). После него сорок сторон из пятидесяти двух
     * объявлялись «слишком дорогими» — не потому, что они дороги, а потому, что прямая
     * проведена по лучшим. Слабо измеренная сторона не выбрасывается, а входит с малым
     * весом: 1/ошибка², как и положено при разной точности замеров.
     * <p>
     * Проверка на здравый смысл: полученный так курс (около 0,05 п.п. на очко) сходится с
     * независимым замером этапа 1, где весь бюджет в пятнадцать очков двигал долю
     * выработки на 0,45 п.п.
     */
    private double pointValue(Map<String, Measurement> measurements, Map<String, Integer> prices) {
        double top = 0;
        double bottom = 0;
        for (Map.Entry<String, Measurement> measured : measurements.entrySet()) {
            Measurement measurement = measured.getValue();
            Integer price = prices.get(measured.getKey());
            if (price == null || price == 0 || measurement.error() <= 0) {
                continue;
            }
            double weight = 1.0 / (measurement.error() * measurement.error());
            top += weight * price * measurement.strength();
            bottom += weight * (double) price * price;
        }
        return bottom == 0 ? 0 : top / bottom;
    }

    /**
     * Веса сторон регрессией — п. 2.2 этапа 2.
     * <p>
     * Столбцы: по одному на сторону, которую взяли достаточно часто, плюс две ковариаты
     * стартового угла и свободный член. Ответ — доля выработки, приведённая к нулю внутри
     * партии.
     */
    private Map<String, Measurement> measure(List<Empire> empires, Map<String, Integer> takers,
                                             Yardstick yardstick) {
        if (takers.isEmpty() || empires.size() <= takers.size() + COVARIATES + 1) {
            return Map.of();
        }
        List<String> columns = new ArrayList<>(takers.keySet());
        LeastSquares.Fit fit = solve(empires, columns, List.of(), yardstick);
        Map<String, Measurement> measured = new LinkedHashMap<>();
        for (int column = 0; column < columns.size(); column++) {
            measured.put(columns.get(column), new Measurement(
                    takers.get(columns.get(column)),
                    fit.weights()[column],
                    fit.errors()[column]));
        }
        return measured;
    }

    /**
     * Стороны, которые вообще можно мерить: носителей достаточно и они не у всех подряд.
     * <p>
     * Считаем только те, у которых есть носители: столбец из одних нулей ничего не
     * объясняет, а место в матрице занимает.
     */
    private Map<String, Integer> takers(List<Empire> empires, Map<String, Integer> prices) {
        return takers(empires, prices, List.of());
    }

    private Map<String, Integer> takers(List<Empire> empires, Map<String, Integer> prices,
                                        List<String> combination) {
        Map<String, Integer> takers = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> priced : prices.entrySet()) {
            // Бесплатная сторона — ОПОРА, а не столбец. Строй есть в каждой сборке, и
            // столбцы группы в сумме дают свободный член: система вырождается, а веса
            // перестают быть определёнными — они и так известны лишь с точностью до
            // общего сдвига. Оставив диктатуру опорой, мы этот сдвиг и закрепляем: вес
            // каждой стороны читается как «сверх бесплатной».
            if (priced.getValue() == 0) {
                continue;
            }
            String code = priced.getKey();
            int count = (int) empires.stream().filter(one -> one.traits().contains(code)).count();
            // Потолок доли не касается ПОДСАЖЕННЫХ сторон. Потолок стоит там потому, что у
            // стороны, которую взяли почти все, «не взявшие» — это купившие вместо неё
            // что-то другое из той же группы. У подсаженной стороны всё наоборот:
            // отсутствие ей назначено жребием, ровно половине сборок, и сравнивать есть с
            // чем. Без этой оговорки подсадка сама себя и хоронила бы — доля подсаженной
            // стороны как раз около половины.
            boolean planted = combination.contains(code);
            if (count >= MIN_TAKERS
                    && (planted || count <= empires.size() * MAX_SHARE_OF_TAKERS)) {
                takers.put(code, count);
            }
        }
        return takers;
    }

    /**
     * Собирает матрицу замера и решает её: столбец на сторону, столбец на связку, две
     * ковариаты стартового угла и свободный член.
     * <p>
     * Столбец связки — единица там, где взяты все её стороны сразу. Сами стороны при этом
     * обязаны идти своими столбцами, иначе вес связки соберёт в себя и то, что даёт каждая
     * из них по отдельности; у тройки по той же причине рядом стоят и три её пары.
     */
    private LeastSquares.Fit solve(List<Empire> empires, List<String> columns,
                                   List<List<String>> pairs, Yardstick yardstick) {
        int width = columns.size() + pairs.size() + COVARIATES + 1;
        double[][] matrix = new double[empires.size()][width];
        double[] answer = new double[empires.size()];
        for (int row = 0; row < empires.size(); row++) {
            Empire empire = empires.get(row);
            for (int column = 0; column < columns.size(); column++) {
                matrix[row][column] = empire.traits().contains(columns.get(column)) ? 1 : 0;
            }
            for (int index = 0; index < pairs.size(); index++) {
                matrix[row][columns.size() + index] =
                        empire.traits().containsAll(pairs.get(index)) ? 1 : 0;
            }
            int start = columns.size() + pairs.size();
            matrix[row][start] = empire.nearbyPlanets() == null ? 0 : empire.nearbyPlanets();
            matrix[row][start + 1] = empire.nearestRival() == null ? 0 : empire.nearestRival();
            matrix[row][width - 1] = 1;
            answer[row] = value(empire, yardstick);
        }
        return LeastSquares.solve(matrix, answer, RIDGE_SHARE * empires.size());
    }

    private record Measurement(Integer takers, Double strength, Double error) {
    }

    /**
     * <b>Невязка цены — мера успеха круга балансировки</b> (п. 3.59 журнала): медиана по
     * сторонам от «на сколько очков цена расходится с тем, что сторона даёт».
     * <p>
     * <b>Зачем понадобилась.</b> Успех мерили ЧИСЛОМ АДЕКВАТНЫХ, и это оказалось не мерой
     * баланса, а мерой размытости прогона: «оценена адекватно» значит и «не отличается от
     * цены», и <i>«нечем отличить»</i>, поэтому слепой прогон объявляет адекватными всех.
     * Измерено по повторам на ОДНОЙ таблице цен: в одной тройке прогонов адекватных вышло
     * 20, 40 и 27 — размах больше, чем весь путь журнала от первого прогона до лучшего, —
     * а по 37 прогонам связь «больше медианная ошибка → больше адекватных» даёт r = +0,83.
     * Невязка на тех же повторах стояла: 6,4 / 6,4 / 6,8.
     * <p>
     * <b>Почему она этому не поддаётся.</b> Полоска ошибки в неё не входит вовсе: считается
     * разница между ценой и тем, что сторона заработала, а не «отличимо ли одно от другого».
     * Размытому прогону нечем себя приукрасить.
     * <p>
     * Берётся МЕДИАНА, а не среднее: у насыщенных сторон (киборги, плохие фермеры) честная
     * цена выходит за два десятка очков при потолке анти-выбора в десять, и среднее они
     * перетянули бы на себя целиком. Считается по сырой силе, а не по {@code fairPrice}:
     * последняя у таких сторон нарочно пуста, и выбросив их, мы мерили бы баланс по тем
     * сторонам, где он и так в порядке.
     *
     * @param judgements приговоры прогона; сторона без замера в счёт не идёт
     * @param pointValue измеренный курс очка — им сила и переводится в очки
     * @return невязка в ОЧКАХ цены, или пусто, если мерить нечем
     */
    public Double residual(List<Judgement> judgements, Double pointValue) {
        if (judgements == null || pointValue == null || pointValue == 0) {
            return null;
        }
        List<Double> gaps = judgements.stream()
                .filter(one -> one.verdict() != Verdict.NOT_MEASURED)
                .filter(one -> one.strength() != null && one.price() != null)
                .map(one -> Math.abs(one.strength() / pointValue - one.price()))
                .sorted()
                .toList();
        if (gaps.isEmpty()) {
            return null;
        }
        int middle = gaps.size() / 2;
        double median = gaps.size() % 2 == 1 ? gaps.get(middle)
                : (gaps.get(middle - 1) + gaps.get(middle)) / 2.0;
        return round(median);
    }

    private Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }
}
