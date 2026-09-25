package com.moo3.server.domain;

import com.moo3.server.domain.enums.RaceEffectType;

import java.util.Map;

/**
 * Что раса игрока даёт ему в игре — п. 7: сумма всех её особенностей.
 * <p>
 * Колониальная часть отдана {@link BuildingEffects}: раса работает на колонии как вечная
 * постройка, и расчёты колоний не различают, откуда пришла прибавка. Остальное —
 * подсистемам: наземному бою (п. 12), шпионажу (п. 13) и кораблям (п. 8).
 *
 * @param colony  прибавки к выработке колоний
 * @param amounts все эффекты расы, включая колониальные: по ним удобно показывать расу
 */
public record RaceEffects(
        BuildingEffects colony,
        Map<RaceEffectType, Integer> amounts
) {

    /** Раса без единой особенности — справочная раса. */
    public static final RaceEffects NONE = new RaceEffects(BuildingEffects.NONE, Map.of());

    public Integer amount(RaceEffectType type) {
        return amounts.getOrDefault(type, 0);
    }

    /** Процент к силе наземного боя — п. 12. */
    public Integer groundCombatPercent() {
        return amount(RaceEffectType.GROUND_COMBAT_PERCENT);
    }

    /** Очки шпионажа за ход — п. 13. */
    public Integer espionagePoints() {
        return amount(RaceEffectType.ESPIONAGE_POINTS);
    }

    /** Процент к атаке кораблей — п. 8. */
    public Integer shipAttackPercent() {
        return amount(RaceEffectType.SHIP_ATTACK_PERCENT);
    }

    /** Процент к защите кораблей — п. 8. */
    public Integer shipDefensePercent() {
        return amount(RaceEffectType.SHIP_DEFENSE_PERCENT);
    }

    /** Очки контрразведки за ход сверх обычных — п. 13: правительства. */
    public Integer espionageDefensePoints() {
        return amount(RaceEffectType.ESPIONAGE_DEFENSE_POINTS);
    }

    /** Процент к съедаемой жителем еде — п. 4.1.1: киборги и литоворы. */
    public Integer foodConsumptionPercent() {
        return amount(RaceEffectType.FOOD_CONSUMPTION_PERCENT);
    }

    /** Процент производства, проедаемый жителем — п. 7: киборги. */
    public Integer productionConsumptionPercent() {
        return amount(RaceEffectType.PRODUCTION_CONSUMPTION_PERCENT);
    }

    /** Прибавка к пригодной доле планеты в процентных пунктах — п. 4.1.2: неприхотливые. */
    public Integer habitabilityPercent() {
        return amount(RaceEffectType.HABITABILITY_PERCENT);
    }

    /** Прибавка к вместимости за ступень размера планеты — п. 4.1.1: подземные. */
    public Integer maxPopulationPerSize() {
        return amount(RaceEffectType.MAX_POPULATION_PER_SIZE);
    }

    /** Водная раса — п. 4.1.2: мокрые климаты считаются лучше своего. */
    public Boolean aquatic() {
        return has(RaceEffectType.AQUATIC);
    }

    /** Процент к стоимости постройки кораблей — п. 8: феодализм. */
    public Integer shipCostPercent() {
        return amount(RaceEffectType.SHIP_COST_PERCENT);
    }

    /** Прибавка к дальности хода флота в парсеках — п. 8: надпространственные. */
    public Integer shipSpeedParsecs() {
        return amount(RaceEffectType.SHIP_SPEED_PARSECS);
    }

    /** Прибавка к скорости корабля на поле боя — п. 8: надпространственные. */
    public Integer shipCombatSpeed() {
        return amount(RaceEffectType.SHIP_COMBAT_SPEED);
    }

    /** Прибавка к очкам учёного на родном мире — п. 7: мир артефактов. */
    public Integer homeResearchPerScientist() {
        return amount(RaceEffectType.HOME_RESEARCH_PER_SCIENTIST);
    }

    /** Процент к доходу с товаров и лишней еды — п. 10: прирождённые торговцы. */
    public Integer tradeIncomePercent() {
        return amount(RaceEffectType.TRADE_INCOME_PERCENT);
    }

    /** Процент к доходу с торговых договоров — п. 15: прирождённые торговцы. */
    public Integer tradeTreatyPercent() {
        return amount(RaceEffectType.TRADE_TREATY_PERCENT);
    }

    /** Процент к силе десанта в обороне сверх обычного — п. 12: подземные. */
    public Integer groundDefencePercent() {
        return amount(RaceEffectType.GROUND_DEFENCE_PERCENT);
    }

    /** Процент к расположению чужих империй — п. 14: обаятельные и телепаты. */
    public Integer diplomacyPercent() {
        return amount(RaceEffectType.DIPLOMACY_PERCENT);
    }

    /** Неприхотливая раса — п. 4.1.2: вместимость мира считается как на земном. */
    public Boolean tolerant() {
        return has(RaceEffectType.TOLERANT);
    }

    /** Кибернетическая раса — п. 8: корабли чинятся прямо в бою. */
    public Boolean cybernetic() {
        return has(RaceEffectType.CYBERNETIC);
    }

    /** Процент к запасу командных очков империи — п. 14: Империум. */
    public Integer commandPercent() {
        return amount(RaceEffectType.COMMAND_PERCENT);
    }

    /** Насколько быстрее империя перевоспитывает подданных, в процентах — п. 7. */
    public Integer assimilationPercent() {
        return amount(RaceEffectType.ASSIMILATION_PERCENT);
    }

    /** Потерянная колония ассимилируется противником сразу — п. 14: феодализм. */
    public Boolean instantAssimilation() {
        return has(RaceEffectType.INSTANT_ASSIMILATION);
    }

    /** Раса малой тяжести — п. 4.1, п. 7: лёгкие миры ей родные, обычные даются хуже. */
    public Boolean gravityLow() {
        return has(RaceEffectType.GRAVITY_LOW);
    }

    /** Раса большой тяжести — п. 4.1, п. 7: тяжёлые и обычные миры ей нипочём. */
    public Boolean gravityHigh() {
        return has(RaceEffectType.GRAVITY_HIGH);
    }

    /**
     * Военачальники — п. 7, п. 8: каждая колония даёт вдвое больше командных очков.
     * <p>
     * Читается в {@code CommandRules}. Обученные экипажи той же расы сидят отдельными
     * эффектами — прибавками к атаке и защите кораблей: в MOO II это «более высокий
     * уровень подготовки команд», а уровней подготовки у здешних кораблей нет.
     */
    public Boolean warlord() {
        return has(RaceEffectType.WARLORD);
    }

    /**
     * Раса телепатов — п. 7, п. 12: колонию берут под контроль вместо десанта.
     * <p>
     * Читается в {@code ExpeditionService.mindControl}: крупный корабль на орбите — и
     * колония меняет хозяина вместе с жителями, без боя и потерь.
     */
    public Boolean telepathic() {
        return has(RaceEffectType.TELEPATHIC);
    }

    /** Скрытные корабли — п. 8: чужие сканеры их не замечают. */
    public Boolean stealthyShips() {
        return has(RaceEffectType.STEALTHY_SHIPS);
    }

    /** Всевидящая раса — п. 15: вся галактика открыта с первого хода. */
    public Boolean omniscient() {
        return has(RaceEffectType.OMNISCIENT);
    }

    /** Изобретательная раса — п. 9: уровень дерева даёт все технологии разом. */
    public Boolean creative() {
        return has(RaceEffectType.CREATIVE);
    }

    /** Неизобретательная раса — п. 9: технологию уровня выбирает случай. */
    public Boolean uncreative() {
        return has(RaceEffectType.UNCREATIVE);
    }

    /**
     * Везучая раса — п. 7, п. 11.1: галактические события к ней добрее.
     * <p>
     * В MOO II «удачливые получают больше добрых случайностей и куда меньше бед». Здесь
     * так же: дурные события к такой империи не приходят вовсе, а добрые приходят вдвое
     * чаще — см. {@code GalacticEventService}.
     */
    public Boolean lucky() {
        return has(RaceEffectType.LUCKY);
    }

    /**
     * Отталкивающая раса — п. 14, п. 15: договоров с ней не бывает.
     * <p>
     * В MOO II у такой империи в переговорах остаются только объявление войны, мир и
     * капитуляция — ни торгового договора, ни союза, ни дани. За это раса и возвращает
     * шесть очков в конструкторе.
     */
    public Boolean repulsive() {
        return has(RaceEffectType.REPULSIVE);
    }

    /**
     * Обаятельная раса — п. 6: лидеры приходят чаще и нанимаются дешевле.
     * <p>
     * Зеркало отталкивающей, и обе стороны одинаково важны там, где считаются лидеры
     * ({@code LeaderRules}). Прибавку к переговорам та же особенность даёт отдельным
     * эффектом {@code DIPLOMACY_PERCENT}: в оригинале она делает и то и другое.
     */
    public Boolean charismatic() {
        return has(RaceEffectType.CHARISMATIC);
    }

    /**
     * Есть ли у расы эта сторона. Признаки записаны в файле как эффект с количеством 1:
     * отдельного вида «особенность без числа» заводить не пришлось, а ноль и отсутствие
     * значат одно и то же.
     */
    private Boolean has(RaceEffectType type) {
        return amount(type) > 0;
    }
}
