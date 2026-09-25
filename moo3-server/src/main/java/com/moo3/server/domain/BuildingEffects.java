package com.moo3.server.domain;

import com.moo3.server.domain.enums.BuildingEffectType;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Суммарное действие зданий, построенных на планете — п. 10.
 * <p>
 * Здания складываются: два здания, каждое из которых прибавляет фермеру по единице еды,
 * дают фермеру две. Поэтому колонии достаточно одного такого набора вместо списка зданий.
 *
 * @param upkeep содержание всех зданий колонии в кредитах за ход
 */
public record BuildingEffects(
        Map<BuildingEffectType, Integer> amounts,
        Integer upkeep
) {

    /** Колония без единого здания. */
    public static final BuildingEffects NONE = new BuildingEffects(Map.of(), 0);

    /**
     * Складывает два набора действий — колония не различает, откуда взялась прибавка:
     * от построек или от особенностей расы (п. 7).
     */
    public BuildingEffects plus(BuildingEffects other) {
        Map<BuildingEffectType, Integer> total = new EnumMap<>(BuildingEffectType.class);
        total.putAll(amounts);
        other.amounts().forEach((type, amount) -> total.merge(type, amount, Integer::sum));
        return new BuildingEffects(Map.copyOf(total), upkeep + other.upkeep());
    }

    /**
     * Смешивает два набора по долям населения — п. 7, п. 12: подданные захваченной
     * колонии работают по правилам своей прежней расы, а свои жители — по правилам
     * хозяина.
     * <p>
     * Смешиваются готовые прибавки, а не сами расы: колония считает выработку одним
     * набором действий, и разделить её по головам иначе нельзя. Доли берутся по жителям,
     * содержание — от хозяина: платит за здания он.
     */
    public BuildingEffects blend(BuildingEffects other, Integer share, Integer otherShare) {
        int total = Math.max(1, share + otherShare);
        Map<BuildingEffectType, Integer> mixed = new EnumMap<>(BuildingEffectType.class);
        amounts.forEach((type, amount) -> mixed.merge(type, amount * share / total, Integer::sum));
        other.amounts().forEach((type, amount) ->
                mixed.merge(type, amount * otherShare / total, Integer::sum));
        return new BuildingEffects(Map.copyOf(mixed), upkeep);
    }

    public static BuildingEffects sum(Collection<Building> buildings) {
        Map<BuildingEffectType, Integer> amounts = new EnumMap<>(BuildingEffectType.class);
        int upkeep = 0;
        for (Building building : buildings) {
            building.effects().forEach((type, amount) -> amounts.merge(type, amount, Integer::sum));
            upkeep += building.upkeep();
        }
        return new BuildingEffects(Map.copyOf(amounts), upkeep);
    }

    /** Сколько зданий колонии дают этого эффекта в сумме; ноль — ни одно не даёт. */
    public Integer amount(BuildingEffectType type) {
        return amounts.getOrDefault(type, 0);
    }

    public Integer foodFlat() {
        return amount(BuildingEffectType.FOOD_FLAT);
    }

    public Integer foodPerFarmer() {
        return amount(BuildingEffectType.FOOD_PER_FARMER);
    }

    public Integer productionFlat() {
        return amount(BuildingEffectType.PRODUCTION_FLAT);
    }

    public Integer productionPerWorker() {
        return amount(BuildingEffectType.PRODUCTION_PER_WORKER);
    }

    /** Производство с каждого жителя — п. 10: рециклотрон, и оно не пачкает планету. */
    public Integer productionPerColonist() {
        return amount(BuildingEffectType.PRODUCTION_PER_COLONIST);
    }

    public Integer researchFlat() {
        return amount(BuildingEffectType.RESEARCH_FLAT);
    }

    public Integer growthPercent() {
        return amount(BuildingEffectType.GROWTH_PERCENT);
    }

    public Integer researchPerScientist() {
        return amount(BuildingEffectType.RESEARCH_PER_SCIENTIST);
    }

    public Integer maxPopulationBonus() {
        return amount(BuildingEffectType.MAX_POPULATION);
    }

    public Integer growthKBonus() {
        return amount(BuildingEffectType.POPULATION_GROWTH_K);
    }

    public Integer incomePercent() {
        return amount(BuildingEffectType.INCOME_PERCENT);
    }

    /** Кредиты за ход сверх налога — п. 4.1: золотые жилы и самоцветы. */
    public Integer incomeFlat() {
        return amount(BuildingEffectType.INCOME_FLAT);
    }

    /** Процент к выработке фермера — п. 7: объединение и плохие фермеры. */
    public Integer foodPercent() {
        return amount(BuildingEffectType.FOOD_PERCENT);
    }

    /** Процент к выработке рабочего — п. 7: объединение. */
    public Integer productionPercent() {
        return amount(BuildingEffectType.PRODUCTION_PERCENT);
    }

    /** Процент к выработке учёного — п. 7: демократия и феодализм. */
    public Integer researchPercent() {
        return amount(BuildingEffectType.RESEARCH_PERCENT);
    }

    /**
     * Во сколько раз чище считается производство колонии — п. 10: 1 без очистных
     * сооружений, 2 с переработчиком отходов, 4 с атмосферным преобразователем, 8 с
     * обоими. Деления зданий складываются, поэтому делитель — двойка в их степени.
     */
    public Integer pollutionDivisor() {
        return 1 << Math.min(16, Math.max(0, amount(BuildingEffectType.POLLUTION_HALVINGS)));
    }

    /** Процент к терпимости планеты к грязи — п. 10: наноразборщики удваивают её. */
    public Integer pollutionTolerancePercent() {
        return amount(BuildingEffectType.POLLUTION_TOLERANCE_PERCENT);
    }

    /** Грязи у колонии нет вовсе — п. 10: свалка в ядре или неприхотливая раса (п. 7). */
    public Boolean pollutionFree() {
        return amount(BuildingEffectType.POLLUTION_NONE) > 0;
    }
}
