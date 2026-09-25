package com.moo3.server.dto;

import java.util.List;

/**
 * Ствол корабля на поле боя — п. 8, строка таблицы «Weapons» окна осмотра MOO II.
 * <p>
 * Тот же состав, что показывает окно дизайна, но взглядом из боя: сколько стволов, чем
 * бьёт каждый и что на нём навешено. Одинаковые стволы с РАЗНЫМИ модификациями стоят
 * разными строками — так и в оригинале («2 Fusion Beams · Hv», «3 Fusion Beams · None»).
 *
 * @param name          название оружия на языке читателя
 * @param count         сколько таких стволов на корабле
 * @param damage        урон одного выстрела с поправкой модификаций
 * @param kind          вид оружия: луч, снаряд, ракета — им сцена рисует залп
 * @param wrecked       сколько гнёзд этого ствола выбито попаданиями по корпусу — п. 8;
 *                      окно осмотра показывает не «пушка есть», а «пушка есть, да не
 *                      стреляет»
 * @param modifications названия модификаций этого ствола; пусто — их нет
 */
public record BattleWeaponDto(
        String name,
        Integer count,
        Integer damage,
        String kind,
        List<String> modifications,
        Integer wrecked
) {
}
