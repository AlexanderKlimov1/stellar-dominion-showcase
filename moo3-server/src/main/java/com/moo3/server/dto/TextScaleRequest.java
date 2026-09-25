package com.moo3.server.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Размер текста интерфейса — п. 11.1: проценты (100, 115, 130 или 150).
 * <p>
 * Проценты, а не кегль: кеглей в игре два десятка, а множитель у них один, и он же стоит в
 * корне стилей клиента. Что процент один из четырёх, проверяет служба, а не аннотация:
 * список ступеней живёт в одном месте ({@code AccountService.SUPPORTED_TEXT_SCALES}), и
 * перечислять его вторым списком здесь значило бы завести две правды об одном — ровно так
 * же, как у языка ({@link LocaleRequest}).
 */
public record TextScaleRequest(
        @NotNull Integer textScale
) {
}
