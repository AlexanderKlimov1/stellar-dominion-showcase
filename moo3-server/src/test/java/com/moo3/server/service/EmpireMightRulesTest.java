package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мощь империи — п. 11.1, п. 15.
 * <p>
 * Числом рисуется первая линия графика окна «Инфо» и им же решают империи ИИ, нападать
 * ли. Ошибка в весе не падает и не видна на экране: она просто делает войну невозможной
 * или, наоборот, всеобщей.
 */
class EmpireMightRulesTest {

    private final EmpireMightRules rules = new EmpireMightRules();

    @Test
    @DisplayName("Мощь складывается из флота, населения, выработки и изученного")
    void sumsEveryPart() {
        // 8 жителей (8000 тыс.) — 32, производство 10 — 20, наука 5 — 10, три технологии — 30.
        // Сила флота делится на десять (журнал, п. 3.75): сотня даёт десять.
        assertEquals(10 + 32 + 20 + 10 + 30, rules.might(100, 8000, 10, 5, 3));
    }

    @Test
    @DisplayName("Пустая империя не сильнее никого")
    void emptyEmpireIsNothing() {
        assertEquals(0, rules.might(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("Каждая часть прибавляет мощи, и ни одна не отнимает")
    void everyPartAdds() {
        Integer base = rules.might(0, 8000, 8, 8, 0);
        assertTrue(rules.might(500, 8000, 8, 8, 0) > base, "флот");
        assertTrue(rules.might(0, 16000, 8, 8, 0) > base, "население");
        assertTrue(rules.might(0, 8000, 16, 8, 0) > base, "производство");
        assertTrue(rules.might(0, 8000, 8, 16, 0) > base, "наука");
        assertTrue(rules.might(0, 8000, 8, 8, 1) > base, "технологии");
    }

    @Test
    @DisplayName("В начале партии ни одна часть не забивает прочие")
    void startingEmpireIsBalanced() {
        // Стартовая колония: 8 жителей, по 8 производства и науки, без флота и технологий.
        Integer population = rules.might(0, 8000, 0, 0, 0);
        Integer output = rules.might(0, 0, 8, 8, 0);
        assertTrue(population <= output * 2 && output <= population * 2,
                "доли населения и выработки сопоставимы: " + population + " и " + output);
    }

    @Test
    @DisplayName("Один корабль не весит больше всей империи — п. 11.1")
    void oneShipDoesNotOutweighTheEmpire() {
        // Проверка ТОЙ ЖЕ меры «ни одна часть не забивает прочие», но НА ВСЕЙ ПАРТИИ, а не
        // на первом ходу. Веса были выверены на первом — когда флота ещё нет, — и флот
        // вошёл в сумму без множителя: один линкор стоил 1083 мощи при всей стартовой
        // империи в 144 (журнал, п. 3.75). Числа здесь взяты из той самой летописи.
        Integer battleship = rules.might(1083, 0, 0, 0, 0);
        Integer starting = rules.might(0, 8000, 8, 8, 8);
        assertTrue(battleship <= starting,
                "линкор " + battleship + " против стартовой империи " + starting);

        // И середина партии: хозяйство империи 150-го хода против её же единственного линкора.
        Integer economy = rules.might(0, 25000, 42, 30, 20);
        assertTrue(battleship * 2 <= economy,
                "линкор " + battleship + " против хозяйства " + economy);
    }
}
