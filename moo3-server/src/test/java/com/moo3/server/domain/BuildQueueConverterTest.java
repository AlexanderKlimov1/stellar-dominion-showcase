package com.moo3.server.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Очередь стройки в одной колонке — п. 10.
 * <p>
 * Проверяется то, на чём такое хранение может сломаться молча: порядок, пустая очередь и
 * коды кораблей с двоеточием и UUID внутри. Ошибка здесь не падает — колония просто
 * забывает, что собиралась строить.
 */
class BuildQueueConverterTest {

    private final BuildQueueConverter converter = new BuildQueueConverter();

    @Test
    @DisplayName("Очередь возвращается в том же порядке, в каком её набрали")
    void keepsOrder() {
        List<String> queue = List.of("automated-factory", "research-laboratory", "SPY");

        assertEquals(queue, converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(queue)));
    }

    @Test
    @DisplayName("Код корабля переживает запись: в нём и двоеточие, и номер проекта")
    void keepsShipCodes() {
        String ship = "SHIP:" + UUID.randomUUID();
        List<String> queue = List.of(ship, "TRANSPORT");

        assertEquals(queue, converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(queue)));
    }

    @Test
    @DisplayName("Пустая очередь ложится в базу пустотой, а не пустой строкой")
    void emptyQueueIsNull() {
        assertEquals(null, converter.convertToDatabaseColumn(List.of()));
        assertTrue(converter.convertToEntityAttribute(null).isEmpty());
        assertTrue(converter.convertToEntityAttribute("").isEmpty());
    }

    @Test
    @DisplayName("Прочитанная очередь изменяемая: её правят добавлением и перестановкой")
    void readQueueIsMutable() {
        List<String> queue = converter.convertToEntityAttribute("automated-factory");
        queue.add("SPY");

        assertEquals(List.of("automated-factory", "SPY"), queue);
    }
}
