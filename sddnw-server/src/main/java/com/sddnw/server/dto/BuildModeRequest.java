package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Как колония ведёт очередь стройки — п. 10, кнопки {@code REPEAT BUILD} и
 * {@code AUTO BUILD} оригинала.
 *
 * @param repeatBuild очередь идёт по кругу: взятое с неё возвращается в конец. Пусто —
 *                    оставить как было: окно переключает признаки по одному
 * @param autoBuild   колония сама берёт следующее дело, когда очередь опустела
 */
public record BuildModeRequest(
        @NotBlank
        String accessToken,

        Boolean repeatBuild,

        Boolean autoBuild
) {
}
