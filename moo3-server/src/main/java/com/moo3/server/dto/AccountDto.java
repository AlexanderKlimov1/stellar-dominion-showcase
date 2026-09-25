package com.moo3.server.dto;

import java.util.UUID;

/**
 * Учётная запись для клиента — п. 3.1. Пароля здесь нет и быть не может: наружу уходит
 * только то, чем игрок представляется другим.
 *
 * @param name      имя для показа в интерфейсе игры; логином служит почта
 * @param roleLabel роль на языке запроса, для интерфейса
 * @param locale    язык игрока — п. 3.5: {@code en}, {@code ru} или пусто, если игрок его
 *                  не называл. Клиент ставит его сразу после входа: язык записи переживает
 *                  и браузер, и машину, а {@code localStorage} — нет
 * @param textScale размер текста интерфейса в процентах — п. 11.1: 100, 115, 130, 150 или
 *                  пусто, если игрок его не называл. Едет за игроком по той же причине,
 *                  что и язык
 */
public record AccountDto(
        UUID id,
        String login,
        String email,
        String name,
        String role,
        String roleLabel,
        String locale,
        Integer textScale
) {
}
