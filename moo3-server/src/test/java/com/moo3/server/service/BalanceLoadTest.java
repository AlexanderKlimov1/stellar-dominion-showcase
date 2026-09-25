package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сколько партий прогон считает разом — п. 2 этапа 2.
 * <p>
 * Правило проверяется по НАЗВАННОМУ часу, а не по системным часам: проверка, зависящая от
 * того, в котором часу её запустили, проходит полдня и падает вторую половину.
 */
class BalanceLoadTest {

    @Test
    @DisplayName("ночью потоков больше, днём меньше")
    void nightAndDay() {
        BalanceLoad load = new BalanceLoad();
        load.update(0, 8, 12, 6);

        assertTrue(load.night(0), "полночь — начало окна, входит");
        assertTrue(load.night(3));
        assertTrue(load.night(7), "последний час окна");
        assertFalse(load.night(8), "восемь — конец окна, уже не входит");
        assertFalse(load.night(15));
    }

    @Test
    @DisplayName("окно переходит через полночь")
    void overMidnight() {
        BalanceLoad load = new BalanceLoad();
        // Ночь с двадцати двух до шести — обычный случай для «после работы».
        load.update(22, 6, 12, 4);

        assertTrue(load.night(23), "до полуночи");
        assertTrue(load.night(0), "и после неё");
        assertTrue(load.night(5));
        assertFalse(load.night(6), "шесть — конец окна");
        assertFalse(load.night(12));
    }

    @Test
    @DisplayName("пустое окно значит «ночи нет»")
    void emptyWindow() {
        BalanceLoad load = new BalanceLoad();
        load.update(3, 3, 12, 6);

        assertFalse(load.night(3), "начало и конец совпали — окна нет вовсе");
        assertFalse(load.night(0));
    }

    @Test
    @DisplayName("пул делается по наибольшему пределу")
    void poolTakesTheLarger() {
        BalanceLoad load = new BalanceLoad();
        load.update(0, 8, 12, 6);
        assertEquals(12, load.poolSize());

        // И наоборот: днём может быть больше, чем ночью, — пул всё равно по большему.
        load.update(0, 8, 4, 9);
        assertEquals(9, load.poolSize());
    }

    @Test
    @DisplayName("правка на ходу не пускает бессмыслицу")
    void updateKeepsSane() {
        BalanceLoad load = new BalanceLoad();
        load.update(26, -1, 0, -5);

        assertEquals(2, load.nightFrom(), "часы приводятся к суткам: 26 это 2");
        assertEquals(23, load.nightUntil(), "-1 это 23");
        assertTrue(load.nightWorkers() >= 1, "меньше одного потока не бывает");
        assertTrue(load.dayWorkers() >= 1);
    }

    @Test
    @DisplayName("пропущенное поле оставляет прежнее значение")
    void updateKeepsMissing() {
        BalanceLoad load = new BalanceLoad();
        load.update(0, 8, 12, 6);
        load.update(null, null, null, 3);

        assertEquals(0, load.nightFrom());
        assertEquals(8, load.nightUntil());
        assertEquals(12, load.nightWorkers());
        assertEquals(3, load.dayWorkers());
    }
}
