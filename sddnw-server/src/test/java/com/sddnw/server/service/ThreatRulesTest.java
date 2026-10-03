package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Когда игрок замечает летящий к нему флот — backlog-promo, пункт 9.
 * <p>
 * Предупреждение не вправе знать больше, чем знали бы глаза игрока: летящий флот без
 * сканера виден лишь на подлёте, со сканером — в его пределах.
 */
class ThreatRulesTest {

    @Test
    @DisplayName("Флот, вылетевший на ходу 10 и садящийся в конце хода 14, на ходу 12 — в двух ходах")
    void turnsLeftCountsLanding() {
        // Фаза прибытия сажает флоты с arrivalTurn <= turn + 1: arrivalTurn 15 садится в
        // конце хода 14.
        assertEquals(2, ThreatRules.turnsLeft(15, 12));
        assertEquals(1, ThreatRules.turnsLeft(15, 13));
    }

    @Test
    @DisplayName("Доля пути растёт ровно по ходам и не выходит за концы")
    void progressIsLinear() {
        assertEquals(0.2, ThreatRules.progress(10, 15, 10), 1e-9);
        assertEquals(0.6, ThreatRules.progress(10, 15, 12), 1e-9);
        assertEquals(1.0, ThreatRules.progress(10, 15, 20), 1e-9);
    }

    @Test
    @DisplayName("Без сканера флот виден только на подлёте")
    void withoutScannerOnlyOnApproach() {
        assertFalse(ThreatRules.noticed(15, 12, 0.5, 0), "за два хода до посадки без сканера не видно");
        assertTrue(ThreatRules.noticed(15, 13, 99.0, 0), "на подлёте видно всегда");
    }

    @Test
    @DisplayName("Со сканером флот заметен в пределах его дальности, и не дальше")
    void scannerRangeDecides() {
        assertTrue(ThreatRules.noticed(20, 12, 4.0, 4), "на краю дальности — заметен");
        assertFalse(ThreatRules.noticed(20, 12, 4.1, 4), "за краем — нет");
    }
}
