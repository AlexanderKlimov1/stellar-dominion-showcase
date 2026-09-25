package com.moo3.server.dto;

/**
 * Состояние Орионометра — записи игры человека в ОРИГИНАЛ (MOO II).
 *
 * @param running        идёт ли запись прямо сейчас
 * @param gameOpen       открыто ли окно самой игры: без него записывать нечего
 * @param seconds        сколько секунд длится запись
 * @param clicks         сколько нажатий записано
 * @param shots          сколько снимков отложено
 * @param scene          на какой сцене было последнее нажатие; пусто — сцена не узнана
 * @param log            где лежит протокол словами
 * @param data           где лежит протокол строками для разбора
 * @param brainRunning   идёт ли прогон нейросети через Орионометр
 * @param failure        почему не вышло; пусто — всё в порядке
 */
public record OrionometerDto(
        Boolean running,
        Boolean gameOpen,
        Double seconds,
        Integer clicks,
        Integer shots,
        String scene,
        String log,
        String data,
        Boolean brainRunning,
        String failure
) {
}
