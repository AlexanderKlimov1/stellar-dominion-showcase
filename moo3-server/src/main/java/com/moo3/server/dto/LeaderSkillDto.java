package com.moo3.server.dto;

/**
 * Способность лидера с силой при нынешнем звании — п. 6.
 *
 * @param ability     код способности
 * @param name        название по-русски
 * @param kind        род: общая, колониальная, корабельная
 * @param unit        в чём измеряется: проценты, очки, кредиты
 * @param value       сила при нынешнем звании
 * @param perLevel    прирост за каждое следующее звание
 * @param works       работает всегда или только по месту службы
 * @param description что делает
 * @param note        пусто, если способность действует; иначе — почему нет
 */
public record LeaderSkillDto(
        String ability,
        String name,
        String kind,
        String unit,
        Integer value,
        Double perLevel,
        String works,
        String description,
        String note
) {
}
