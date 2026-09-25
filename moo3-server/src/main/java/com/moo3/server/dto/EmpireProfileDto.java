package com.moo3.server.dto;

import java.util.List;

/**
 * Империя в окне «Инфо» — п. 11.1: кто это, чем её раса особенна и как шли её дела.
 * <p>
 * Свойства расы MOO II показывает «для всех империй, с которыми игрок в контакте», —
 * поэтому здесь они у каждой знакомой империи, а не только у своей: по ним и судят, чего
 * от соседа ждать в бою, в науке и на переговорах.
 *
 * @param own        своя ли это империя: на графике своя линия выделена
 * @param government правительство расы (п. 14) — оно тоже выбирается в конструкторе
 * @param character  характер и устремление правителя ИИ (п. 15); пусто у человека
 * @param traits     стороны расы (п. 7) в порядке конструктора
 * @param history    замеры по ходам, от первого к последнему
 */
public record EmpireProfileDto(
        java.util.UUID playerId,
        String name,
        String raceName,
        String color,
        Boolean own,
        String government,
        String character,
        List<EmpireTraitDto> traits,
        List<EmpireHistoryPointDto> history
) {

    /**
     * Сторона расы в окне «Инфо» — п. 7: название и цена в очках.
     *
     * @param picks цена особенности; отрицательная — особенность возвращает очки
     */
    public record EmpireTraitDto(String code, String name, Integer picks) {
    }
}
