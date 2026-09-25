package com.moo3.server.dto;

import java.util.UUID;

/** Игрок в лобби и в игре — п. 3.2. */
public record PlayerDto(
        UUID id,
        Integer slot,
        String name,
        String playerType,
        String raceCode,
        String raceName,
        String color,
        UUID homeSystemId,
        Integer credits,

        /** Правительство империи — п. 14; пусто, если раса собрана без него. */
        String government,

        /** Накоплено очков шпионажа — п. 13. */
        Integer espionagePoints,

        /** Грузовых кораблей у империи — п. 4.1.1: они возят еду голодающим колониям. */
        Integer freighters,

        /**
         * Сколько грузовиков занято перевозкой жителей — п. 4.1.1: по одному на единицу
         * населения, пока рейс не дойдёт. Эти еду не возят.
         */
        Integer freightersReserved,

        /**
         * Игрок объявил конец текущего хода — п. 11.1: по этому признаку в интерфейсе
         * видно, кого ещё ждёт партия. У ИИ всегда истина: он никого не задерживает.
         */
        Boolean turnEnded
) {
}
