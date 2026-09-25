package com.moo3.server.domain.save;

/**
 * Замер империи за один ход в слепке партии — п. 11.1.
 * <p>
 * Летопись сохраняется вместе с партией, потому что пересчитать её нечем: население и
 * флот прошлых ходов нигде больше не лежат. Без неё загруженная партия открывала бы окно
 * «Инфо» с пустым графиком, хотя за плечами у империи сотня ходов.
 */
public record EmpireHistorySnapshot(
        Integer turn,
        Integer populationK,
        Integer colonies,
        /** Постройки ценой; у слепков, снятых до графика построек, поля нет. */
        Integer buildings,
        Integer production,
        Integer research,
        Integer fleetPower,
        Integer technologies,
        Integer credits
) {
}
