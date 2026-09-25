package com.moo3.server.service;

import com.moo3.server.domain.enums.GalaxySize;
import com.moo3.server.domain.enums.ScannerTech;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Дальности сканеров — п. 15.
 * <p>
 * От них зависит, что игрок видит на карте, поэтому таблица проверена: опечатка в
 * коде технологии молча лишила бы империю всех сканеров, и никто бы не заметил.
 */
class ScannerTechTest {

    @Test
    @DisplayName("Без технологий сканеров дальность нулевая")
    void withoutTechnologies() {
        assertEquals(0, ScannerTech.bestRange(Set.of()));
        assertEquals(0, ScannerTech.bestRange(Set.of("laser-cannon", "biospheres")));
    }

    @Test
    @DisplayName("Работает лучший из изученных сканеров, а не их сумма")
    void bestWins() {
        Integer space = ScannerTech.bestRange(Set.of("space-scanner"));
        Integer both = ScannerTech.bestRange(Set.of("space-scanner", "tachyon-scanner"));

        assertEquals(ScannerTech.SPACE_SCANNER.getRangeParsecs(), space);
        assertEquals(ScannerTech.TACHYON_SCANNER.getRangeParsecs(), both,
                "сканеры не складываются: смотрит самый дальнобойный");
    }

    @Test
    @DisplayName("Дальность растёт вместе с уровнем технологии")
    void rangeGrowsWithLevel() {
        assertTrue(ScannerTech.SPACE_SCANNER.getRangeParsecs() < ScannerTech.TACHYON_SCANNER.getRangeParsecs());
        assertTrue(ScannerTech.TACHYON_SCANNER.getRangeParsecs() < ScannerTech.NEUTRON_SCANNER.getRangeParsecs());
        assertTrue(ScannerTech.NEUTRON_SCANNER.getRangeParsecs() < ScannerTech.SENSORS.getRangeParsecs());
    }

    /**
     * Дальности — парсеки MOO II, а не «вся галактика» — п. 15.
     * <p>
     * Пока карта мерялась своей единицей, последний сканер нарочно накрывал галактику
     * целиком: числа были подобраны от её размеров. Теперь и карта, и сканеры в парсеках,
     * и числа взяты у оригинала: «Space Scanner замечает корабли за 1 + класс размера
     * парсеков», Tachyon — за 3 + класс, Neutron — за 5 + класс. Видеть всё сразу они не
     * должны: разведка остаётся делом кораблей.
     */
    @Test
    @DisplayName("Дальности сканеров — парсеки оригинала, а не вся галактика")
    void rangesAreParsecsOfTheOriginal() {
        // Середина диапазона оригинала: 2–7, 4–9 и 6–11 парсеков в зависимости от
        // размера замеченного корабля, а система — цель среднего размера.
        assertEquals(4, ScannerTech.SPACE_SCANNER.getRangeParsecs());
        assertEquals(6, ScannerTech.TACHYON_SCANNER.getRangeParsecs());
        assertEquals(8, ScannerTech.NEUTRON_SCANNER.getRangeParsecs());
        assertTrue(ScannerTech.SENSORS.getRangeParsecs() < GalaxySize.HUGE.getWidthParsecs(),
                "даже лучший сканер не показывает всю галактику разом");
        assertTrue(ScannerTech.SENSORS.getRangeParsecs() > ScannerTech.NEUTRON_SCANNER.getRangeParsecs());
    }

    @Test
    @DisplayName("Коды совпадают с кодами дерева технологий")
    void codesMatchTechTree() {
        assertEquals("space-scanner", ScannerTech.SPACE_SCANNER.getCode());
        assertEquals("tachyon-scanner", ScannerTech.TACHYON_SCANNER.getCode());
        assertEquals("neutron-scanner", ScannerTech.NEUTRON_SCANNER.getCode());
        assertEquals("sensors", ScannerTech.SENSORS.getCode());
    }
}
