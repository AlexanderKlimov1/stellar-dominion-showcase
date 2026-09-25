package com.moo3.server.service;

import com.moo3.server.domain.Leader;
import org.springframework.stereotype.Service;

/**
 * Числовые правила лидеров — п. 6. Правятся только здесь.
 * <p>
 * <b>Что взято у оригинала дословно</b> (StrategyWiki, Hiring Leaders): по четыре места
 * на каждый род лидеров; предложение ждёт решения тридцать ходов; нанятый лидер получает
 * очко опыта за ход, даже сидя без назначения; отказанный уходит к другим, поднявшись на
 * звание; лидеры появляются примерно в порядке званий — магистратов раньше сотого хода не
 * ждут; способность «Знаменитость» удешевляет наём и жалованье остальных, и из нескольких
 * знаменитостей считается только сильнейшая; «Богач» жалованья не берёт, а приносит
 * десять кредитов за ход.
 * <p>
 * <b>Что реконструировано:</b> сама цена найма и жалованье. Оригинал их не публикует —
 * известно только, что наём стоит «обычно немало», а жалованье идёт за ход. Опора для
 * реконструкции есть в самом справочнике: скидка «Знаменитости» задана в кредитах
 * (−60, −90, −120, −180, −270), и цена обязана быть такой, чтобы эта скидка была заметной,
 * но не решающей. Отсюда цена в сотнях кредитов, растущая со званием и числом
 * способностей, и жалованье в двадцатую её часть.
 * <p>
 * <b>Раса решает, кто и как часто приходит</b> (п. 7). В MOO II это две зеркальные
 * особенности: у отталкивающей расы выбор лидеров беднее, дороже и реже, у обаятельной —
 * наоборот. Здесь это ровно то же: вероятность появления умножается, цена делится, а у
 * отталкивающей расы вдобавок не появляются лидеры с дипломатическими способностями —
 * им у неё нечего делать.
 */
@Service
public class LeaderRules {

    /** Мест под лидеров каждого рода — как в оригинале, по четыре. */
    public static final int SLOTS_PER_KIND = 4;

    /** Сколько ходов предложение ждёт решения игрока. */
    public static final int OFFER_LIFETIME_TURNS = 30;

    /** Очков опыта за ход службы: нанятый растёт, даже сидя в резерве. */
    public static final int EXPERIENCE_PER_TURN = 1;

    /**
     * Сколько ходов лидер добирается до назначения. В оригинале пять, а мгновенно — только
     * туда, где стоит офицерский резерв (родная система).
     */
    public static final int TRAVEL_TURNS = 5;

    /** Вероятность появления нового лидера в ход, процентов. */
    private static final int OFFER_CHANCE_PERCENT = 6;

    /** Во сколько раз реже приходят лидеры к отталкивающей расе и чаще — к обаятельной. */
    private static final double REPULSIVE_OFFER = 0.5;
    private static final double CHARISMATIC_OFFER = 1.5;

    /** Во столько же раз дороже и дешевле обходится наём. */
    private static final double REPULSIVE_COST = 1.5;
    private static final double CHARISMATIC_COST = 0.5;

    /** Основа цены найма: за каждое звание лидера. */
    private static final int COST_PER_RANK = 150;

    /** Прибавка к цене за каждую способность и за каждую принесённую технологию. */
    private static final int COST_PER_SKILL = 50;
    private static final int COST_PER_TECH = 150;

    /** Жалованье — двадцатая часть цены найма за ход. */
    private static final int SALARY_DIVISOR = 20;

    /** Сколько кредитов за ход приносит «Богач» — число оригинала. */
    public static final int MEGAWEALTH_CREDITS = 10;

    /**
     * Вероятность, что в этот ход появится новый лидер, — в процентах.
     * <p>
     * Раса меняет частоту, как в оригинале: отталкивающей реже, обаятельной чаще.
     */
    public Integer offerChancePercent(Boolean repulsive, Boolean charismatic) {
        double chance = OFFER_CHANCE_PERCENT;
        if (Boolean.TRUE.equals(repulsive)) {
            chance *= REPULSIVE_OFFER;
        }
        if (Boolean.TRUE.equals(charismatic)) {
            chance *= CHARISMATIC_OFFER;
        }
        return (int) Math.round(chance);
    }

    /**
     * Цена найма — реконструкция, см. описание класса.
     *
     * @param rankIndex номер звания лидера с нуля
     * @param famousDiscount скидка сильнейшей «Знаменитости» на службе, кредитов
     */
    public Integer hireCost(Leader leader, Integer rankIndex, Integer famousDiscount,
                            Boolean repulsive, Boolean charismatic) {
        int skills = leader.skills().isEmpty() ? leader.randomSkills() : leader.skills().size();
        double cost = (double) COST_PER_RANK * (rankIndex + 1)
                + (double) COST_PER_SKILL * skills
                + (double) COST_PER_TECH * leader.techs().size();
        if (Boolean.TRUE.equals(repulsive)) {
            cost *= REPULSIVE_COST;
        }
        if (Boolean.TRUE.equals(charismatic)) {
            cost *= CHARISMATIC_COST;
        }
        // Скидка знаменитости вычитается последней и не уводит цену ниже нуля: даром
        // лидеры не служат даже у самой прославленной империи.
        return Math.max(0, (int) Math.round(cost) - famousDiscount);
    }

    /**
     * Жалованье за ход. «Богач» его не берёт вовсе — вместо этого приносит казне
     * {@link #MEGAWEALTH_CREDITS}, и это считается отдельно, при подсчёте дохода.
     */
    public Integer salary(Leader leader, Integer hireCost) {
        if (Boolean.TRUE.equals(leader.has("MEGAWEALTH"))) {
            return 0;
        }
        return Math.max(1, hireCost / SALARY_DIVISOR);
    }

    /**
     * Сила способности при этом звании: базовое значение плюс прирост за каждое звание
     * сверх стартового. Дробный прирост оригинала («+7 или 8 за уровень») округляется
     * к ближайшему целому — половинки в описании и означают чередование.
     */
    public Integer skillValue(Leader.LeaderSkill skill, Integer rankIndex, Integer startRankIndex) {
        int levels = Math.max(0, rankIndex - startRankIndex);
        return (int) Math.round(skill.value() + skill.perLevel() * levels);
    }

    /**
     * Годится ли лидер этой империи. Отталкивающей расе не предлагают дипломатов и
     * торговцев: договоров она не ведёт, и такой лидер был бы обманом — деньги за
     * способность, которая ничего не делает.
     */
    public Boolean suitable(Leader leader, Boolean repulsive) {
        if (!Boolean.TRUE.equals(repulsive)) {
            return Boolean.TRUE;
        }
        return !Boolean.TRUE.equals(leader.has("DIPLOMAT"))
                && !Boolean.TRUE.equals(leader.has("TRADER"));
    }

    /**
     * Не рано ли этому лидеру появляться — п. 6.
     * <p>
     * В оригинале лидеры приходят примерно в порядке званий: администраторы сразу,
     * магистратов раньше сотого хода не ждут, комиссаров — раньше сто пятидесятого.
     * Здесь это тот же порядок: ход должен быть не меньше стартового опыта, делённого на
     * два, — так пороги и складываются в 0, 30, 75, 150 и 250 ходов.
     */
    public Boolean dueByTurn(Leader leader, Integer turn) {
        return turn >= leader.startExperience() / 2;
    }
}
