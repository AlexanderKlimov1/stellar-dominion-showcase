package com.moo3.server.dto;

import java.time.OffsetDateTime;

/**
 * Сеанс после входа — п. 3.1: пропуск, срок его жизни и кто вошёл.
 *
 * @param token пропуск учётной записи; клиент шлёт его заголовком {@code X-Account-Token}
 */
public record SessionDto(
        String token,
        OffsetDateTime expiresAt,
        AccountDto account
) {
}
