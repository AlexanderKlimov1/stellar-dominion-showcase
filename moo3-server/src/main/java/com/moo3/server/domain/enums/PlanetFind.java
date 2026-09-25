package com.moo3.server.domain.enums;

import java.util.List;
import java.util.Map;

/**
 * Находка на планете — п. 4.1: то, ради чего планету колонизируют, даже когда сама она
 * ничем не хороша.
 * <p>
 * В MOO II у горстки планет есть «special feature»: золотые жилы, самоцветы, туземцы и
 * наследие ушедшей цивилизации. Числа взяты у первоисточника дословно (StrategyWiki,
 * «Which planets to colonize», раздел «Planets with special features»):
 * <ul>
 *   <li>золото даёт «special income» ровно в 5 кредитов за ход, самоцветы — в 10;</li>
 *   <li>туземцы приходят тройками, не платят налога, не переселяются и умеют одно —
 *       пахать, причём лучше любой расы: «Natives produce a food surplus of at least 6»;</li>
 *   <li>артефакты дают первой добравшейся империи «1 or occasionally 2 free
 *       technologies», а колонии на такой планете — «+2 per scientist».</li>
 * </ul>
 * <b>Находка выражена парами «тип эффекта — количество»</b>, как здание: колония не
 * различает, откуда к ней пришла прибавка, и заводить ради находок вторую дорогу в
 * расчётах незачем. Оттого и всех правок в колонии ровно одна строка —
 * {@code ColonyContext.effects}.
 * <p>
 * <b>Туземцы — реконструкция в одном месте.</b> Их трое, они едят и пашут, но жителями
 * колонии не считаются: сделать их населением значило бы дать игроку переставить их на
 * завод и увезти грузовиком, а в оригинале ни того, ни другого нельзя. Поэтому в игру
 * входит их ИЗЛИШЕК — те самые шесть единиц еды, которые называет первоисточник (девять
 * выращенных минус три съеденных), — а три места в колонии они занимают отрицательной
 * прибавкой к вместимости: «imagine that you have a colony with X−3 colonists».
 *
 * @param weight        вес в жребии: у дешёвых находок он больше
 * @param minCapacity   какая вместимость нужна планете, чтобы находка на ней имела смысл
 * @param effects       что находка даёт колонии — теми же парами, что и здание
 * @param freeTechnologies сколько технологий достаётся первому, кто разведал систему
 */
public enum PlanetFind {

    /** Золотые жилы: ровно пять кредитов за ход сверх налога с жителей. */
    GOLD_DEPOSITS(4, 1, Map.of(BuildingEffectType.INCOME_FLAT, 5), 0),

    /** Самоцветы: то же самое, но вдвое дороже — и вдвое реже. */
    GEM_DEPOSITS(2, 1, Map.of(BuildingEffectType.INCOME_FLAT, 10), 0),

    /**
     * Наследие ушедшей цивилизации: технологии тому, кто нашёл, и вечная прибавка к науке
     * тому, кто поселился. Единственная находка, которая даёт что-то ДО колонии.
     */
    ARTIFACTS(3, 1, Map.of(BuildingEffectType.RESEARCH_PER_SCIENTIST, 2), 1),

    /**
     * Туземцы: трое пахарей, которые достаются хозяину колонии вместе с планетой.
     * <p>
     * Вместимости требуют вчетверо больше прочих находок: в оригинале планеты с
     * туземцами не мельче средних — иначе раса с обычными пределами не смогла бы
     * поселиться там вовсе, отдав все места туземцам.
     */
    NATIVES(3, 4, Map.of(BuildingEffectType.FOOD_FLAT, 6, BuildingEffectType.MAX_POPULATION, -3), 0);

    /**
     * Доля планет галактики, на которых что-то находится, — реконструкция.
     * <p>
     * MOO II своих долей не публиковала; известно лишь, что находка — редкость, а её
     * ценность в том, что за неё стоит драться. Шесть процентов дают на средней галактике
     * (двадцать звёзд, около трёх планет у каждой) три-четыре находки на всех: меньше —
     * и половина партий пройдёт, не увидев ни одной, больше — и находка перестанет быть
     * поводом тянуться в дальний угол карты.
     */
    public static final int SHARE_PERCENT = 6;

    /**
     * С какой вероятностью артефакты отдают ВТОРУЮ технологию — реконструкция «1 or
     * occasionally 2» первоисточника: «изредка» прочитано как каждый четвёртый раз.
     */
    public static final int SECOND_TECHNOLOGY_PERCENT = 25;

    private final int weight;
    private final int minCapacity;
    private final Map<BuildingEffectType, Integer> effects;
    private final int freeTechnologies;

    PlanetFind(int weight, int minCapacity, Map<BuildingEffectType, Integer> effects,
               int freeTechnologies) {
        this.weight = weight;
        this.minCapacity = minCapacity;
        this.effects = effects;
        this.freeTechnologies = freeTechnologies;
    }

    public Integer getWeight() {
        return weight;
    }

    public Integer getMinCapacity() {
        return minCapacity;
    }

    /** Что находка даёт колонии — пары «тип эффекта — количество», как у здания. */
    public Map<BuildingEffectType, Integer> getEffects() {
        return effects;
    }

    /** Сколько технологий достаётся тому, кто первым разведал систему; ноль — ни одной. */
    public Integer getFreeTechnologies() {
        return freeTechnologies;
    }

    /** Находка отдаёт технологии за саму разведку — это только артефакты. */
    public Boolean rewardsExplorer() {
        return freeTechnologies > 0;
    }

    /**
     * Годится ли планета такой вместимости под эту находку.
     * <p>
     * Золото на газовом гиганте — это находка, которой никто никогда не воспользуется:
     * селиться там нельзя, а находка работает только с колонией.
     */
    public Boolean fits(Integer maxPopulation) {
        return maxPopulation >= minCapacity;
    }

    /** Находки, какие бывают, — по ним и бросается жребий при заселении галактики. */
    public static List<PlanetFind> all() {
        return List.of(values());
    }
}
