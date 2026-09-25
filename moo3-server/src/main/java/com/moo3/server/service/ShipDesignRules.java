package com.moo3.server.service;

import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.WeaponModification;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Числовые правила проекта корабля — п. 8. Всё, что считается по корпусу и компонентам,
 * живёт здесь: экран дизайна, стройка колонии и бой берут числа отсюда и своих формул
 * не заводят.
 * <p>
 * <b>Что взято из MOO II.</b> Шанс попасть по кораблю зависит от размера корпуса
 * (20 % по фрегату, 100 % по Leviathan) — отсюда уклонение {@link ShipHull#evasion()}.
 * Командные очки корпусов (1 у фрегата, 6 у Leviathan) — оттуда же.
 * <p>
 * <b>Что реконструировано.</b> Точных таблиц места, цены и урона игра не публиковала, а
 * StrategyWiki и зеркала вики отвечают отказом (403 и 402). Поэтому лестницы чисел
 * держатся на трёх величинах, которые в проекте уже были (Mass Driver 6, Laser 4,
 * Fusion Beam 10 урона; фрегат 30 места за 16 единиц), и на порядке технологий MOO II.
 * Все числа лежат в {@code ship-components.json} и правятся без пересборки.
 * <p>
 * <b>Правила расчёта.</b> Броня, щит, двигатель и приборы обслуживают весь корабль,
 * поэтому их место и цена растут вместе с корпусом; пушка занимает своё место на любом
 * корпусе. Из-за этого крупный корпус несёт пропорционально больше огня — как в MOO II,
 * где ради этого и строят дредноуты.
 */
@Service
public class ShipDesignRules {

    /**
     * Во сколько раз обволакивающий удар (ENV) сильнее обычного — п. 8.
     * <p>
     * <b>Реконструкция под наш щит.</b> В MOO II у корабля четыре стороны со своими
     * щитами, и такой удар приходится на все четыре разом, каждая получает полную силу.
     * Щит здесь один на корабль, поэтому то же самое выражено множителем.
     * <p>
     * Стоит оно ЗДЕСЬ, а не в правилах боя: множитель нужен обоим — и бою
     * ({@code BattleRules.envelopingFactor}), и силе проекта ({@code salvo}), — а одно
     * число в двух местах однажды разойдётся.
     */
    public static final int ENVELOPING_SIDES = 4;

    /**
     * Ядерный двигатель — Power, уровень 1 («Nuclear Fission»). Без него кораблю нечем
     * лететь, поэтому кораблестроения у империи нет вовсе — п. 8.
     */
    public static final String DRIVE_TECH = "nuclear-drive";

    /**
     * Топливные элементы — Chemistry, уровень 1 («Chemistry»). Без них корабль не отойдёт
     * от родной звезды — см. {@link com.moo3.server.domain.enums.FuelTech}.
     */
    public static final String FUEL_TECH = "standard-fuel-cells";

    /**
     * Звёздная база — орбитальная верфь колонии. Код здания из
     * {@code resources/Buildings/buildings.json}.
     */
    public static final String STAR_BASE = "star-base";

    /**
     * Какого размера корпуса сходят со стапеля колонии без звёздной базы — п. 8.
     * <p>
     * В MOO II звёздная база и есть верфь: «a star dock capable of building ships larger
     * than destroyers». Фрегат и эсминец — два наименьших корпуса, их строит любая
     * колония; всё, что крупнее, требует базы на орбите.
     */
    public static final Integer HULL_SIZE_WITHOUT_STAR_BASE = 2;

    /**
     * Умеет ли империя строить корабли — п. 8.
     * <p>
     * Нужны базовые уровни двух разделов: Power даёт двигатель, Chemistry — топливо. Оба
     * уровня общие: изучив уровень, империя получает все его технологии разом, поэтому
     * достаточно спросить по одной из каждого. До них нет ни окна дизайна, ни корабля
     * в списке стройки колонии — строить было бы не из чего.
     */
    public Boolean shipbuildingAvailable(Set<String> technologies) {
        return technologies.contains(DRIVE_TECH) && technologies.contains(FUEL_TECH);
    }

    /** Чего не хватает для кораблестроения — строкой для игрока; пусто, если хватает. */
    public List<String> missingShipbuildingTechnologies(Set<String> technologies) {
        List<String> missing = new ArrayList<>(2);
        if (!technologies.contains(DRIVE_TECH)) {
            missing.add("Nuclear Fission (Power)");
        }
        if (!technologies.contains(FUEL_TECH)) {
            missing.add("Chemistry (Chemistry)");
        }
        return missing;
    }

    /**
     * Наибольший размер корпуса, который поднимет эта колония, — п. 8: со звёздной базой
     * любой, без неё только два наименьших.
     */
    public Integer maxHullSize(Boolean hasStarBase) {
        return Boolean.TRUE.equals(hasStarBase) ? Integer.MAX_VALUE : HULL_SIZE_WITHOUT_STAR_BASE;
    }

    /**
     * Проект и его состав: компонент и сколько раз он взят. Пушек в проекте бывает
     * несколько одинаковых, поэтому состав — не множество, а пары с количеством.
     */
    public record Item(ShipComponent component, Integer count,
                       /** Модификации этого ствола — п. 8; у прочих гнёзд пусто. */
                       List<WeaponModification> modifications) {

        /** Состав без модификаций — так собираются гнёзда, где их не бывает. */
        public Item(ShipComponent component, Integer count) {
            this(component, count, List.of());
        }

        /** Место одного такого ствола на корпусе — с поправкой модификаций. */
        public Integer spaceOn(ShipHull hull) {
            return scaled(component.spaceOn(hull), WeaponModification::spacePercent);
        }

        /** Цена одного такого ствола — по тому же правилу, что и место. */
        public Integer costOn(ShipHull hull) {
            return scaled(component.costOn(hull), WeaponModification::costPercent);
        }

        /** Урон одного выстрела с поправкой модификаций. */
        public Integer damage() {
            return scaled(component.amount(ShipEffectType.WEAPON_DAMAGE),
                    WeaponModification::damagePercent);
        }

        /**
         * Выстрелов в залпе с поправкой модификаций — п. 8: разделяющаяся ракета (MIRV)
         * несёт четыре боеголовки, и каждая летит своей целью.
         */
        public Integer shots() {
            return scaled(component.amount(ShipEffectType.WEAPON_SHOTS),
                    WeaponModification::shotsPercent);
        }

        /** Насколько крепче сама ракета: настолько же труднее сбить её ближней обороной. */
        public Integer missileArmourPercent() {
            return modifications.stream()
                    .mapToInt(WeaponModification::missileArmourPercent)
                    .sum();
        }

        /** Насколько ракета быстрее обычной — перехватить её тем труднее. */
        public Integer missileSpeed() {
            return modifications.stream().mapToInt(WeaponModification::missileSpeed).sum();
        }

        /**
         * Дальность этого ствола в клетках — п. 8: тяжёлая установка бьёт вдвое дальше,
         * ближняя оборона — вдвое ближе.
         */
        public Integer rangeFrom(Integer base) {
            int total = 100 + modifications.stream()
                    .mapToInt(WeaponModification::rangePercent)
                    .sum();
            return Math.max(1, base * Math.max(0, total) / 100);
        }

        /**
         * Охватывает ли удар цель со всех сторон разом — ENV или сам ствол.
         * <p>
         * Второе — правило оригинала: плазменная пушка и Копьё новы обволакивают цель сами
         * собой, и модификации ENV им не предлагают (`ship-components.json`, признак
         * {@code envelops}).
         */
        public Boolean envelops() {
            return Boolean.TRUE.equals(component.envelops())
                    || modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.envelops()));
        }

        /** Не слабеет ли удар с расстоянием — NR. */
        public Boolean noRangePenalty() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.noRangePenalty()));
        }

        /** Срезает ли ракета уклонение цели вдвое — помехозащита ECCM. */
        public Boolean halvesEvasion() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.halvesEvasion()));
        }

        /** Бьёт ли ракета по двигателю цели, пробившись сквозь щит, — наведение EMG. */
        public Boolean hitsEngine() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.hitsEngine()));
        }

        /** Надбавка модификаций к меткости этого ствола, в процентных пунктах. */
        public Integer attackPercent() {
            return modifications.stream()
                    .mapToInt(WeaponModification::attackPercent)
                    .sum();
        }

        /** Пробивает ли выстрел броню насквозь. */
        public Boolean piercesArmour() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.piercesArmour()));
        }

        /** Держит ли щит такой выстрел. */
        public Boolean piercesShield() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.piercesShield()));
        }

        /**
         * Проценты модификаций складываются, а не перемножаются: в MOO II их и берут по
         * одной-две, а сложение оставляет числа круглыми — тяжёлое скорострельное стоит
         * ровно вдвое с половиной.
         */
        private Integer scaled(Integer base, java.util.function.ToIntFunction<WeaponModification> percent) {
            int total = 100 + modifications.stream().mapToInt(percent).sum();
            return Math.max(1, base * Math.max(0, total) / 100);
        }
    }

    /**
     * Считает характеристики проекта.
     *
     * @param race расовые проценты атаки и защиты кораблей — п. 7; они применяются
     *             последними, поверх приборов
     */
    public ShipStats stats(ShipHull hull, List<Item> items, RaceEffects race) {
        int spacePercent = sum(items, ShipEffectType.SPACE_PERCENT);
        int space = hull.space() * (100 + spacePercent) / 100;

        int spaceUsed = 0;
        int cost = hull.cost();
        for (Item item : items) {
            // Место и цена берутся у состава, а не у компонента: модификация ствола
            // меняет и то и другое — п. 8.
            spaceUsed += item.spaceOn(hull) * item.count();
            cost += item.costOn(hull) * item.count();
        }
        // Феодализм строит корабли на треть дешевле — п. 14. Скидка идёт от готовой цены
        // корпуса с приборами, а не от корпуса: в MOO II дешевле обходится весь корабль,
        // и это касается не только боевых — колониальные и грузовые тоже.
        cost = Math.max(1, cost * (100 + race.shipCostPercent()) / 100);

        int structure = hull.structure() * (100 + sum(items, ShipEffectType.STRUCTURE_PERCENT)) / 100;

        // Броня и щиты покрывают весь корпус, поэтому очки множатся на его размер —
        // тем же множителем, что место и цена. Тяжёлая броня добавляется процентом сверху.
        int armour = sum(items, ShipEffectType.ARMOUR) * hull.systemFactor()
                * (100 + sum(items, ShipEffectType.ARMOUR_PERCENT)) / 100;
        int shield = sum(items, ShipEffectType.SHIELD) * hull.systemFactor();

        int attack = salvo(items) * (100 + sum(items, ShipEffectType.ATTACK_PERCENT)
                + race.shipAttackPercent()) / 100;
        int defense = (hull.evasion() + sum(items, ShipEffectType.DEFENSE))
                * (100 + race.shipDefensePercent()) / 100;

        return new ShipStats(
                space,
                spaceUsed,
                cost,
                structure,
                armour,
                shield,
                sum(items, ShipEffectType.SPEED),
                sum(items, ShipEffectType.COMBAT_SPEED),
                attack,
                defense,
                sum(items, ShipEffectType.MISSILE_EVASION),
                sum(items, ShipEffectType.TROOPS),
                hull.command());
    }

    /**
     * Боевая сила одного корабля: чем он больнее бьёт, дольше живёт и труднее ловится, тем
     * больше весит в бою — п. 8.
     * <p>
     * Реконструкция: сравнивать флоты чем-то нужно, а быстрый бой ({@code
     * stub/SpaceBattleService}) поля не считает. Живучесть — прочность с бронёй и щитом;
     * деление держит числа в том же масштабе, в каком бой считал простые корабли; меньше
     * единицы сила не бывает — корабль на поле всегда что-то да значит.
     * <p>
     * <b>Защита входит тем же множителем, что и атака</b> — решение хозяина проекта, и
     * вот на чём оно стоит. Раньше её в силе не было вовсе: сила была «урон на живучесть»,
     * и сторона расы «пилоты» не меняла НИЧЕГО ни в мериле баланса, ни в исходе боя —
     * партии ИИ решаются только быстрым боём, а он сравнивает ровно эту силу. Измерено:
     * хорошие пилоты -0,24, отличные -0,17 по военному мерилу, то есть ноль. Уклонение
     * при этом в игре есть и работает — но лишь в тактическом бою, в кривой попадания,
     * которой партии ИИ не видят.
     * <p>
     * <b>Почему {@code 100 + защита}, а не голая защита.</b> Уклонение корпуса считается
     * как {@code 100 − шанс попадания}, и у Leviathan, звёздной крепости и наземных
     * батарей оно РАВНО НУЛЮ: по ним попадают всегда. Голый множитель обнулил бы их силу
     * вовсе, чего быть не должно — их защита это броня и щиты, а не верткость. Сотня и
     * есть та база «по мне всегда попадают», от которой уклонение считается прибавкой:
     * фрегат (уклонение 80) весит в 1,8 раза больше, чем весил бы неповоротливым, а
     * звёздная крепость — ровно столько же, сколько и раньше.
     */
    public Integer power(ShipStats stats) {
        int endurance = stats.structure() + stats.armour() + stats.shield();
        long evasion = 100L + Math.max(0, stats.defense());
        return (int) Math.max(1, (long) stats.attack() * endurance * evasion / 10_000L);
    }

    /**
     * Залп проекта: урон всех стволов за один заход, без приборов и расы. Урон берётся у
     * состава — модификация ствола его меняет (п. 8).
     */
    private Integer salvo(List<Item> items) {
        int salvo = 0;
        // Обволакивающий удар входит в залп вчетверо: в бою он и приходится на цель
        // четыре раза (ENVELOPING_SIDES). Без этого сила проекта расходилась бы с боем —
        // а её читают ИИ, сравнение флотов и прибор балансировки.
        for (Item item : items) {
            if (item.component().slot() == ShipComponentSlot.WEAPON) {
                int sides = Boolean.TRUE.equals(item.envelops()) ? ENVELOPING_SIDES : 1;
                salvo += item.damage() * item.shots() * item.count() * sides;
            }
        }
        return salvo;
    }

    /** Сумма одного эффекта по всему проекту — с учётом количества компонентов. */
    private Integer sum(List<Item> items, ShipEffectType type) {
        int total = 0;
        for (Item item : items) {
            total += item.component().amount(type) * item.count();
        }
        return total;
    }
}
