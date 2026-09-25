package com.moo3.server.dto;

import java.util.List;

/**
 * Дерево технологий для экрана выбора исследования — п. 9.
 *
 * @param version       версия описания из файла дерева
 * @param categories    восемь разделов, каждый — своя последовательность уровней
 * @param startingLevels уровни, которые есть у всех рас с первого хода, — ссылками
 *                       «раздел:номер» ({@code power:1}). Ссылка, а не название: название
 *                       переводится (п. 3.5), а опознаватель уровня — его место в дереве
 */
public record ResearchTreeDto(
        String version,
        List<ResearchCategoryDto> categories,
        List<String> startingLevels
) {
}
