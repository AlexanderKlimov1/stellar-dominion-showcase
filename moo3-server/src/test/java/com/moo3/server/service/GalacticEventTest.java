package com.moo3.server.service;

import com.moo3.server.domain.enums.GalacticEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Справочник случайных галактических событий — п. 11.1.
 * <p>
 * От знака события зависит, кому оно достанется: дурное тянется к ведущей империи, доброе
 * — к отставшей. Ошибка в знаке не падает и не видна на экране: она просто начинает
 * добивать отстающих, а это ровно наоборот тому, как устроен MOO II.
 */
class GalacticEventTest {

    @Test
    @DisplayName("Есть и добрые события, и дурные: без обеих половин правило теряет смысл")
    void bothSidesExist() {
        long good = Arrays.stream(GalacticEvent.values())
                .filter(event -> Boolean.TRUE.equals(event.getGood())).count();
        long bad = GalacticEvent.values().length - good;

        assertTrue(good >= 5, "добрых событий должно хватать на выбор: " + good);
        assertTrue(bad >= 5, "дурных событий должно хватать на выбор: " + bad);
    }

    @Test
    @DisplayName("У каждого события есть название по-русски и вес в жребии")
    void everyEventIsFilled() {
        for (GalacticEvent event : GalacticEvent.values()) {
            assertNotNull(event.getLabel(), event.name());
            assertTrue(event.getLabel().length() > 3, event.name() + ": пустое название");
            assertNotNull(event.getGood(), event.name());
            assertTrue(event.getWeight() > 0, event.name() + ": нулевой вес не выпадет никогда");
        }
    }

    @Test
    @DisplayName("Знаки событий совпадают с оригиналом: находка — добро, пираты — беда")
    void signsMatchTheOriginal() {
        assertEquals(Boolean.TRUE, GalacticEvent.ANCIENT_SHIP.getGood());
        assertEquals(Boolean.TRUE, GalacticEvent.SECRET_EXPERIMENT.getGood());
        assertEquals(Boolean.TRUE, GalacticEvent.AXIS_SHIFT.getGood());
        assertEquals(Boolean.TRUE, GalacticEvent.WORMHOLE.getGood());
        assertEquals(Boolean.FALSE, GalacticEvent.PIRATES.getGood());
        assertEquals(Boolean.FALSE, GalacticEvent.EARTHQUAKE.getGood());
        assertEquals(Boolean.FALSE, GalacticEvent.COMPUTER_VIRUS.getGood());
        assertEquals(Boolean.FALSE, GalacticEvent.WARP_FUNNEL.getGood());
    }

    @Test
    @DisplayName("Названия событий не повторяются: в итогах хода их различают по ним")
    void labelsAreUnique() {
        long distinct = Arrays.stream(GalacticEvent.values())
                .map(GalacticEvent::getLabel).distinct().count();

        assertEquals(GalacticEvent.values().length, distinct);
    }
}
