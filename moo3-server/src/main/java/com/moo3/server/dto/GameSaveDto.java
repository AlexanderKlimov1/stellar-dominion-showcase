package com.moo3.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сохранённая партия в списке загрузки — без самого состояния: в диалоге нужен только
 * заголовок строки.
 *
 * @param turn номер завершённого хода, на конец которого снято состояние
 */
public record GameSaveDto(
        UUID id,
        UUID gameId,
        String name,
        String galaxySize,
        Integer widthParsecs,
        Integer heightParsecs,
        Integer starCount,
        Integer turn,
        Integer humanPlayers,
        Integer totalPlayers,
        OffsetDateTime savedAt
) {
}
