package com.moo3.server.service;

import com.moo3.server.domain.enums.FuelTech;
import com.moo3.server.domain.enums.GalaxySize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила перелёта — п. 8: дальность, время в пути и смена курса.
 * <p>
 * Числа взяты из MOO II (4/6/9/12 парсеков и неограниченно), и опечатка в коде технологии
 * молча оставила бы империю с начальной дальностью навсегда — как это уже было со
 * сканерами, см. {@link ScannerTechTest}.
 */
class FlightRulesTest {

    private final FlightRules rules = new FlightRules();

    @Test
    @DisplayName("Без топливных технологий работают Standard Fuel Cells: 4 парсека")
    void withoutTechnologies() {
        assertEquals(4, rules.rangeParsecs(Set.of()));
        assertEquals(4, rules.rangeParsecs(Set.of("laser-cannon", "biospheres")));
    }

    @Test
    @DisplayName("Дальность даёт лучший элемент, а не сумма изученных")
    void bestFuelWins() {
        assertEquals(9, rules.rangeParsecs(Set.of("deuterium-fuel-cells", "iridium-fuel-cells")));
        assertEquals(FuelTech.UNLIMITED_PARSECS,
                rules.rangeParsecs(Set.of("thorium-fuel-cells", "standard-fuel-cells")));
    }

    @Test
    @DisplayName("Дополнительные баки прибавляют половину дальности")
    void extendedTanksAddHalf() {
        assertEquals(6, rules.rangeParsecs(Set.of("standard-fuel-cells", "extended-fuel-tanks")));
        assertEquals(13, rules.rangeParsecs(Set.of("iridium-fuel-cells", "extended-fuel-tanks")));
        assertEquals(FuelTech.UNLIMITED_PARSECS,
                rules.rangeParsecs(Set.of("thorium-fuel-cells", "extended-fuel-tanks")),
                "неограниченной дальности прибавлять нечего");
    }

    /**
     * Карта и топливо меряются одним и тем же — п. 4.2, п. 8.
     * <p>
     * Раньше между ними стоял множитель ГРЕ→парсек, свой у каждого размера галактики, и
     * от него зависело, дотянется ли империя до соседа. Теперь дальность одна на все
     * галактики: четыре парсека Standard Fuel Cells — это четыре парсека карты.
     */
    @Test
    @DisplayName("Начальной дальности хватает до соседей в любой галактике")
    void reachesNeighbourInEveryGalaxy() {
        for (GalaxySize size : GalaxySize.values()) {
            double cell = Math.sqrt((double) size.getWidthParsecs() * size.getHeightParsecs()
                    / size.getTotalStarCount());
            double range = rules.rangeParsecs(Set.of());
            assertTrue(range > cell,
                    "в галактике " + size + " цепочка перелётов рвалась бы на первом же разрыве");
            assertTrue(range < size.getHeightParsecs(),
                    "в галактике " + size + " дальность перестала бы что-либо ограничивать");
        }
    }

    @Test
    @DisplayName("Размеры галактик — из оригинала, в парсеках")
    void galaxySizesOfTheOriginal() {
        assertEquals(20, GalaxySize.SMALL.getWidthParsecs());
        assertEquals(27, GalaxySize.MEDIUM.getWidthParsecs());
        assertEquals(33, GalaxySize.LARGE.getWidthParsecs());
        assertEquals(38, GalaxySize.HUGE.getWidthParsecs());
        for (GalaxySize size : GalaxySize.values()) {
            double ratio = (double) size.getWidthParsecs() / size.getHeightParsecs();
            assertTrue(ratio > 1.3 && ratio < 1.5,
                    "пропорция карты MOO II — 1,4:1, у " + size + " вышло " + ratio);
        }
    }

    @Test
    @DisplayName("Время в пути — расстояние на скорость, но не меньше хода")
    void travelTime() {
        assertEquals(5, rules.travelTurns(10.0, 2), "10 парсеков на скорости 2");
        assertEquals(2, rules.travelTurns(10.0, 6), "10 парсеков на скорости 6");
        assertEquals(1, rules.travelTurns(1.0, 2), "мгновенных перелётов не бывает");
        assertEquals(1, rules.travelTurns(0.0, 0), "флот без скорости всё равно летит");
    }

    @Test
    @DisplayName("Быстрый флот приходит не позже медленного")
    void fasterIsNotSlower() {
        Integer slow = rules.travelTurns(30.0, 2);
        Integer fast = rules.travelTurns(30.0, 7);
        assertTrue(fast < slow);
    }

    @Test
    @DisplayName("Расстояние — обычная прямая между звёздами, в парсеках")
    void distance() {
        assertEquals(5.0, rules.distanceParsecs(0.0, 0.0, 3.0, 4.0), 0.0001);
        assertEquals(2.5, rules.distanceParsecs(1.0, 1.0, 3.5, 1.0), 0.0001);
    }

    @Test
    @DisplayName("Курс в полёте меняет только Hyperspace Communications")
    void redirectNeedsTech() {
        assertFalse(rules.canRedirect(Set.of("subspace-communications", "tachyon-communications")));
        assertTrue(rules.canRedirect(Set.of(FlightRules.REDIRECT_TECH)));
    }

    /**
     * Дальность знакомства — п. 15: та же, что у полёта, но без дополнительных баков.
     * <p>
     * В MOO II контакт возникает, когда одна из сторон может послать корабли <b>без
     * дополнительных баков</b> в системы, занятые другой. Если считать знакомство по
     * общей дальности, одна технология баков знакомила бы империю с половиной галактики
     * разом — а в оригинале она на контакт не влияет вовсе.
     */
    @Test
    @DisplayName("Знакомство меряется топливом без дополнительных баков — п. 15")
    void contactRangeIgnoresExtendedTanks() {
        Set<String> withTanks = Set.of(
                FuelTech.DEUTERIUM_FUEL_CELLS.getCode(), FuelTech.EXTENDED_TANKS_CODE);

        assertEquals(6, FuelTech.baseRangeParsecs(withTanks));
        // Полёту баки дальность прибавляют: 6 + половина.
        assertEquals(9, rules.rangeParsecs(withTanks));

        // Без баков обе величины совпадают.
        Set<String> plain = Set.of(FuelTech.DEUTERIUM_FUEL_CELLS.getCode());
        assertEquals(6, FuelTech.baseRangeParsecs(plain));
        assertEquals(6, rules.rangeParsecs(plain));

        // Без топливных технологий — те же начальные 4 парсека.
        assertEquals(4, FuelTech.baseRangeParsecs(Set.of()));
    }
}
