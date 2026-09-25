package com.moo3.server.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Подстановки события игрока одной колонкой — п. 3.5 (локализация).
 * <p>
 * Событие ждёт конца хода ключом и подстановками: языка получателя внутри хода нет, и
 * готовой строки взяться неоткуда. Подстановки — имена, числа и ключи ярлыков; своей
 * таблицей они дали бы выборку на событие там, где событий за ход десятки.
 * <p>
 * <b>Разделитель — служебный знак, а не запятая.</b> В подстановке лежит и название
 * планеты, и название постройки, и имя игрока: запятая или точка с запятой встречаются в
 * них законно, и список развалился бы на первом же таком названии. Знак U+001F для этого
 * и придуман — он не бывает в тексте, который вводит человек.
 */
@Converter
public class EventArgsConverter implements AttributeConverter<List<String>, String> {

    private static final String SEPARATOR = "";

    @Override
    public String convertToDatabaseColumn(List<String> args) {
        // Событие без подстановок — пустая колонка: пустая строка читалась бы как список
        // из одной пустой подстановки, и сообщение получило бы лишний аргумент.
        return args == null || args.isEmpty() ? null : String.join(SEPARATOR, args);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        if (column == null || column.isEmpty()) {
            return new ArrayList<>();
        }
        // -1 у split: подстановка бывает и пустой строкой, и терять её нельзя — иначе
        // сообщение соберётся с меньшим числом аргументов, чем ждёт ключ.
        return new ArrayList<>(Arrays.asList(column.split(SEPARATOR, -1)));
    }
}
