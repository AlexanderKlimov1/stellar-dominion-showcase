package com.moo3.server.service;

import com.moo3.server.domain.LocalizedText;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.WeaponModification;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.enums.BattleSide;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.WeaponKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила тактического боя — п. 8. Числа боя дешевле проверить здесь, чем ловить их
 * на поле: ошибка в шансе попадания или в очереди хода видна только через десяток
 * выстрелов.
 */
class BattleRulesTest {

    private final BattleRules rules = new BattleRules();
    private final ShipDesignRules designRules = new ShipDesignRules();

    /**
     * Модификации берутся из того самого справочника, который уходит в игру: числа
     * оригинала (HV, PD, AF, ENV…) проверяются вместе с правилами, а не отдельно от них.
     */
    private final ShipCatalog catalog = new ShipCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private final ShipHull frigate = new ShipHull("frigate", LocalizedText.of("Frigate"), LocalizedText.of(""), 30, 16, 20, 1, 20, 1, null, 1, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    private final ShipHull doomStar =
            new ShipHull("doom-star", LocalizedText.of("Leviathan"), LocalizedText.of(""), 1200, 1800, 640, 6, 100, 40, null, 6, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);

    private final ShipComponent drive = component("nuclear-drive", ShipComponentSlot.ENGINE,
            Map.of(ShipEffectType.SPEED, 2));
    private final ShipComponent fastDrive = component("hyper-drive", ShipComponentSlot.ENGINE,
            Map.of(ShipEffectType.SPEED, 6));
    private final ShipComponent computer = component("electronic-computer", ShipComponentSlot.COMPUTER,
            Map.of(ShipEffectType.ATTACK_PERCENT, 25));
    private final ShipComponent gun = component("mass-driver", ShipComponentSlot.WEAPON,
            Map.of(ShipEffectType.WEAPON_DAMAGE, 6, ShipEffectType.WEAPON_SHOTS, 1),
            WeaponKind.PROJECTILE);
    private final ShipComponent blaster = component("neutron-blaster", ShipComponentSlot.WEAPON,
            Map.of(ShipEffectType.WEAPON_DAMAGE, 12, ShipEffectType.WEAPON_SHOTS, 2),
            WeaponKind.BEAM);
    private final ShipComponent missile = component("nuclear-missile", ShipComponentSlot.WEAPON,
            Map.of(ShipEffectType.WEAPON_DAMAGE, 8, ShipEffectType.WEAPON_SHOTS, 1),
            WeaponKind.MISSILE);
    private final ShipComponent jammer = component("ecm-jammer", ShipComponentSlot.ECM,
            Map.of(ShipEffectType.DEFENSE, 15));

    @Test
    @DisplayName("Очередь хода: быстрый корабль ходит раньше, при равной скорости решает прицел")
    void initiativeFollowsSpeedThenComputer() {
        List<ShipDesignRules.Item> slow = List.of(item(drive, 1));
        List<ShipDesignRules.Item> slowWithComputer = List.of(item(drive, 1), item(computer, 1));
        List<ShipDesignRules.Item> fast = List.of(item(fastDrive, 1));

        Integer slowInitiative = rules.initiative(stats(frigate, slow), slow);
        Integer smartInitiative = rules.initiative(stats(frigate, slowWithComputer), slowWithComputer);
        Integer fastInitiative = rules.initiative(stats(frigate, fast), fast);

        assertThat(fastInitiative).isGreaterThan(smartInitiative);
        assertThat(smartInitiative).isGreaterThan(slowInitiative);
        // Скорость весит сто, прицел добавляется процентами: 2 * 100 + 25.
        assertThat(smartInitiative).isEqualTo(225);
    }

    @Test
    @DisplayName("Шанс попасть берётся от размера корпуса цели — числа MOO II")
    void hitChanceFollowsHullSize() {
        // При равных меткости и защите кривая даёт ровно половину, и остаётся табличный
        // шанс корпуса — п. 8.
        assertThat(rules.hitChancePercent(frigate, 0, 0)).isEqualTo(20);
        assertThat(rules.hitChancePercent(doomStar, 0, 0)).isEqualTo(100);
    }

    @Test
    @DisplayName("Кривая попадания MOO II: шестнадцать очков перевеса удваивают шансы")
    void hitChanceFollowsTheCurve() {
        // 100 / (1 + 2^(−(BA − BD)/16)): шестнадцать очков перевеса дают две трети кривой
        // вместо половины, тридцать два — четыре пятых, и столько же очков отставания
        // роняют её до трети. Табличный шанс корпуса двигается по этой кривой.
        assertThat(rules.hitChancePercent(frigate, 16, 0)).isEqualTo(27);
        assertThat(rules.hitChancePercent(frigate, 32, 0)).isEqualTo(32);
        assertThat(rules.hitChancePercent(frigate, 0, 16)).isEqualTo(13);
        // Прибавка в 25 очков поднимает фрегатный шанс с 20 до 30, а не до 45: сложение
        // процентов делало модификации ствола втрое сильнее оригинала.
        assertThat(rules.hitChancePercent(frigate, 25, 0)).isEqualTo(30);
    }

    @Test
    @DisplayName("Прицел поднимает шанс, помехи роняют, но ниже пяти процентов он не падает")
    void hitChanceIsBounded() {
        assertThat(rules.hitChancePercent(frigate, 0, 15)).isEqualTo(14);
        assertThat(rules.hitChancePercent(frigate, 0, 500)).isEqualTo(5);
        assertThat(rules.hitChancePercent(doomStar, 125, 0)).isEqualTo(100);
    }

    @Test
    @DisplayName("Уклонение корпуса не считается дважды: в бой идёт только надбавка приборов")
    void defenceBonusExcludesHullEvasion() {
        List<ShipDesignRules.Item> plain = List.of(item(drive, 1));
        ShipStats stats = stats(frigate, plain);

        // Защита фрегата — 80: это его уклонение, и оно уже сидит в шансе попасть (20 %).
        assertThat(stats.defense()).isEqualTo(80);
        assertThat(rules.defenceBonus(frigate, stats)).isZero();
        // Иначе по фрегату вообще нельзя было бы попасть: 20 − 80 упиралось бы в пять.
        assertThat(rules.hitChancePercent(frigate, 0, rules.defenceBonus(frigate, stats)))
                .isEqualTo(20);

        List<ShipDesignRules.Item> jammed = List.of(item(drive, 1), item(jammer, 1));
        ShipStats jammedStats = stats(frigate, jammed);
        assertThat(rules.defenceBonus(frigate, jammedStats)).isEqualTo(15);
    }

    @Test
    @DisplayName("Залп разбирается на выстрелы: у каждого ствола свой урон")
    void shotsAreCountedPerBarrel() {
        List<Integer> shots = rules.shots(List.of(item(gun, 3), item(blaster, 2))).stream()
                .map(BattleRules.Shot::damage)
                .toList();

        // Три масс-драйвера по одному выстрелу и два бластера по два.
        assertThat(shots).containsExactly(6, 6, 6, 12, 12, 12, 12);
    }

    @Test
    @DisplayName("Залп раскладывается по видам оружия: сцене нужно, чем стреляли")
    void shotsSplitByWeaponKind() {
        Map<WeaponKind, List<BattleRules.Shot>> byKind =
                rules.shotsByKind(List.of(item(gun, 2), item(blaster, 1), item(missile, 3)));

        assertThat(damages(byKind.get(WeaponKind.PROJECTILE))).containsExactly(6, 6);
        assertThat(damages(byKind.get(WeaponKind.BEAM))).containsExactly(12, 12);
        assertThat(damages(byKind.get(WeaponKind.MISSILE))).containsExactly(8, 8, 8);
        // Сумма та же, что и без разбора: вид оружия на числа боя не влияет.
        assertThat(byKind.values().stream().flatMap(List::stream).map(BattleRules.Shot::damage).toList())
                .containsExactlyInAnyOrderElementsOf(
                        rules.shots(List.of(item(gun, 2), item(blaster, 1), item(missile, 3))).stream()
                                .map(BattleRules.Shot::damage)
                                .toList());
    }

    @Test
    @DisplayName("Пушка без вида в справочнике считается снарядом, а не пропадает")
    void weaponWithoutKindIsProjectile() {
        ShipComponent old = component("old-gun", ShipComponentSlot.WEAPON,
                Map.of(ShipEffectType.WEAPON_DAMAGE, 5, ShipEffectType.WEAPON_SHOTS, 1));

        assertThat(rules.shotsByKind(List.of(item(old, 2))))
                .containsOnlyKeys(WeaponKind.PROJECTILE);
    }

    /** Урон выстрелов списком: проверкам важны числа, а не остальное содержимое выстрела. */
    private static List<Integer> damages(List<BattleRules.Shot> shots) {
        return shots.stream().map(BattleRules.Shot::damage).toList();
    }

    @Test
    @DisplayName("Луч слабеет с расстоянием, снаряд долетает целым — п. 8")
    void beamsDissipateWithRange() {
        BattleRules.Shot beam = new BattleRules.Shot(WeaponKind.BEAM, 100, 0,
                Boolean.FALSE, Boolean.FALSE);
        BattleRules.Shot bullet = new BattleRules.Shot(WeaponKind.PROJECTILE, 100, 0,
                Boolean.FALSE, Boolean.FALSE);

        // Числа оригинала: вплотную полный урон, дальше по десятой доле за три клетки.
        assertThat(rules.damageAtRange(beam, 0)).isEqualTo(100);
        assertThat(rules.damageAtRange(beam, 2)).isEqualTo(100);
        assertThat(rules.damageAtRange(beam, 3)).isEqualTo(90);
        assertThat(rules.damageAtRange(beam, 8)).isEqualTo(80);
        assertThat(rules.damageAtRange(beam, 12)).isEqualTo(60);
        // Ниже 35 % луч не слабеет — это край таблицы оригинала (21–23 клетки).
        assertThat(rules.damageAtRange(beam, 22)).isEqualTo(35);
        assertThat(rules.damageAtRange(beam, 24)).isEqualTo(35);
        // Снарядам и ракетам рассеиваться нечему.
        assertThat(rules.damageAtRange(bullet, 20)).isEqualTo(100);
    }

    @Test
    @DisplayName("Ход по полю — боевая скорость двигателя как есть — п. 8")
    void combatSpeedIsCellsPerTurn() {
        ShipStats nuclear = stats(2, 12);
        ShipStats none = stats(3, 0);

        assertThat(rules.cellsPerTurn(nuclear)).isEqualTo(12);
        // Проект без боевой скорости (старая партия) ходит своей звёздной.
        assertThat(rules.cellsPerTurn(none)).isEqualTo(3);
    }

    /** Характеристики только со скоростями: прочему этих проверок не нужно. */
    private static ShipStats stats(int speed, int combatSpeed) {
        return new ShipStats(0, 0, 0, 0, 0, 0, speed, combatSpeed, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("Модификация ствола меняет урон, меткость и дальность выстрела — п. 8")
    void modificationsChangeTheShot() {
        WeaponModification heavy = catalog.modification("heavy-mount");
        WeaponModification piercing = catalog.modification("armor-piercing");

        List<BattleRules.Shot> shots = rules.shots(List.of(
                new ShipDesignRules.Item(gun, 1, List.of(heavy, piercing))));

        // Масс-драйвер бьёт на шесть, тяжёлый — на девять, вдвое дальше, и броню он
        // проходит насквозь.
        assertThat(shots).hasSize(1);
        assertThat(shots.get(0).damage()).isEqualTo(9);
        assertThat(shots.get(0).range()).isEqualTo(BattleRules.WEAPON_RANGE * 2);
        assertThat(shots.get(0).piercesArmour()).isTrue();
        assertThat(shots.get(0).piercesShield()).isFalse();
    }

    @Test
    @DisplayName("Ближняя оборона бьёт вдвое ближе и вдвое слабее, зато метче — п. 8")
    void pointDefenceIsShortAndWeak() {
        BattleRules.Shot shot = rules.shots(List.of(new ShipDesignRules.Item(
                blaster, 1, List.of(catalog.modification("point-defense"))))).get(0);

        assertThat(shot.damage()).isEqualTo(6);
        assertThat(shot.range()).isEqualTo(BattleRules.WEAPON_RANGE / 2);
        assertThat(shot.attackPercent()).isEqualTo(25);
        // Дальше своей половины поля такой ствол не достаёт вовсе.
        assertThat(rules.shotReaches(shot, BattleRules.WEAPON_RANGE / 2)).isTrue();
        assertThat(rules.shotReaches(shot, BattleRules.WEAPON_RANGE / 2 + 1)).isFalse();
    }

    @Test
    @DisplayName("Автоматический огонь стреляет трижды, но каждым выстрелом хуже — п. 8")
    void autoFireShootsThriceLessAccurately() {
        List<BattleRules.Shot> shots = rules.shots(List.of(new ShipDesignRules.Item(
                blaster, 1, List.of(catalog.modification("auto-fire")))));

        // У нейтронного бластера два ствола, автоматический огонь делает из них шесть.
        assertThat(shots).hasSize(6);
        assertThat(shots.get(0).attackPercent()).isEqualTo(-20);
    }

    @Test
    @DisplayName("Обволакивающий удар приходится на все стороны, дальность его не ослабляет")
    void envelopingAndNoRangePenalty() {
        BattleRules.Shot enveloping = rules.shots(List.of(new ShipDesignRules.Item(
                blaster, 1, List.of(catalog.modification("enveloping"))))).get(0);
        BattleRules.Shot steady = rules.shots(List.of(new ShipDesignRules.Item(
                blaster, 1, List.of(catalog.modification("no-range-penalty"))))).get(0);
        BattleRules.Shot plain = rules.shots(List.of(new ShipDesignRules.Item(
                blaster, 1, List.of()))).get(0);

        // Четыре стороны корабля получают удар разом — у нас это множитель к прошедшему.
        assertThat(rules.envelopingFactor(enveloping)).isEqualTo(4);
        assertThat(rules.envelopingFactor(plain)).isEqualTo(1);
        // Обычный луч на другом конце поля слабеет, а этот бьёт в полную силу.
        assertThat(rules.damageAtRange(plain, 20)).isLessThan(plain.damage());
        assertThat(rules.damageAtRange(steady, 20)).isEqualTo(steady.damage());
    }

    @Test
    @DisplayName("Ракету сбивает постановщик помех, а луч и снаряд — нет — п. 8")
    void missileEvasionStopsMissilesOnly() {
        BattleRules.Shot rocket = new BattleRules.Shot(WeaponKind.MISSILE, 8, 0,
                Boolean.FALSE, Boolean.FALSE);
        BattleRules.Shot beam = new BattleRules.Shot(WeaponKind.BEAM, 8, 0,
                Boolean.FALSE, Boolean.FALSE);
        BattleRules.Shot precise = new BattleRules.Shot(WeaponKind.BEAM, 8, 25,
                Boolean.FALSE, Boolean.FALSE);
        BattleRules.Shot guided = rules.shots(List.of(new ShipDesignRules.Item(
                missile, 1, List.of(catalog.modification("eccm"))))).get(0);

        // Уклонение цели от ракет идёт в её защиту, и кривая попадания та же.
        assertThat(rules.shotHitChancePercent(frigate, 25, 0, rocket, 40)).isEqualTo(14);
        assertThat(rules.shotHitChancePercent(frigate, 25, 0, beam, 40)).isEqualTo(30);
        // Меткость модификации идёт в плюс любому выстрелу.
        assertThat(rules.shotHitChancePercent(frigate, 25, 0, precise, 40)).isEqualTo(36);
        // Помехозащита режет чужое уклонение вдвое — попадать становится вдвое легче.
        assertThat(rules.shotHitChancePercent(frigate, 25, 0, guided, 40)).isEqualTo(22);
    }

    @Test
    @DisplayName("Щит гасит каждый выстрел по отдельности: рой мелких об него разбивается")
    void shieldStopsEachShot() {
        assertThat(rules.damageThroughShield(6, 6)).isZero();
        assertThat(rules.damageThroughShield(6, 10)).isZero();
        assertThat(rules.damageThroughShield(30, 10)).isEqualTo(20);
    }

    @Test
    @DisplayName("Расстояние считается по диагонали, дальность залпа — край таблицы луча")
    void distanceAndRange() {
        assertThat(rules.distance(0, 0, 3, 3)).isEqualTo(3);
        assertThat(rules.distance(0, 0, 7, 2)).isEqualTo(7);
        assertThat(rules.inRange(0, 0, BattleRules.WEAPON_RANGE, BattleRules.WEAPON_RANGE)).isTrue();
        assertThat(rules.inRange(0, 0, BattleRules.WEAPON_RANGE + 1, 0)).isFalse();
    }

    @Test
    @DisplayName("Стороны входят в бой с разных краёв поля и строем по центру")
    void deploymentFacesEachOther() {
        int[] attacker = rules.startingCell(BattleSide.ATTACKER, 0, 2);
        int[] defender = rules.startingCell(BattleSide.DEFENDER, 0, 2);

        assertThat(attacker[0]).isEqualTo(1);
        assertThat(defender[0]).isEqualTo(BattleRules.FIELD_WIDTH - 2);
        // Строй из двух кораблей встаёт по центру поля, каким бы оно ни было.
        int middle = (BattleRules.FIELD_HEIGHT - 2) / 2;
        assertThat(attacker[1]).isEqualTo(middle);
        assertThat(defender[1]).isEqualTo(middle);
        assertThat(rules.onField(attacker[0], attacker[1])).isTrue();
        assertThat(rules.onField(BattleRules.FIELD_WIDTH, 0)).isFalse();
    }

    @Test
    @DisplayName("Строй длиннее поля уходит во второй столбец, а не громоздится в клетке")
    void longLineTakesSecondColumn() {
        int total = BattleRules.FIELD_HEIGHT + 3;
        List<String> cells = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            int[] cell = rules.startingCell(BattleSide.ATTACKER, index, total);
            cells.add(cell[0] + ":" + cell[1]);
        }

        // Клетки не повторяются: две единицы в одной клетке поле не различает.
        assertThat(cells).doesNotHaveDuplicates();
        // Первый столбец заполняется целиком, остальные встают за ним.
        assertThat(cells.subList(0, BattleRules.FIELD_HEIGHT)).allMatch(cell -> cell.startsWith("1:"));
        assertThat(cells.subList(BattleRules.FIELD_HEIGHT, total)).allMatch(cell -> cell.startsWith("2:"));
    }

    @Test
    @DisplayName("Попадание разыгрывается по шансу: сто процентов бьют всегда, пять — почти никогда")
    void rollFollowsChance() {
        Random random = new Random(42);
        int hits = 0;
        for (int shot = 0; shot < 1000; shot++) {
            if (Boolean.TRUE.equals(rules.rollHit(random, 100))) {
                hits++;
            }
        }
        assertThat(hits).isEqualTo(1000);

        hits = 0;
        for (int shot = 0; shot < 1000; shot++) {
            if (Boolean.TRUE.equals(rules.rollHit(random, 5))) {
                hits++;
            }
        }
        assertThat(hits).isBetween(20, 90);
    }

    private ShipStats stats(ShipHull hull, List<ShipDesignRules.Item> items) {
        return designRules.stats(hull, items, RaceEffects.NONE);
    }

    private ShipDesignRules.Item item(ShipComponent component, Integer count) {
        return new ShipDesignRules.Item(component, count);
    }

    private ShipComponent component(String code, ShipComponentSlot slot,
                                    Map<ShipEffectType, Integer> effects) {
        return component(code, slot, effects, null);
    }

    private ShipComponent component(String code, ShipComponentSlot slot,
                                    Map<ShipEffectType, Integer> effects, WeaponKind kind) {
        return new ShipComponent(code, LocalizedText.of(code), LocalizedText.of(""), slot, 4, 10, null, effects, kind, Boolean.FALSE, null, 1, Boolean.FALSE);
    }

}
