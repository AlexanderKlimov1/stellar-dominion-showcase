package com.moo3.server.service;

import com.moo3.server.domain.LocalizedText;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.WeaponKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Поломка бортовых систем, взрыв корабля и самоподрыв — п. 8.
 * <p>
 * Правило MOO II: щит и броня держат удар, а прошедшее сквозь них рвёт корабль изнутри.
 * Здесь проверяется арифметика этого правила — что считается системой, что остаётся у
 * корабля после поломки и как далеко бьёт взрыв. Сам розыгрыш (кому не повезло) живёт в
 * бою, и его проверяет сквозной прогон: юнит-тест на жребий проверял бы генератор
 * случайных чисел, а не игру.
 */
class BattleSystemDamageTest {

    private final BattleRules rules = new BattleRules();

    private final ShipComponent drive = component("nuclear-drive", ShipComponentSlot.ENGINE,
            Map.of(ShipEffectType.COMBAT_SPEED, 12));
    private final ShipComponent shield = component("class-i-shield", ShipComponentSlot.SHIELD,
            Map.of(ShipEffectType.SHIELD, 2));
    private final ShipComponent computer = component("electronic-computer",
            ShipComponentSlot.COMPUTER, Map.of(ShipEffectType.ATTACK_PERCENT, 25));
    private final ShipComponent armour = component("titanium-armor", ShipComponentSlot.ARMOR,
            Map.of(ShipEffectType.ARMOUR, 4));
    private final ShipComponent gun = component("mass-driver", ShipComponentSlot.WEAPON,
            Map.of(ShipEffectType.WEAPON_DAMAGE, 6, ShipEffectType.WEAPON_SHOTS, 1));

    /** Крейсер с четырьмя стволами: двигатель, щит, прицел, броня и пушки. */
    private List<ShipDesignRules.Item> cruiser() {
        return List.of(
                new ShipDesignRules.Item(drive, 1),
                new ShipDesignRules.Item(shield, 1),
                new ShipDesignRules.Item(computer, 1),
                new ShipDesignRules.Item(armour, 1),
                new ShipDesignRules.Item(gun, 4));
    }

    @Test
    @DisplayName("Система — это ГНЕЗДО: четыре одинаковых ствола ломаются по одному")
    void everyMountIsItsOwnSystem() {
        List<String> live = rules.liveSystems(cruiser(), List.of());
        assertEquals(7, live.size(), "двигатель, щит, прицел и четыре ствола: " + live);
        assertEquals(4, live.stream().filter("mass-driver"::equals).count());
        assertFalse(live.contains("titanium-armor"), "броню держит корпус, ломать её нечего");

        List<String> afterOne = rules.liveSystems(cruiser(), List.of("mass-driver"));
        assertEquals(3, afterOne.stream().filter("mass-driver"::equals).count(),
                "разбитый ствол не уносит с собой три уцелевших");
    }

    @Test
    @DisplayName("Разбитая пушка не стреляет, сожжённый прицел не целится")
    void wreckedPartsStopWorking() {
        List<ShipDesignRules.Item> left = rules.partsLeft(cruiser(),
                List.of("mass-driver", "mass-driver", BattleRules.COMPUTER_SYSTEM));

        Integer mounts = left.stream()
                .filter(item -> item.component().slot() == ShipComponentSlot.WEAPON)
                .mapToInt(ShipDesignRules.Item::count)
                .sum();
        assertEquals(2, mounts, "из четырёх стволов замолчали два");
        assertEquals(0, rules.attackPercent(left), "сожжённый компьютер прицела не даёт");
        assertEquals(25, rules.attackPercent(cruiser()), "у целого корабля прицел на месте");

        // Двигатель и щит из состава не убираются: скорость и щит корабль держит своими
        // полями боя, и убрать их отсюда значило бы посчитать поломку дважды.
        List<ShipDesignRules.Item> noDrive = rules.partsLeft(cruiser(),
                List.of(BattleRules.ENGINE_SYSTEM, BattleRules.SHIELD_SYSTEM));
        assertTrue(noDrive.stream().anyMatch(item -> item.component().equals(drive)));
        assertTrue(noDrive.stream().anyMatch(item -> item.component().equals(shield)));
    }

    @Test
    @DisplayName("Выбитые все стволы — залпа нет вовсе")
    void shipWithoutGunsHasNoSalvo() {
        List<String> broken = new ArrayList<>(List.of("mass-driver", "mass-driver",
                "mass-driver", "mass-driver"));
        List<ShipDesignRules.Item> left = rules.partsLeft(cruiser(), broken);
        assertTrue(rules.shots(left).isEmpty(), "стрелять нечем");
        assertTrue(rules.liveSystems(cruiser(), broken).contains(BattleRules.ENGINE_SYSTEM),
                "двигатель при этом цел: ломается то, что ещё не разбито");
    }

    @Test
    @DisplayName("Ломать нечего — жребий возвращает пусто, а не выдумывает систему")
    void nothingLeftToWreck() {
        List<String> everything = rules.liveSystems(cruiser(), List.of());
        assertNull(rules.pickSystem(new Random(1), rules.liveSystems(cruiser(), everything)));
        assertEquals(0, rules.liveSystems(cruiser(), everything).size());
    }

    @Test
    @DisplayName("Взрыв считается от ПОЛНОЙ прочности корпуса, и крупный бьёт дальше")
    void blastGrowsWithTheHull() {
        ShipHull frigate = hull("frigate", 20);
        ShipHull battleship = hull("battleship", 160);

        assertEquals(10, rules.blastDamage(frigate), "половина прочности фрегата");
        assertEquals(80, rules.blastDamage(battleship), "половина прочности дредноута");
        assertEquals(1, rules.blastRadius(frigate), "мелкий рвёт только соседнюю клетку");
        assertEquals(2, rules.blastRadius(battleship), "дредноут достаёт дальше");
    }

    @Test
    @DisplayName("В быстром бою взрывы считаются жребием от зерна партии, а не долей")
    void fastBlastsFollowTheSeed() {
        Integer first = rules.fastBlastLosses(new Random(777), 100);
        Integer again = rules.fastBlastLosses(new Random(777), 100);
        assertEquals(first, again, "тот же бой в том же прогоне обязан кончаться тем же");
        assertTrue(first > 0 && first < 100, "взрывается часть погибших, а не все: " + first);
        assertEquals(0, rules.fastBlastLosses(new Random(1), 0), "гибнуть некому — нечему и рваться");
    }

    private ShipComponent component(String code, ShipComponentSlot slot,
                                    Map<ShipEffectType, Integer> effects) {
        return new ShipComponent(code, LocalizedText.of(code), LocalizedText.of(""), slot,
                4, 10, null, effects,
                slot == ShipComponentSlot.WEAPON ? WeaponKind.PROJECTILE : null,
                Boolean.FALSE, null, 1, Boolean.FALSE);
    }

    private ShipHull hull(String code, Integer structure) {
        return new ShipHull(code, LocalizedText.of(code), LocalizedText.of(""), 120, 110,
                structure, 3, 40, 4, null, 3, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
    }
}
