package com.moo3.server.service;

import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.enums.SpaceMonster;
import com.moo3.server.domain.RaceEffects;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Из чего чудище собрано для тактического боя — п. 8, п. 11.1.
 * <p>
 * У чудища в игре есть ровно одно число — <b>сила</b> ({@link SpaceMonster#getStrength()},
 * та же мера, что у флота), и быстрый бой на подлёте сравнивает именно её. Тактическое же
 * поле умеет считать только корабль: корпус, броню и стволы. Поэтому чудище собирается
 * как корабль — телом, шкурой и когтями, — а число когтей подбирается так, чтобы
 * {@code ShipDesignRules.power} собранного совпал с его силой. Иначе игра отвечала бы на
 * один и тот же вопрос двумя разными числами: «дракон силой 800» на подлёте и «дракон
 * непонятно какой» на поле.
 * <p>
 * <b>Это реконструкция.</b> MOO II состава чудищ не публиковала — известно лишь, что
 * чудище бьёт больно, живёт долго и технологиями не улучшается. Тело, шкура и когти лежат
 * данными в {@code ship-components.json} (признак {@code monster}), а правило сборки —
 * здесь, и правится только здесь.
 * <p>
 * <b>Рана чудища видна в ударе, а не в шкуре.</b> Ослабевшее чудище
 * ({@code star_system.monster_strength} ниже начальной силы) выходит на поле тем же телом,
 * но с меньшим числом когтей: тело у существа одно, а бить оно после боя может слабее.
 * Обратный перевод — после боя силой становится доля уцелевшего тела ({@link #leftAfter}).
 */
@Component
public class MonsterBattleRules {

    /** Тело чудища: когти. */
    public static final String CLAWS = "monster-claws";

    /** Тело чудища: шкура вместо брони. */
    public static final String HIDE = "monster-hide";

    /** Тело чудища: плавники вместо двигателя — они двигают его по полю боя. */
    public static final String FINS = "monster-fins";

    /**
     * Ячейка проекта чудища.
     * <p>
     * Шесть ячеек (1…6) заняты игроком, нулевая — гражданскими кораблями, −1…−4 —
     * платформами обороны. Чудищу отведена своя, чтобы его проект не мог попасть ни в
     * окно дизайна, ни в список стройки: они смотрят на ячейки с первой.
     */
    public static final int DESIGN_SLOT = -20;

    private final ShipCatalog shipCatalog;
    private final ShipDesignRules shipDesignRules;

    public MonsterBattleRules(ShipCatalog shipCatalog, ShipDesignRules shipDesignRules) {
        this.shipCatalog = shipCatalog;
        this.shipDesignRules = shipDesignRules;
    }

    /** Тело этого чудища из справочника кораблей. */
    public ShipHull hull(SpaceMonster kind) {
        return shipCatalog.monsterHull(kind.getHullCode());
    }

    /**
     * Состав чудища нынешней силы: плавники, шкура и столько когтей, чтобы сила
     * собранного совпала с {@code strength}.
     * <p>
     * Когтей всегда хотя бы один: чудище без когтей не чудище, а мишень, — и бой с ним
     * не кончился бы никогда.
     */
    public List<ShipDesignRules.Item> items(SpaceMonster kind, Integer strength) {
        return List.of(
                new ShipDesignRules.Item(shipCatalog.component(FINS), 1),
                new ShipDesignRules.Item(shipCatalog.component(HIDE), 1),
                new ShipDesignRules.Item(shipCatalog.component(CLAWS), claws(kind, strength)));
    }

    /**
     * Сколько когтей нужно, чтобы чудище весило ровно свою силу.
     * <p>
     * Сила корабля растёт с числом стволов ровно пропорционально (залп входит в неё
     * множителем), поэтому счёт прямой: сила одного когтя на этом теле — и сколько их
     * нужно до заданной. Место на теле при этом не проверяется: у тел его заведомо
     * хватает, а чудище, которому когти «не влезли», было бы слабее собственной силы.
     */
    public Integer claws(SpaceMonster kind, Integer strength) {
        ShipHull hull = hull(kind);
        List<ShipDesignRules.Item> single = List.of(
                new ShipDesignRules.Item(shipCatalog.component(FINS), 1),
                new ShipDesignRules.Item(shipCatalog.component(HIDE), 1),
                new ShipDesignRules.Item(shipCatalog.component(CLAWS), 1));
        Integer perClaw = shipDesignRules.power(
                shipDesignRules.stats(hull, single, RaceEffects.NONE));
        if (perClaw <= 0) {
            return 1;
        }
        return Math.max(1, Math.round((float) Math.max(1, strength) / perClaw));
    }

    /**
     * Сила, с которой чудище остаётся после боя: доля уцелевшего тела от целого.
     * <p>
     * Это обратный перевод того же правила: на поле рана считается по корпусу и шкуре, а
     * в игре чудище живёт одним числом силы. Разбитое наголову отдаёт ноль — тогда его
     * в системе больше нет.
     */
    public Integer leftAfter(Integer strength, ShipStats full,
                             Integer structureLeft, Integer armourLeft) {
        int whole = Math.max(1, full.structure() + full.armour());
        int left = Math.max(0, structureLeft) + Math.max(0, armourLeft);
        if (left <= 0) {
            return 0;
        }
        return Math.max(1, strength * left / whole);
    }
}
