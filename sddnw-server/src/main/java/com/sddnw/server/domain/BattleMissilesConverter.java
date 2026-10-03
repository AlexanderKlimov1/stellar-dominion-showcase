package com.sddnw.server.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * Ракеты в полёте одной колонкой боя — backlog-promo, пункт 30.
 * <p>
 * Своей таблицы они не стоят: живут круг-два, их у боя считанные единицы, а читаются и
 * пишутся всегда вместе с самим боем. Тот же приём, что у очереди стройки планеты
 * ({@link BuildQueueConverter}), только состав у записи богаче строки кода — поэтому JSON.
 */
@Converter
public class BattleMissilesConverter implements AttributeConverter<List<BattleMissile>, String> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<BattleMissile>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<BattleMissile> missiles) {
        if (missiles == null || missiles.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(missiles);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Ракеты боя не записываются", failure);
        }
    }

    @Override
    public List<BattleMissile> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.readValue(column, TYPE));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Ракеты боя не читаются", failure);
        }
    }
}
