package com.moo3.server.service;

import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.enums.BattleSide;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.WeaponKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Числовые правила тактического боя — п. 8, сцена боя MOO II.
 * <p>
 * <b>Что взято из оригинала.</b> Шанс попасть по кораблю задаёт размер его корпуса:
 * 20 % по фрегату, 30 по эсминцу, 40 по крейсеру, 50 по дредноуту, 80 по титану и 100 по
 * Leviathan ({@link ShipHull#hitChancePercent()}). Прицельный компьютер поднимает шанс,
 * постановщик помех роняет. Урон снимается щитом, потом бронёй, и только остаток бьёт
 * по прочности корпуса — порядок MOO II.
 * <p>
 * <b>Что реконструировано.</b> Поле в MOO II шестиугольное, здесь клетчатое: соседняя
 * клетка по диагонали считается такой же соседней, как по прямой (расстояние Чебышёва).
 * Дальности у оружия в справочнике нет вовсе — она одна на все стволы
 * ({@link #WEAPON_RANGE}), иначе стрелять было бы можно через всё поле и движение
 * потеряло бы смысл. Инициатива корабля считается от скорости и прицельного компьютера:
 * в MOO II очередь задают двигатели и приборы, а других чисел про очередь игра не
 * публиковала.
 * <p>
 * <b>Чего в правилах пока нет, хотя в оригинале есть.</b> Урон и размеры оружия взяты у
 * MOO II (`ship-components.json`), а два его свойства — нет: торпеда в оригинале бьёт
 * ЧЕРЕЗ ХОД (зато её нельзя сбить, и это у нас есть), а ионная импульсная пушка минует
 * броню с корпусом и попадает прямо в системы. И то и другое — точки расширения: первое
 * ложится в саму стрельбу ({@code TacticalBattleService.fire}), второе — в разбор
 * попадания рядом с поломкой систем ({@link #rollSystemHit}). Пока оба оружия бьют как
 * обычные, и в их урон это не заложено: числа остаются оригинальскими.
 */
@Service
public class BattleRules {

    /**
     * Ширина поля боя в клетках — п. 8.
     * <p>
     * <b>Размер поля — реконструкция по трём числам оригинала</b>, потому что саму сетку
     * MOO II не публикует: корабли там проходят «12–14 клеток за ход» (и augmented
     * engines добавляют «+5 клеток»), луч теряет силу «до 35 % на 21–23 клетках», а экран
     * боя показывает часть поля в пропорции 8:5. Отсюда 32 на 20: ход корабля — треть
     * поля, вся таблица ослабления луча помещается, а пропорция совпадает с экранной.
     * <p>
     * Прежние 20×12 были придуманы до того, как в игре появилась боевая скорость
     * оригинала: с ней фрегат пересекал такое поле целиком за один ход.
     */
    public static final int FIELD_WIDTH = 32;

    /** Высота поля боя в клетках — п. 8, см. {@link #FIELD_WIDTH}. */
    public static final int FIELD_HEIGHT = 20;

    /**
     * Дальность залпа в клетках — одна на все стволы.
     * <p>
     * Двадцать четыре: столько считает таблица ослабления луча MOO II — от полного урона
     * вплотную до 35 % на 21–23 клетках. Дальше выстрел не летит вовсе: у оригинала этот
     * край тоже последний.
     */
    public static final int WEAPON_RANGE = 24;

    /** Меньше этого шанс попасть не падает: даже юркий фрегат ловит случайный залп. */
    private static final int MIN_HIT_PERCENT = 5;

    /**
     * Сколько очков перевеса меткости над защитой удваивают отношение шансов — MOO II,
     * знаменатель 16 в формуле попадания.
     */
    private static final int HIT_RATING_STEP = 16;

    /** Половина: столько даёт кривая попадания при равных меткости и защите. */
    private static final double EVEN_ODDS_PERCENT = 50.0;

    /**
     * На сколько процентов слабеет луч с каждыми тремя клетками пути — п. 8.
     * <p>
     * Числа оригинала: вплотную (0–2 клетки) луч бьёт в полную силу, дальше теряет по
     * десятой доле за каждые три клетки — 90 % на 3–5, 80 % на 6–8 и так далее, — и ниже
     * {@link #MIN_DAMAGE_PERCENT} не опускается.
     */
    private static final int DISSIPATION_PERCENT_PER_STEP = 10;

    /** Клеток в одной ступени ослабления. */
    private static final int DISSIPATION_STEP_CELLS = 3;

    /** Слабее этого луч не становится: 35 % оригинала на 21–23 клетках. */
    private static final int MIN_DAMAGE_PERCENT = 35;

    /** Код модификации ближней обороны: ею сбивают чужие ракеты — п. 8. */
    private static final String POINT_DEFENCE = "point-defense";

    /** Сколько процентов перехвата даёт один ствол ближней обороны — реконструкция. */
    private static final int POINT_DEFENCE_PERCENT = 20;

    /** Выше этого перехват не поднимается: оборона не оставляет совсем без ракет. */
    private static final int POINT_DEFENCE_MAX_PERCENT = 80;

    /** Сколько процентов перехвата снимает каждая единица скорости ракеты. */
    private static final int MISSILE_SPEED_PERCENT = 5;

    /**
     * Какую долю брони и корпуса кибернетическая раса чинит между кругами боя — п. 7.
     * <p>
     * Число MOO II: «корабли такой расы чинят 10% брони и структуры за ход сражения».
     * <b>Выбитые системы она не чинит</b> — это точка расширения: поломка систем в игре
     * теперь есть ({@link #SYSTEM_HIT_PERCENT}), а починки их посреди боя пока нет.
     */
    public static final int CYBERNETIC_REPAIR_PERCENT = 10;

    /**
     * Какая доля попаданий ПО КОРПУСУ выбивает бортовую систему — п. 8.
     * <p>
     * Правило MOO II: щит и броня держат удар, а всё, что прошло сквозь них, рвёт корабль
     * изнутри — гасит пушку, разбивает двигатель, сжигает прицел. Самого числа игра не
     * публиковала, и оно <b>реконструировано по длине боя</b>: корабль доживает до гибели
     * за пять-десять попаданий по корпусу, и при трети таких попаданий он теряет одну-две
     * системы — то есть доживает искалеченным, как в оригинале, а не целым до последнего
     * очка прочности.
     */
    public static final int SYSTEM_HIT_PERCENT = 30;

    /**
     * Вероятность того, что разбитый двигатель взорвёт корабль, — п. 8.
     * <p>
     * Число — решение хозяина проекта: у реактора, которому разбили привод, четверть
     * шансов уйти вместе с кораблём. Взрыв бьёт соседей ({@link #blastDamage}), поэтому
     * стоять кучей возле подбитого опасно и своим.
     */
    public static final int ENGINE_BLAST_PERCENT = 25;

    /** Сила взрыва: доля ПОЛНОЙ прочности корпуса — большой корабль рвёт сильнее. */
    private static final int BLAST_DAMAGE_PERCENT = 50;

    /** Взрыв достаёт соседей на клетку, а у крупных корпусов — на две. */
    private static final int BLAST_RADIUS = 1;
    private static final int BIG_BLAST_RADIUS = 2;

    /** С какой прочности корпус считается крупным: дредноут и выше. */
    private static final int BIG_HULL_STRUCTURE = 160;

    /**
     * Доля погибших кораблей, взрывающихся в БЫСТРОМ бою, — п. 8.
     * <p>
     * Перевод того же правила на бой без поля: там нет ни систем, ни клеток, зато есть
     * потери. Число выведено, а не назначено: попадание по корпусу выбивает систему в
     * {@link #SYSTEM_HIT_PERCENT} случаев, двигатель — примерно одна система из пяти, а
     * взрывается он в {@link #ENGINE_BLAST_PERCENT} случаев; за пять-десять попаданий,
     * которые корабль переживает до гибели, это и даёт около десятой доли.
     */
    public static final int FAST_BLAST_PERCENT = 10;

    /** Бортовая система: двигатель. Выбит — корабль неподвижен и с поля не уйдёт. */
    public static final String ENGINE_SYSTEM = "ENGINE";

    /** Бортовая система: щит. Выбит — держать удар больше нечем. */
    public static final String SHIELD_SYSTEM = "SHIELD";

    /** Бортовая система: прицельный компьютер. Выбит — прицел падает до голого. */
    public static final String COMPUTER_SYSTEM = "COMPUTER";

    /**
     * Сколько чинится за круг: доля от целого, но не меньше единицы, пока чинить есть что.
     * <p>
     * Не меньше единицы потому, что у фрегата десятая доля брони — это ноль, и правило
     * оригинала на мелких кораблях не работало бы вовсе.
     */
    public Integer repaired(Integer now, Integer full) {
        if (now >= full) {
            return full;
        }
        return Math.min(full, now + Math.max(1, full * CYBERNETIC_REPAIR_PERCENT / 100));
    }

    /**
     * Инициатива корабля — п. 8: по ней строится очередь хода.
     * <p>
     * Реконструкция: в MOO II очередь задают двигатели и приборы корабля. Скорость взята
     * с весом сто, прицельный компьютер добавляется процентами — так быстрый разведчик
     * всегда ходит раньше тяжёлого корабля, а из двух одинаково быстрых первым стреляет
     * тот, у кого лучше прицел.
     */
    public Integer initiative(ShipStats stats, List<ShipDesignRules.Item> items) {
        return stats.speed() * 100 + attackPercent(items);
    }

    /** Прибавка приборов к прицелу в процентах: компьютер, сканер, анализатор. */
    public Integer attackPercent(List<ShipDesignRules.Item> items) {
        int percent = 0;
        for (ShipDesignRules.Item item : items) {
            percent += item.component().amount(ShipEffectType.ATTACK_PERCENT) * item.count();
        }
        return percent;
    }

    /**
     * Шанс попасть по цели в процентах — п. 8, формула попадания MOO II.
     * <p>
     * Оригинал считает попадание не сложением процентов, а кривой:
     * <pre>100 / (1 + 2^(−(BA − BD) / 16))</pre>
     * где BA — меткость стреляющего (прицельный компьютер, модификации ствола, раса), а
     * BD — защита цели (помехи, стабилизаторы). Шестнадцать очков перевеса удваивают
     * отношение шансов, поэтому прибавка в четверть сотни очков не значит «плюс 25 % к
     * попаданию»: у меткого корабля она добавляет немного, у неметкого — почти вдвое.
     * Сложением процентов, как было раньше, модификации ствола выходили втрое сильнее,
     * чем в оригинале.
     * <p>
     * <b>Что реконструировано.</b> Сама кривая даёт ровно половину при равных BA и BD, а
     * размера цели не знает — в MOO II размер сидит в её защите. У нас размер корпуса
     * хранится готовым шансом попадания (20 % по фрегату, 100 % по Leviathan, справочник
     * корпусов), поэтому кривая берётся множителем к нему: при равных меткости и защите
     * выходит ровно табличный шанс корпуса, а перевес двигает его по той же кривой
     * оригинала. Ниже {@link #MIN_HIT_PERCENT} шанс не опускается и выше ста не
     * поднимается.
     *
     * @param targetHull    корпус цели: его табличный шанс и есть точка равновесия
     * @param attackRating  меткость стреляющего (BA)
     * @param targetDefense защита цели <b>сверх</b> собственной юркости корпуса (BD):
     *                      помехи, стабилизаторы и раса. Уклонение самого корпуса сюда не
     *                      входит — оно уже сидит в табличном шансе MOO II, и вычитать его
     *                      второй раз значило бы сделать неуязвимым любой фрегат
     */
    public Integer hitChancePercent(ShipHull targetHull, Integer attackRating, Integer targetDefense) {
        double curve = 100.0 / (1.0 + Math.pow(2.0,
                -(attackRating - targetDefense) / (double) HIT_RATING_STEP));
        int chance = (int) Math.round(targetHull.hitChancePercent() * curve / EVEN_ODDS_PERCENT);
        return Math.max(MIN_HIT_PERCENT, Math.min(100, chance));
    }

    /**
     * Надбавка к защите сверх собственной юркости корпуса: помехи, стабилизаторы и раса.
     * <p>
     * Защита проекта ({@link ShipStats#defense()}) считается от уклонения корпуса, а оно
     * в бою уже учтено шансом попасть по корпусу такого размера. Поэтому в бой идёт
     * только то, что защита даёт сверх голого корпуса.
     */
    public Integer defenceBonus(ShipHull hull, ShipStats stats) {
        return Math.max(0, stats.defense() - hull.evasion());
    }

    /**
     * Один выстрел залпа — п. 8.
     * <p>
     * Выстрел несёт с собой всё, что решает его судьбу: урон, надбавку модификаций к
     * меткости <b>этого ствола</b> и то, проходит ли он броню и щит. Раньше выстрел был
     * просто числом урона, и модификации оружия рассказать о себе было нечем.
     *
     * @param kind          вид оружия: по нему сцена рисует выстрел, а ракеты ещё и
     *                      спотыкаются о постановщик помех
     * @param attackPercent надбавка модификаций к меткости, в процентных пунктах
     */
    public record Shot(WeaponKind kind,
                       Integer damage,
                       Integer attackPercent,
                       Boolean piercesArmour,
                       Boolean piercesShield,
                       /** Помехозащита ракеты (ECCM): уклонение цели считается вполовину. */
                       Boolean halvesEvasion,
                       /** Насколько крепче ракета (ARM): настолько же труднее её сбить. */
                       Integer missileArmourPercent,
                       /** Насколько ракета быстрее (FST): перехватить её труднее. */
                       Integer missileSpeed,
                       /** Наведение по излучению (EMG): попадание выбивает двигатель цели. */
                       Boolean hitsEngine,
                       /** Дальность этого ствола в клетках: HV бьёт дальше, PD ближе. */
                       Integer range,
                       /** Обволакивающий удар (ENV): цель получает его всеми сторонами разом. */
                       Boolean envelops,
                       /** Расстояние не ослабляет удар (NR). */
                       Boolean noRangePenalty) {

        /** Выстрел без переделок — так собираются простые стволы в проверках. */
        public Shot(WeaponKind kind, Integer damage, Integer attackPercent,
                    Boolean piercesArmour, Boolean piercesShield) {
            this(kind, damage, attackPercent, piercesArmour, piercesShield,
                    Boolean.FALSE, 0, 0, Boolean.FALSE, WEAPON_RANGE,
                    Boolean.FALSE, Boolean.FALSE);
        }
    }

    /**
     * Выстрелы одного залпа: у каждого ствола свой урон, и стволов бывает много.
     * <p>
     * Залп разбивается на отдельные выстрелы потому, что попадание разыгрывается для
     * каждого, а щит цели гасит каждый выстрел по отдельности — как в MOO II, где щит
     * держит удар, а не суммарный урон.
     */
    public List<Shot> shots(List<ShipDesignRules.Item> items) {
        List<Shot> shots = new ArrayList<>();
        for (ShipDesignRules.Item item : items) {
            ShipComponent component = item.component();
            if (component.slot() != ShipComponentSlot.WEAPON) {
                continue;
            }
            // Число выстрелов берётся у состава: разделяющаяся ракета (MIRV) несёт четыре
            // боеголовки — п. 8.
            int count = item.shots() * item.count();
            // Вида может не быть у пушки из старого справочника — считаем её снарядом:
            // не рисовать выстрел вовсе хуже, чем нарисовать привычным.
            WeaponKind kind = component.weaponKind() == null
                    ? WeaponKind.PROJECTILE : component.weaponKind();
            for (int shot = 0; shot < count; shot++) {
                shots.add(new Shot(kind, item.damage(), item.attackPercent(),
                        item.piercesArmour(), item.piercesShield(),
                        item.halvesEvasion(), item.missileArmourPercent(),
                        item.missileSpeed(), item.hitsEngine(),
                        item.rangeFrom(WEAPON_RANGE), item.envelops(),
                        item.noRangePenalty()));
            }
        }
        return shots;
    }

    /**
     * Те же выстрелы, но разложенные по виду оружия — п. 8.
     * <p>
     * Числа боя от вида не зависят: урон, попадание и щит считаются одинаково. Разложение
     * нужно сцене — по нему луч, снаряды и ракеты рисуются по-разному, и видно, кто в кого
     * стреляет и чем. Порядок видов сохраняется: сперва то, что стоит раньше в проекте.
     */
    public Map<WeaponKind, List<Shot>> shotsByKind(List<ShipDesignRules.Item> items) {
        Map<WeaponKind, List<Shot>> shots = new LinkedHashMap<>();
        for (Shot shot : shots(items)) {
            shots.computeIfAbsent(shot.kind(), key -> new ArrayList<>()).add(shot);
        }
        return shots;
    }

    /**
     * Шанс попасть этим выстрелом — п. 8.
     * <p>
     * Меткость модификации самого ствола (ближняя оборона и скорострельное бьют метче,
     * автоматический огонь — хуже) идёт в BA, а уклонение цели от ракет — в BD: считает
     * всё та же кривая попадания MOO II, {@link #hitChancePercent}. Уклонение касается
     * только ракет — постановщик помех сбивает наведение, лучу и снаряду сбивать нечего.
     */
    public Integer shotHitChancePercent(ShipHull targetHull, Integer attackRating,
                                        Integer targetDefense, Shot shot,
                                        Integer missileEvasion) {
        int defence = targetDefense;
        if (shot.kind() == WeaponKind.MISSILE) {
            // Помехозащита (ECCM) режет чужое уклонение вдвое — п. 8.
            int evasion = Math.max(0, missileEvasion);
            defence += Boolean.TRUE.equals(shot.halvesEvasion()) ? evasion / 2 : evasion;
        }
        return hitChancePercent(targetHull, attackRating + shot.attackPercent(), defence);
    }

    /**
     * Сколько у корабля стволов ближней обороны — п. 8: ими и сбивают чужие ракеты.
     * <p>
     * Считаются стволы с модификацией «ближняя оборона»: в MOO II она для того и нужна —
     * «такими стволами сбивают ракеты и истребители».
     */
    public Integer pointDefenceGuns(List<ShipDesignRules.Item> items) {
        int guns = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.WEAPON
                    && item.modifications().stream()
                            .anyMatch(modification -> POINT_DEFENCE.equals(modification.code()))) {
                guns += item.count();
            }
        }
        return guns;
    }

    /**
     * Шанс сбить чужую ракету ближней обороной, в процентах, — п. 8.
     * <p>
     * <b>Реконструкция.</b> В MOO II ракета летит по полю, и стволы ближней обороны бьют
     * по ней в пути: крепкая ракета (ARM) держит вдвое больше попаданий, быстрая (FST)
     * реже попадает под огонь. Полёта ракет наша игра не считает — залп разыгрывается
     * разом, — поэтому перехват сведён к одному броску: каждый ствол ближней обороны даёт
     * {@link #POINT_DEFENCE_PERCENT} процентов, броня ракеты делит их на столько же,
     * на сколько крепче сама ракета, а скорость отнимает по
     * {@link #MISSILE_SPEED_PERCENT} за единицу. Выше {@link #POINT_DEFENCE_MAX_PERCENT}
     * шанс не поднимается: совсем без ракет оборона не оставляет.
     */
    public Integer interceptChancePercent(Integer guns, Shot shot) {
        if (shot.kind() != WeaponKind.MISSILE || guns <= 0) {
            return 0;
        }
        int chance = Math.min(POINT_DEFENCE_MAX_PERCENT, guns * POINT_DEFENCE_PERCENT);
        chance = chance * 100 / (100 + Math.max(0, shot.missileArmourPercent()));
        chance -= Math.max(0, shot.missileSpeed()) * MISSILE_SPEED_PERCENT;
        return Math.max(0, chance);
    }

    /**
     * Клеток поля за ход — п. 8: <b>боевая скорость двигателя как есть</b>.
     * <p>
     * Число оригинала идёт в бой напрямую: у ядерного двигателя двенадцать клеток, и поле
     * под него и размечено (32 клетки — почти три хода). Делить её больше не на что —
     * прежний делитель был поправкой на поле вчетверо мельче настоящего.
     * <p>
     * У проекта без двигателя из справочника (старые партии) остаётся его звёздная
     * скорость: меньше одной клетки за ход не ходит никто.
     */
    public Integer cellsPerTurn(ShipStats stats) {
        int combat = stats.combatSpeed() == null ? 0 : stats.combatSpeed();
        return combat > 0 ? combat : Math.max(1, stats.speed());
    }

    /**
     * Урон выстрела, дошедший до цели через расстояние, — п. 8.
     * <p>
     * Правило MOO II: луч рассеивается в пути и с каждыми тремя клетками теряет по
     * десятой доле силы — 100 % вплотную, 90 % на 3–5 клетках, 80 % на 6–8 и так далее до
     * 35 % на 21–23. Снаряды и ракеты долетают целыми: рассеиваться в них нечему.
     */
    public Integer damageAtRange(Shot shot, Integer distance) {
        if (shot.kind() != WeaponKind.BEAM || Boolean.TRUE.equals(shot.noRangePenalty())) {
            return shot.damage();
        }
        int steps = Math.max(0, distance) / DISSIPATION_STEP_CELLS;
        int percent = Math.max(MIN_DAMAGE_PERCENT, 100 - steps * DISSIPATION_PERCENT_PER_STEP);
        return Math.max(1, shot.damage() * percent / 100);
    }

    /**
     * Урон одного выстрела, дошедший до корабля: щит гасит удар целиком, если тот слабее
     * щита. Так тяжёлый ствол пробивает щит, а рой мелких об него разбивается — правило
     * MOO II, ради которого там и ставят большие пушки.
     */
    public Integer damageThroughShield(Integer shotDamage, Integer shield) {
        return Math.max(0, shotDamage - shield);
    }

    /**
     * Доходит ли этот ствол до цели — п. 8: дальность у каждого своя, потому что тяжёлая
     * установка бьёт вдвое дальше, а ближняя оборона вдвое ближе.
     */
    public Boolean shotReaches(Shot shot, Integer distance) {
        return distance <= (shot.range() == null ? WEAPON_RANGE : shot.range());
    }

    /**
     * Во сколько раз обволакивающий удар (ENV) сильнее обычного — п. 8.
     * <p>
     * Само число живёт в {@link ShipDesignRules#ENVELOPING_SIDES} — там же, где считается
     * сила проекта: множитель нужен обоим, и одно число в двух местах однажды разойдётся.
     * Прошедший щит урон учитывается вчетверо.
     */
    public Integer envelopingFactor(Shot shot) {
        return Boolean.TRUE.equals(shot.envelops()) ? ShipDesignRules.ENVELOPING_SIDES : 1;
    }

    /** Расстояние между клетками поля: диагональ считается за один шаг. */
    public Integer distance(Integer fromX, Integer fromY, Integer toX, Integer toY) {
        return Math.max(Math.abs(fromX - toX), Math.abs(fromY - toY));
    }

    /** Достанет ли залп до цели с этого места. */
    public Boolean inRange(Integer fromX, Integer fromY, Integer toX, Integer toY) {
        return distance(fromX, fromY, toX, toY) <= WEAPON_RANGE;
    }

    /**
     * Насколько далеко бьёт корабль — по самому дальнобойному своему стволу (п. 8).
     * <p>
     * Дальность перестала быть одной на всех: тяжёлая установка (HV) бьёт вдвое дальше,
     * ближняя оборона (PD) — вдвое ближе. Подходить к цели корабль должен на выстрел
     * хоть чем-нибудь, а долетит ли до неё каждый конкретный ствол, решает
     * {@link #shotReaches(Shot, Integer)} уже в залпе.
     */
    public Integer reach(List<Shot> shots) {
        return shots.stream()
                .mapToInt(shot -> shot.range() == null ? WEAPON_RANGE : shot.range())
                .max()
                .orElse(WEAPON_RANGE);
    }

    /** Достанет ли до цели хоть один ствол корабля. */
    public Boolean inReach(List<Shot> shots, Integer fromX, Integer fromY,
                           Integer toX, Integer toY) {
        return distance(fromX, fromY, toX, toY) <= reach(shots);
    }

    /** Клетка на поле, а не за его краем. */
    public Boolean onField(Integer x, Integer y) {
        return x >= 0 && x < FIELD_WIDTH && y >= 0 && y < FIELD_HEIGHT;
    }

    /**
     * Куда корабль встаёт перед боем — п. 8.
     * <p>
     * Нападающий входит в бой слева, обороняющийся справа, оба строем в столбец по центру поля:
     * так же расставляет флоты MOO II. Строй растягивается от середины, чтобы бой начинали
     * с одинакового расстояния при любом числе кораблей.
     * <p>
     * Строй длиннее поля переходит во второй столбец — за спину первому. Иначе лишние
     * корабли вставали бы на последнюю клетку друг на друга: две единицы в одной клетке
     * поле не различает, и ходить им потом некуда.
     *
     * @param index номер корабля в своём строю, начиная с нуля
     * @param total сколько кораблей в строю — по нему строй и центрируется
     */
    public int[] startingCell(BattleSide side, int index, int total) {
        int column = index / FIELD_HEIGHT;
        int inColumn = index % FIELD_HEIGHT;
        int rows = Math.max(1, Math.min(FIELD_HEIGHT, total - column * FIELD_HEIGHT));
        int first = Math.max(0, (FIELD_HEIGHT - rows) / 2);
        int y = Math.min(FIELD_HEIGHT - 1, first + inColumn);
        int x = side == BattleSide.ATTACKER ? 1 + column : FIELD_WIDTH - 2 - column;
        return new int[]{x, y};
    }

    /** Разыгрывает попадание: {@code true} — выстрел дошёл до цели. */
    public Boolean rollHit(RandomGenerator random, Integer hitChancePercent) {
        return random.nextInt(100) < hitChancePercent;
    }

    /**
     * Что на корабле ещё цело — п. 8: по этому списку и бросается жребий поломки.
     * <p>
     * Система здесь — это <b>гнездо</b>, а не вид: четыре масс-драйвера это четыре
     * системы, и выбивает их по одной. Оттого у крупного корабля с полным трюмом стволов
     * двигатель под ударом реже, чем у фрегата с единственной пушкой, — и это правильно:
     * рвётся то, чего в корабле больше.
     * <p>
     * Брони и корпуса в списке нет: их держит сам корпус, ломать их отдельно нечего.
     * Особые модули не ломаются пока тоже — точка расширения: их действие считается на
     * проекте целиком, а не на корабле.
     */
    public List<String> liveSystems(List<ShipDesignRules.Item> items, List<String> damaged) {
        List<String> live = new ArrayList<>();
        for (ShipDesignRules.Item item : items) {
            switch (item.component().slot()) {
                case WEAPON -> {
                    for (int mount = 0; mount < item.count(); mount++) {
                        live.add(item.component().code());
                    }
                }
                case ENGINE -> live.add(ENGINE_SYSTEM);
                case SHIELD -> live.add(SHIELD_SYSTEM);
                case COMPUTER -> live.add(COMPUTER_SYSTEM);
                default -> {
                    // Броня и особые модули не ломаются — см. javadoc.
                }
            }
        }
        // Уже выбитое вычитается ПО ОДНОМУ: у корабля четыре одинаковых ствола, и
        // разбитый первый не уносит с собой три уцелевших.
        List<String> left = new ArrayList<>(live);
        for (String broken : damaged) {
            left.remove(broken);
        }
        return left;
    }

    /**
     * Состав корабля БЕЗ выбитых систем — п. 8: по нему считаются залп и прицел.
     * <p>
     * Разбитая пушка не стреляет, сожжённый компьютер не целится. Двигатель и щит из
     * состава не убираются: скорость и щит корабль держит своими полями боя, и убрать их
     * отсюда значило бы посчитать поломку дважды.
     */
    public List<ShipDesignRules.Item> partsLeft(List<ShipDesignRules.Item> items,
                                                List<String> damaged) {
        if (damaged.isEmpty()) {
            return items;
        }
        List<String> broken = new ArrayList<>(damaged);
        List<ShipDesignRules.Item> left = new ArrayList<>(items.size());
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.COMPUTER
                    && broken.remove(COMPUTER_SYSTEM)) {
                continue;
            }
            if (item.component().slot() != ShipComponentSlot.WEAPON) {
                left.add(item);
                continue;
            }
            int mounts = item.count();
            while (mounts > 0 && broken.remove(item.component().code())) {
                mounts--;
            }
            if (mounts > 0) {
                left.add(new ShipDesignRules.Item(item.component(), mounts, item.modifications()));
            }
        }
        return left;
    }

    /** Попадание по корпусу выбило систему? */
    public Boolean rollSystemHit(RandomGenerator random) {
        return rollHit(random, SYSTEM_HIT_PERCENT);
    }

    /** Разбитый двигатель взорвал корабль? */
    public Boolean rollEngineBlast(RandomGenerator random) {
        return rollHit(random, ENGINE_BLAST_PERCENT);
    }

    /** Какую систему выбило: жребий по всему, что ещё цело; пусто — ломать нечего. */
    public String pickSystem(RandomGenerator random, List<String> live) {
        return live.isEmpty() ? null : live.get(random.nextInt(live.size()));
    }

    /** Сила взрыва корабля: половина его полной прочности — по всем, кто рядом. */
    public Integer blastDamage(ShipHull hull) {
        return Math.max(1, hull.structure() * BLAST_DAMAGE_PERCENT / 100);
    }

    /** На сколько клеток достаёт взрыв: у дредноута и выше — на две. */
    public Integer blastRadius(ShipHull hull) {
        return hull.structure() >= BIG_HULL_STRUCTURE ? BIG_BLAST_RADIUS : BLAST_RADIUS;
    }

    /**
     * Сколько кораблей победителя унесли с собой взрывы погибших — быстрый бой (п. 8).
     * <p>
     * Жребий бросается на каждого погибшего, а не берётся долей: партия обязана
     * повторяться от прогона к прогону, и случайность у неё выводится из зерна партии.
     */
    public Integer fastBlastLosses(RandomGenerator random, Integer deaths) {
        int blasts = 0;
        for (int death = 0; death < Math.max(0, deaths); death++) {
            if (Boolean.TRUE.equals(rollHit(random, FAST_BLAST_PERCENT))) {
                blasts++;
            }
        }
        return blasts;
    }
}
