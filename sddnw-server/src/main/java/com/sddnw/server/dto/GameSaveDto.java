package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сохранённая партия в списке загрузки — без самого состояния: в диалоге нужен только
 * заголовок строки.
 *
 * @param turn номер завершённого хода, на конец которого снято состояние
 * @param auto слепок снят игрой в конце хода, а не человеком — п. 3
 * @param loadable дозволено ли поднять его этой учётной записи: автосохранение партии
 *                 на двоих поднимают только те же люди, сделанное руками — кто угодно
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
        OffsetDateTime savedAt,
        Boolean auto,
        Boolean loadable
) {
}
