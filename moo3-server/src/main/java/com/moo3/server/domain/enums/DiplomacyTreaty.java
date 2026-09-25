package com.moo3.server.domain.enums;

import com.moo3.server.domain.enums.RaceEffectType;

/**
 * Договор между империями — п. 15.
 * <p>
 * Договоры MOO II, которые эта игра умеет считать: торговый поднимает доход обеих
 * сторон, исследовательский — их науку, пакт о ненападении запрещает войну, союз — всё
 * это вместе плюс общая разведка.
 *
 * @see com.moo3.server.service.DiplomacyService
 */
public enum DiplomacyTreaty {

    /**
     * Торговый договор: обе империи торгуют и богатеют.
     * <p>
     * Реконструкция: в MOO II прибавка зависит от объёма торговли сторон, которого здесь
     * нет, поэтому взят ровный процент к налоговому доходу колоний.
     */
    TRADE("Торговый договор", RaceEffectType.INCOME_PERCENT, 25, 40),

    /**
     * Исследовательский договор: учёные сторон обмениваются наработками.
     * <p>
     * Реконструкция: MOO II прибавляет процент к исследованиям; здесь это очко с учёного
     * — та же величина в модели, где наука считается с человека.
     */
    RESEARCH("Исследовательский договор", RaceEffectType.RESEARCH_PER_SCIENTIST, 1, 50),

    /** Пакт о ненападении: пока он в силе, война невозможна. */
    NON_AGGRESSION("Пакт о ненападении", null, 0, 55),

    /** Союз: ненападение, общая разведка систем и доверие союзника. */
    ALLIANCE("Союз", null, 0, 75);

    private final String label;
    /** Что договор даёт колониям; {@code null} — договор действует не на колонии. */
    private final RaceEffectType effect;
    private final Integer amount;
    /** С какого доверия другая сторона соглашается на договор. */
    private final Integer requiredTrust;

    DiplomacyTreaty(String label, RaceEffectType effect, Integer amount, Integer requiredTrust) {
        this.label = label;
        this.effect = effect;
        this.amount = amount;
        this.requiredTrust = requiredTrust;
    }

    public String getLabel() {
        return label;
    }

    public RaceEffectType getEffect() {
        return effect;
    }

    public Integer getAmount() {
        return amount;
    }

    /**
     * Запрещает ли этот договор войну между сторонами — п. 15.
     * <p>
     * Различие не косметическое, а решающее для ИИ. Подписанный договор ИИ не рвёт
     * никогда, поэтому пакт о ненападении и союз запирают вечный мир — и когда-то из-за
     * этого галактика не воевала вовсе. Торговый и исследовательский войну не запрещают:
     * это чистая экономика, и подписывать их можно с кем угодно, с кем есть хоть
     * какое-то доверие.
     */
    public Boolean forbidsWar() {
        return this == NON_AGGRESSION || this == ALLIANCE;
    }

    public Integer getRequiredTrust() {
        return requiredTrust;
    }
}
