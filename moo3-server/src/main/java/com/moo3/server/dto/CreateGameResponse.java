package com.moo3.server.dto;

import java.util.List;

/**
 * Ответ на создание игры (п. 3.1) и на присоединение к ней (п. 3.2):
 * состояние лобби плюс личные учётные данные игрока.
 */
public record CreateGameResponse(
        GameSummaryDto game,
        List<PlayerDto> players,
        PlayerCredentialsDto credentials
) {
}
