package com.sddnw.server.dto;

/** Вариант размера галактики для экрана создания игры — п. 4.2. */
public record GalaxySizeDto(
        String code,
        String label,
        Integer widthParsecs,
        Integer heightParsecs,
        Integer starCount,
        Integer totalStarCount,
        Boolean defaultChoice
) {
}
