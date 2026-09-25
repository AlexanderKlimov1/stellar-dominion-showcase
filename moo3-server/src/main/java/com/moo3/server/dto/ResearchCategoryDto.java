package com.moo3.server.dto;

import java.util.List;

/** Раздел дерева технологий: прямая последовательность уровней без ответвлений — п. 9. */
public record ResearchCategoryDto(
        String code,
        String name,
        String description,
        List<ResearchLevelDto> levels
) {
}
