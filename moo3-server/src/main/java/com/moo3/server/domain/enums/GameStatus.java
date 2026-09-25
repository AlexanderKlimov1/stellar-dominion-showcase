package com.moo3.server.domain.enums;

public enum GameStatus {
    /** Игра создана и видна в списке, идёт набор игроков (п. 3.1, 3.2). */
    LOBBY,
    /** Галактика сгенерирована, игра идёт. */
    IN_PROGRESS,
    FINISHED
}
