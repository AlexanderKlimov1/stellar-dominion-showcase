package com.moo3.server.dto;

/**
 * Технология на выбор внутри уровня — п. 9.
 *
 * @param recommended  технология отмечена в описании дерева как предпочтительная
 * @param section      раздел списка изученного в окне «Инфо» — п. 11.1: одна из четырёх
 *                     частей окна Tech Review MOO II. Пусто у дерева, взятого из
 *                     справочника напрямую: раздел проставляет {@code TechnologySections}
 * @param sectionLabel тот же раздел по-русски, для кнопок окна
 */
public record ResearchOptionDto(
        String code,
        String name,
        String description,
        Boolean recommended,
        String section,
        String sectionLabel
) {
}
