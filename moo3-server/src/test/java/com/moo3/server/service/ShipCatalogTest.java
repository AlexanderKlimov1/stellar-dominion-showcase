package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.ShipComponent;
import com.moo3.server.domain.ShipHull;
import com.moo3.server.domain.WeaponModification;
import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.WeaponKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Справочник кораблей — п. 8. Проверка читает тот самый файл, который уходит в игру:
 * опечатка в нём ломает сборку, а не партию.
 */
class ShipCatalogTest {

    private final ShipCatalog catalog = new ShipCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    @Test
    @DisplayName("Шесть корпусов MOO II по порядку, командные очки от 1 до 6")
    void hulls() {
        // Корабельные корпуса — те самые шесть оригинала. Платформы обороны (п. 8, п. 11)
        // в этот счёт не идут: их не строят на стапеле и в окне дизайна их нет.
        List<ShipHull> hulls = catalog.hulls().stream()
                .filter(hull -> !Boolean.TRUE.equals(hull.platform()))
                .toList();
        assertEquals(6, hulls.size());
        assertEquals(List.of("frigate", "destroyer", "cruiser", "battleship", "titan", "doom-star"),
                hulls.stream().map(ShipHull::code).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), hulls.stream().map(ShipHull::command).toList());

        ShipHull previous = null;
        for (ShipHull hull : hulls) {
            assertTrue(hull.space() > 0, hull.code());
            assertTrue(hull.cost() > 0, hull.code());
            assertTrue(hull.structure() > 0, hull.code());
            assertTrue(hull.hitChancePercent() >= 20 && hull.hitChancePercent() <= 100, hull.code());
            assertTrue(hull.systemFactor() > 0, hull.code());
            if (previous != null) {
                // Корпуса идут по возрастанию: крупнее — просторнее, дороже и заметнее.
                assertTrue(hull.space() > previous.space(), hull.code());
                assertTrue(hull.cost() > previous.cost(), hull.code());
                assertTrue(hull.hitChancePercent() >= previous.hitChancePercent(), hull.code());
            }
            previous = hull;
        }
    }

    @Test
    @DisplayName("Империя начинает партию с двигателем, бронёй и пушкой — как в MOO II")
    void startingComponentsExist() {
        assertTrue(startsWithout(ShipComponentSlot.ENGINE), "нет стартового двигателя");
        assertTrue(startsWithout(ShipComponentSlot.ARMOR), "нет стартовой брони");
        assertTrue(startsWithout(ShipComponentSlot.WEAPON), "нет стартового оружия");
    }

    @Test
    @DisplayName("У каждого компонента есть гнездо, место, цена и хоть одно действие")
    void components() {
        List<ShipComponent> components = catalog.components();
        assertTrue(components.size() > 40, "справочник компонентов подозрительно мал");
        assertEquals(components.size(), components.stream().map(ShipComponent::code).distinct().count());

        for (ShipComponent component : components) {
            assertNotNull(component.name(), component.code());
            assertNotNull(component.slot(), component.code());
            assertTrue(component.space() > 0, "место компонента " + component.code());
            assertTrue(component.cost() > 0, "цена компонента " + component.code());
            assertTrue(!component.effects().isEmpty(), "компонент без действия: " + component.code());
        }
    }

    @Test
    @DisplayName("У каждой пушки есть урон и выстрелы, у каждого двигателя — скорость")
    void weaponsAndEngines() {
        for (ShipComponent weapon : catalog.components(ShipComponentSlot.WEAPON)) {
            assertTrue(weapon.amount(ShipEffectType.WEAPON_DAMAGE) > 0, weapon.code());
            assertTrue(weapon.amount(ShipEffectType.WEAPON_SHOTS) > 0, weapon.code());
        }
        for (ShipComponent engine : catalog.components(ShipComponentSlot.ENGINE)) {
            assertTrue(engine.amount(ShipEffectType.SPEED) > 0, engine.code());
        }
    }

    @Test
    @DisplayName("Самый дешёвый корпус — фрегат: с него начинается стартовый проект")
    void cheapestHullIsFrigate() {
        // Платформы обороны стоят ноль (их покупают зданием), и без оговорки «не платформа»
        // самым дешёвым корпусом игры оказалась бы звёздная база — на ней и уехали бы
        // колонисты. Проверка держит именно эту оговорку.
        assertEquals("frigate", catalog.cheapestHull().code());
    }

    @Test
    @DisplayName("Платформы обороны: корпуса есть, но кораблями не считаются — п. 8, п. 11")
    void platforms() {
        List<ShipHull> platforms = catalog.hulls().stream()
                .filter(hull -> Boolean.TRUE.equals(hull.platform()))
                .toList();
        assertEquals(List.of("star-base", "battle-station", "star-fortress", "planetary-battery",
                        "planetary-shield"),
                platforms.stream().map(ShipHull::code).toList());
        for (ShipHull hull : platforms) {
            // Платформа никуда не летит и командных очков не тратит, а цены у неё нет
            // вовсе: за неё платят зданием колонии.
            assertEquals(0, hull.cost(), hull.code());
            assertEquals(0, hull.command(), hull.code());
            assertTrue(hull.space() > 0, hull.code());
            assertTrue(hull.structure() > 0, hull.code());
        }
        // Лестница орбиты идёт по возрастанию: база, станция, крепость.
        assertTrue(platforms.get(1).structure() > platforms.get(0).structure());
        assertTrue(platforms.get(2).structure() > platforms.get(1).structure());

        // Планетарный щит — единственный корпус игры, который НЕ НЕСЁТ ОРУЖИЯ (п. 11): он
        // держит огонь на себе, а не открывает его. Если признак потеряется, щит начнёт
        // стрелять, и заметить это будет нечем, кроме этой строки.
        ShipHull shield = platforms.get(4);
        assertTrue(Boolean.TRUE.equals(shield.unarmed()), shield.code());
        assertTrue(platforms.stream().filter(one -> Boolean.TRUE.equals(one.unarmed())).count() == 1);
    }

    @Test
    @DisplayName("Модификации ствола: восемь для лучей со снарядами и шесть для ракет — п. 8")
    void modifications() {
        List<WeaponModification> modifications = catalog.modifications();
        assertEquals(14, modifications.size());

        for (WeaponModification modification : modifications) {
            assertNotNull(modification.name(), modification.code());
            assertTrue(!modification.appliesTo().isEmpty(), modification.code());
        }

        // Наборы не смешиваются: ствол переделывают установкой, ракету — ею самой.
        assertTrue(catalog.modification("heavy-mount").fits(WeaponKind.BEAM));
        assertTrue(!catalog.modification("heavy-mount").fits(WeaponKind.MISSILE));
        assertTrue(catalog.modification("mirv").fits(WeaponKind.MISSILE));
        assertTrue(!catalog.modification("mirv").fits(WeaponKind.BEAM));
        // Обволакивающий удар и непрерывный луч снаряду недоступны: это свойства луча.
        assertTrue(!catalog.modification("enveloping").fits(WeaponKind.PROJECTILE));
        assertTrue(!catalog.modification("continuous").fits(WeaponKind.PROJECTILE));
    }

    @Test
    @DisplayName("Набор модификаций у каждого оружия свой — п. 8")
    void modificationsAreNarrowedPerWeapon() {
        WeaponModification pointDefence = catalog.modification("point-defense");
        WeaponModification heavy = catalog.modification("heavy-mount");
        WeaponModification eccm = catalog.modification("eccm");

        // Лёгкий луч ближней обороной становится, тяжёлый — нет: так и в оригинале, где
        // ускоритель частиц её берёт, а луч смерти уже нет.
        assertTrue(catalog.component("laser-cannon").carries(pointDefence));
        assertTrue(!catalog.component("plasma-cannon").carries(pointDefence));
        assertTrue(catalog.component("plasma-cannon").carries(heavy));
        // Звёздный конвертер не переделывают вовсе.
        assertTrue(!catalog.component("stellar-converter").carries(heavy));
        // Ракете чужие модификации не подходят, а свои — да.
        assertTrue(!catalog.component("nuclear-missile").carries(heavy));
        assertTrue(catalog.component("nuclear-missile").carries(eccm));
        // Не-оружие не переделывают ничем.
        assertTrue(!catalog.component("nuclear-drive").carries(heavy));
    }

    /** Есть ли в гнезде компонент, доступный без исследований. */
    private Boolean startsWithout(ShipComponentSlot slot) {
        return catalog.components(slot).stream()
                .anyMatch(component -> component.requiredTechCode() == null);
    }
}
