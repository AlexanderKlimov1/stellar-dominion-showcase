package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Перекос событий к ведущей империи — backlog-promo, пункт 7.
 * <p>
 * Обещано «втрое», и обещание обязано держаться при любом числе империй: прежняя формула
 * растила каждую ступень и давала семикратный перекос на четверых и пятнадцатикратный на
 * восьмерых, а проверить это было нечем.
 */
class GalacticEventWeightTest {

    @ParameterizedTest(name = "империй {0}")
    @ValueSource(ints = {2, 3, 4, 6, 8})
    @DisplayName("Первый весит ровно втрое больше последнего, лестница растёт")
    void firstIsThreeTimesLast(int size) {
        int last = GalacticEventService.standingWeight(0, size);
        int first = GalacticEventService.standingWeight(size - 1, size);

        assertEquals(3 * last, first, "первый против последнего при " + size + " империях");
        for (int rank = 1; rank < size; rank++) {
            assertTrue(GalacticEventService.standingWeight(rank, size)
                            > GalacticEventService.standingWeight(rank - 1, size),
                    "соседние места не слипаются: " + rank);
        }
    }

    @ParameterizedTest(name = "империй {0}")
    @ValueSource(ints = {1})
    @DisplayName("Одна империя в жребии — вес единица")
    void single(int size) {
        assertEquals(1, GalacticEventService.standingWeight(0, size));
    }
}
