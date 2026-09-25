package com.moo3.server.domain.enums;

import java.util.List;

/**
 * К чему стремится империя ИИ — п. 15.
 * <p>
 * Шесть устремлений MOO II. В оригинале они стоят рядом с характером в окне Report:
 * «агрессивный <i>промышленник</i>», — и подсказывают, куда сосед вкладывается. Сам
 * оригинал предупреждает, что это «не безошибочный указатель»: устремление задаёт
 * склонность, а не расписание.
 * <p>
 * Здесь устремление решает <b>куда идут исследования</b>: раздел дерева ИИ берёт не
 * ровным жребием по всем восьми, а из своих излюбленных, и только если в них не осталось
 * чего изучать — из остальных. Так экспансионист и правда уходит в двигатели, а
 * технолог — в науку.
 * <p>
 * <i>Реконструкция:</i> какие именно разделы дерева тянет каждое устремление, оригинал
 * называет лишь общими словами («экспансионисты налегают на двигатели и планетологию»,
 * «милитаристы — на флот и оружие»), поэтому соответствие разделам расставлено по смыслу
 * названий разделов MOO II. Правится в одном месте — этом.
 */
public enum AiObjective {

    /** Экспансионист: «жаждет новых земель, налегает на двигатели и планетологию». */
    EXPANSIONIST("Экспансионист", List.of("power", "biology", "chemistry")),

    /** Милитарист: «ставит на первое место флот и оружие», дерётся за ничейные планеты. */
    MILITARIST("Милитарист", List.of("physics", "force_fields", "engineering")),

    /** Технолог: «сосредоточен на технологиях», флот и оборону запускает. */
    TECHNOLOGIST("Технолог", List.of("computers", "physics", "force_fields")),

    /** Промышленник: главное — производственная мощь. */
    INDUSTRIALIST("Промышленник", List.of("engineering", "chemistry", "power")),

    /** Эколог: возится с мирами — климат, население, чистота. */
    ECOLOGIST("Эколог", List.of("biology", "sociology", "chemistry")),

    /** Дипломат: берёт не силой, а договорами и голосами. */
    DIPLOMAT("Дипломат", List.of("sociology", "computers", "biology"));

    private final String label;
    private final List<String> favouriteCategories;

    AiObjective(String label, List<String> favouriteCategories) {
        this.label = label;
        this.favouriteCategories = favouriteCategories;
    }

    public String getLabel() {
        return label;
    }

    /** Излюбленные разделы дерева: из них ИИ выбирает, пока в них есть что изучать. */
    public List<String> getFavouriteCategories() {
        return favouriteCategories;
    }

    /** Дипломат охотнее прочих идёт на договоры и подарки — п. 15. */
    public Boolean favoursDiplomacy() {
        return this == DIPLOMAT;
    }
}
