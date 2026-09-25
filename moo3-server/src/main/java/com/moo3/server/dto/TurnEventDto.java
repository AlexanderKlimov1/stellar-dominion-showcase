package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Событие хода — то, что изменилось у игрока, пока считалась галактика (п. 11.1).
 * <p>
 * Игроки ходят одновременно и узнают о пересчёте по подписке, а не по своему запросу,
 * поэтому им нужно не только новое состояние, но и рассказ о том, что произошло: где
 * выросла колония, что достроилось, что украли.
 * <p>
 * <b>В базе лежит ключ с подстановками, а не готовая строка</b> — п. 3.5 (локализация).
 * Событие рождается внутри посчитанного хода, то есть до того, как кто-нибудь попросил
 * его показать: языка в этот миг нет. Готовой строкой текст становится при показе, на
 * языке запроса ({@code GameEventService.shown}), — и тот же отчёт читается по-русски и
 * по-английски, а не на языке того, кто закончил ход первым.
 *
 * @param code     вид события: POPULATION, BUILDING, COLONY_BASE, SPY, INCOME, RESEARCH,
 *                 ESPIONAGE — по нему клиент выбирает значок и цвет
 * @param text     готовая строка для журнала хода; пусто, пока отчёт лежит в базе
 * @param key      ключ словаря сервера; пусто у отчётов, сохранённых до перехода на ключи
 * @param args     подстановки к ключу: имена, числа и ключи ярлыков перечислений
 * @param systemId система, которой касается событие; пусто — событие про всю империю
 * @param planetId планета, которой касается событие; пусто — событие не про планету
 */
public record TurnEventDto(
        String code,
        String text,
        String key,
        List<String> args,
        UUID systemId,
        UUID planetId
) {

    /** Событие, каким оно ложится в базу: ключ и подстановки, текста ещё нет. */
    public static TurnEventDto stored(String code, String key, List<String> args,
                                      UUID systemId, UUID planetId) {
        return new TurnEventDto(code, null, key, args, systemId, planetId);
    }

    /**
     * То же событие с готовой строкой — для ответа игроку.
     * <p>
     * Ключ и подстановки остаются: по ним видно, из чего собрана строка, а лишнего веса в
     * ответе они не дают — клиент читает {@code text}.
     */
    public TurnEventDto shown(String ready) {
        return new TurnEventDto(code, ready, key, args, systemId, planetId);
    }
}
