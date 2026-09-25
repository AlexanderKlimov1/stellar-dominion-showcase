package com.moo3.server.service;

import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.enums.RaceEffectType;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила проекта корабля — п. 8. Числа дешевле проверить здесь, чем ловить в партии:
 * ошибка в множителе корпуса меняет весь баланс флота разом.
 */
class ShipDesignRulesTest {

    private final ShipDesignRules rules = new ShipDesignRules();

    /** Фрегат: 30 места, 16 стоимости, множитель систем 1, шанс попадания 20 %. */
    private final ShipHull frigate = new ShipHull("frigate", LocalizedText.of("Frigate"), LocalizedText.of(""), 30, 16, 20, 1, 20, 1, null, 1, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);

    /** Крейсер: 120 места, множитель систем 4 — на нём броня и щиты дороже вчетверо. */
    private final ShipHull cruiser = new ShipHull("cruiser", LocalizedText.of("Cruiser"), LocalizedText.of(""), 120, 110, 80, 3, 40, 4, null, 3, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);

    private final ShipComponent drive = component("nuclear-drive", ShipComponentSlot.ENGINE, 4, 12,
            Map.of(ShipEffectType.SPEED, 2));
    private final ShipComponent armour = component("titanium-armor", ShipComponentSlot.ARMOR, 3, 3,
            Map.of(ShipEffectType.ARMOUR, 4));
    private final ShipComponent gun = component("mass-driver", ShipComponentSlot.WEAPON, 5, 4,
            Map.of(ShipEffectType.WEAPON_DAMAGE, 6, ShipEffectType.WEAPON_SHOTS, 1));
    private final ShipComponent computer = component("electronic-computer", ShipComponentSlot.COMPUTER, 2, 6,
            Map.of(ShipEffectType.ATTACK_PERCENT, 25));
    private final ShipComponent jammer = component("ecm-jammer", ShipComponentSlot.ECM, 2, 8,
            Map.of(ShipEffectType.DEFENSE, 15));
    private final ShipComponent pods = component("battle-pods", ShipComponentSlot.SPECIAL, 2, 10,
            Map.of(ShipEffectType.SPACE_PERCENT, 50));
    private final ShipComponent heavyArmour = component("heavy-armor", ShipComponentSlot.SPECIAL, 3, 12,
            Map.of(ShipEffectType.ARMOUR_PERCENT, 100));
    private final ShipComponent reinforced = component("reinforced-hull", ShipComponentSlot.SPECIAL, 3, 8,
            Map.of(ShipEffectType.STRUCTURE_PERCENT, 50));

    /** Плазменная пушка оригинала: обволакивает цель сама собой, без модификации ENV. */
    private final ShipComponent plasma = new ShipComponent("plasma-cannon",
            LocalizedText.of("plasma-cannon"), LocalizedText.of(""), ShipComponentSlot.WEAPON,
            10, 52, null, Map.of(ShipEffectType.WEAPON_DAMAGE, 30, ShipEffectType.WEAPON_SHOTS, 1),
            null, Boolean.TRUE, null, 1, Boolean.FALSE);

    @Test
    @DisplayName("Стартовый фрегат: место и цена складываются из корпуса и компонентов")
    void frigateStats() {
        ShipStats stats = rules.stats(frigate,
                List.of(item(drive, 1), item(armour, 1), item(gun, 4)), RaceEffects.NONE);

        // 4 (двигатель) + 3 (броня) + 4 * 5 (пушки) = 27 из 30
        assertEquals(30, stats.space());
        assertEquals(27, stats.spaceUsed());
        assertEquals(3, stats.spaceLeft());
        assertTrue(stats.fits());

        // 16 (корпус) + 12 + 3 + 4 * 4 = 47
        assertEquals(47, stats.cost());
        assertEquals(24, stats.attack());
        assertEquals(4, stats.armour());
        assertEquals(20, stats.structure());
        assertEquals(2, stats.speed());
        assertEquals(1, stats.command());
    }

    @Test
    @DisplayName("Оружие с корпусом не растёт, а всё остальное растёт")
    void weaponsDoNotScaleWithHull() {
        ShipStats stats = rules.stats(cruiser,
                List.of(item(drive, 1), item(armour, 1), item(gun, 4)), RaceEffects.NONE);

        /*
          Множитель крейсера — четыре, и на него умножается всё, что обслуживает корабль
          целиком: двигатель 4 * 4, броня 3 * 4. Пушка своего места не меняет — четыре
          гнезда по пять, как на фрегате, — поэтому крупный корпус несёт пропорционально
          больше огня (п. 8, правило MOO II).
        */
        assertEquals(4 * 4 + 3 * 4 + 4 * 5, stats.spaceUsed());
        assertEquals(48, stats.spaceUsed());
        // 110 (корпус) + 12 * 4 (двигатель) + 3 * 4 (броня) + 4 * 4 (пушки без множителя)
        assertEquals(186, stats.cost());
        assertEquals(16, stats.armour(), "броня растёт вместе с корпусом");
        assertEquals(24, stats.attack(), "а залп четырёх стволов от корпуса не зависит");
    }

    @Test
    @DisplayName("Боевые отсеки дают половину места корпуса сверху")
    void battlePodsAddSpace() {
        ShipStats stats = rules.stats(frigate,
                List.of(item(drive, 1), item(pods, 1)), RaceEffects.NONE);

        assertEquals(45, stats.space());
        assertEquals(6, stats.spaceUsed());
    }

    @Test
    @DisplayName("Тяжёлая броня удваивает броню, усиленный корпус поднимает прочность")
    void specialsChangeEndurance() {
        ShipStats stats = rules.stats(cruiser,
                List.of(item(drive, 1), item(armour, 1), item(heavyArmour, 1), item(reinforced, 1)),
                RaceEffects.NONE);

        // Броня: 4 * 4 множителя корпуса = 16, тяжёлая броня удваивает
        assertEquals(32, stats.armour());
        // Прочность: 80 корпуса плюс половина
        assertEquals(120, stats.structure());
    }

    @Test
    @DisplayName("Компьютер и раса поднимают залп, помехи и раса — защиту")
    void computerAndRace() {
        RaceEffects race = race(50, 20);
        ShipStats stats = rules.stats(frigate,
                List.of(item(drive, 1), item(gun, 2), item(computer, 1), item(jammer, 1)), race);

        // Залп 12, компьютер +25 %, раса +50 % — проценты складываются: 12 * 175 / 100
        assertEquals(21, stats.attack());
        // Уклонение фрегата 80 (попадают в 20 % случаев), помехи +15, раса +20 %
        assertEquals(114, stats.defense());
    }

    @Test
    @DisplayName("Leviathan уклонения не имеет: по нему попадают всегда")
    void doomStarHasNoEvasion() {
        ShipHull doomStar = new ShipHull("doom-star", LocalizedText.of("Leviathan"), LocalizedText.of(""), 1200, 1800, 640, 6, 100, 40, null, 6, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
        assertEquals(0, doomStar.evasion());

        ShipStats stats = rules.stats(doomStar, List.of(item(drive, 1), item(jammer, 1)), RaceEffects.NONE);
        // Вся защита такого корабля — приборы: 15 помех, умноженные на множитель корпуса
        // в цене и месте, но не в самом эффекте.
        assertEquals(15, stats.defense());
    }

    @Test
    @DisplayName("Проект, не влезающий в корпус, виден по занятому месту")
    void oversizedDesign() {
        ShipStats stats = rules.stats(frigate,
                List.of(item(drive, 1), item(gun, 8)), RaceEffects.NONE);

        assertEquals(44, stats.spaceUsed());
        assertFalse(stats.fits());
        assertEquals(-14, stats.spaceLeft());
    }

    @Test
    @DisplayName("Боевая сила растёт от залпа, живучести и уклонения, но не падает до нуля")
    void power() {
        ShipStats weak = rules.stats(frigate, List.of(item(drive, 1)), RaceEffects.NONE);
        ShipStats armed = rules.stats(frigate,
                List.of(item(drive, 1), item(armour, 1), item(gun, 4)), RaceEffects.NONE);

        // Безоружный корабль всё равно весит единицу: он занимает место в строю.
        assertEquals(1, rules.power(weak));
        // 24 залпа на живучесть 24 (20 прочности и 4 брони) и на уклонение фрегата:
        // 100 + 80 = 180 сотых, то есть в 1,8 раза больше, чем у неповоротливого корпуса.
        assertEquals(24 * 24 * 180 / 10_000, rules.power(armed));
        assertTrue(rules.power(armed) > rules.power(weak));
    }

    @Test
    @DisplayName("Защита входит в силу тем же множителем, что и атака")
    void defenceCountsLikeAttack() {
        // Сторона расы «пилоты» до этой правки не меняла в силе НИЧЕГО: защиты в формуле
        // не было вовсе, а партии ИИ решаются быстрым боем, который сравнивает ровно её.
        List<ShipDesignRules.Item> items = List.of(item(drive, 1), item(armour, 1), item(gun, 4));
        ShipStats plain = rules.stats(frigate, items, RaceEffects.NONE);
        ShipStats pilots = rules.stats(frigate, items,
                new RaceEffects(com.moo3.server.domain.BuildingEffects.NONE,
                        java.util.Map.of(RaceEffectType.SHIP_DEFENSE_PERCENT, 50)));
        assertTrue(rules.power(pilots) > rules.power(plain),
                "сила " + rules.power(plain) + " -> " + rules.power(pilots));

        // А у корпуса, по которому попадают всегда (уклонение ноль), защита силы не
        // прибавляет — его защита это броня и щиты. Обнулять его при этом нельзя.
        ShipStats doom = rules.stats(doomStar(), items, RaceEffects.NONE);
        assertTrue(rules.power(doom) >= 1);
    }

    private ShipHull doomStar() {
        return new ShipHull("doom-star", LocalizedText.of("Leviathan"), LocalizedText.of(""), 1200, 1800, 640, 6, 100, 40, null, 6,
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    }

    @Test
    @DisplayName("Кораблестроение открывают базовые уровни Power и Chemistry")
    void shipbuildingNeedsBothTechnologies() {
        assertFalse(rules.shipbuildingAvailable(Set.of()));
        assertFalse(rules.shipbuildingAvailable(Set.of(ShipDesignRules.DRIVE_TECH)),
                "без топлива корабль не отойдёт от родной звезды");
        assertFalse(rules.shipbuildingAvailable(Set.of(ShipDesignRules.FUEL_TECH)),
                "без двигателя кораблю нечем лететь");
        assertTrue(rules.shipbuildingAvailable(
                Set.of(ShipDesignRules.DRIVE_TECH, ShipDesignRules.FUEL_TECH)));
    }

    @Test
    @DisplayName("Игроку названо, чего именно не хватает")
    void requirementNamesMissingLevels() {
        assertTrue(rules.missingShipbuildingTechnologies(Set.of()).contains("Nuclear Fission (Power)"));
        assertTrue(rules.missingShipbuildingTechnologies(Set.of()).contains("Chemistry (Chemistry)"));
        assertFalse(rules.missingShipbuildingTechnologies(Set.of(ShipDesignRules.DRIVE_TECH))
                .contains("Nuclear Fission (Power)"), "изученное в требованиях не повторяется");
        assertTrue(rules.missingShipbuildingTechnologies(
                Set.of(ShipDesignRules.DRIVE_TECH, ShipDesignRules.FUEL_TECH)).isEmpty());
    }

    @Test
    @DisplayName("Обволакивающий удар входит в залп вчетверо — п. 8")
    void envelopingCountsFourfold() {
        ShipStats plain = rules.stats(frigate,
                List.of(item(drive, 1), item(gun, 1)), RaceEffects.NONE);
        ShipStats enveloping = rules.stats(frigate,
                List.of(item(drive, 1), item(plasma, 1)), RaceEffects.NONE);

        /*
          Правило оригинала: плазменная пушка и Копьё новы обволакивают цель сами собой, и
          удар приходится на все четыре стороны разом. Залп поэтому считается вчетверо —
          тем же множителем, каким его считает бой (ShipDesignRules.ENVELOPING_SIDES),
          иначе сила проекта расходилась бы с тем, что происходит на поле.
        */
        assertEquals(6, plain.attack(), "обычный ствол бьёт на свой урон");
        assertEquals(30 * ShipDesignRules.ENVELOPING_SIDES, enveloping.attack(),
                "обволакивающий — вчетверо");
    }

    @Test
    @DisplayName("Без звёздной базы колония поднимает только два наименьших корпуса")
    void starBaseOpensBigHulls() {
        assertEquals(ShipDesignRules.HULL_SIZE_WITHOUT_STAR_BASE, rules.maxHullSize(Boolean.FALSE));
        assertEquals(2, rules.maxHullSize(null), "нет базы — нет и верфи");
        assertTrue(rules.maxHullSize(Boolean.TRUE) >= 6, "с базой строится любой корпус");
        assertTrue(frigate.sortOrder() <= rules.maxHullSize(Boolean.FALSE));
        assertTrue(cruiser.sortOrder() > rules.maxHullSize(Boolean.FALSE));
    }

    private ShipDesignRules.Item item(ShipComponent component, Integer count) {
        return new ShipDesignRules.Item(component, count);
    }

    private ShipComponent component(String code, ShipComponentSlot slot, Integer space, Integer cost,
                                    Map<ShipEffectType, Integer> effects) {
        return new ShipComponent(code, LocalizedText.of(code), LocalizedText.of(""), slot, space, cost, null, effects, null, Boolean.FALSE, null, 1, Boolean.FALSE);
    }

    private RaceEffects race(Integer attackPercent, Integer defensePercent) {
        return new RaceEffects(BuildingEffects.NONE, Map.of(
                RaceEffectType.SHIP_ATTACK_PERCENT, attackPercent,
                RaceEffectType.SHIP_DEFENSE_PERCENT, defensePercent));
    }
}
