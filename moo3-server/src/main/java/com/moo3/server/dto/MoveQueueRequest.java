package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Куда переставить проект в очереди стройки — п. 10.
 * <p>
 * Новое место приходит числом, а не «выше» и «ниже»: очередь короткая, и перетащить
 * проект сразу на своё место дешевле, чем нажимать стрелку четыре раза. Экрану это не
 * мешает — стрелка просто просит место по соседству.
 *
 * @param toIndex место в очереди, считая от нуля
 */
public record MoveQueueRequest(
        @NotBlank
        String accessToken,

        @NotNull
        @PositiveOrZero
        Integer toIndex
) {
}
