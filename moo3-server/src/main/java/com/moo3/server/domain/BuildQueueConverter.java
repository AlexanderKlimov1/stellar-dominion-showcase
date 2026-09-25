package com.moo3.server.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Очередь стройки колонии в одной колонке — п. 10.
 * <p>
 * Очередь это просто порядок кодов из списка стройки ({@code colony.available}), и своей
 * таблицы она не стоит: фаза производства спрашивает её у каждой колонии каждый ход, а
 * планеты ход вычитывает целиком. Отдельная таблица дала бы выборку «на планету» изнутри
 * посчитанного хода — ту самую, которой в этом проекте уже наступали на грабли.
 * <p>
 * Разделитель — запятая: в кодах её нет ни у зданий (латиница с дефисами), ни у особых
 * проектов (заглавные с подчёркиванием), ни у кораблей («SHIP:&lt;проект&gt;»).
 */
@Converter
public class BuildQueueConverter implements AttributeConverter<List<String>, String> {

    private static final String SEPARATOR = ",";

    @Override
    public String convertToDatabaseColumn(List<String> queue) {
        // Пустая очередь — пустая колонка: отличать «нет очереди» от «очередь из нуля
        // проектов» незачем, а null в базе читается проще, чем пустая строка.
        return queue == null || queue.isEmpty() ? null : String.join(SEPARATOR, queue);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return new ArrayList<>();
        }
        // Список изменяемый: очередь правят на месте — добавляют, убирают, переставляют.
        return new ArrayList<>(Arrays.asList(column.split(SEPARATOR)));
    }
}
