package com.moo3.server.service;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Ссылка на запись справочника в отчёте хода — п. 3.5.
 * <p>
 * Отчёт хода хранится ключом с подстановками и собирается текстом при чтении. Подстановка,
 * которая называет здание или технологию, обязана быть СССЫЛКОЙ, а не названием: название
 * теперь переводится, а ход считает один игрок, тогда как отчёт открывают все. Положи в
 * подстановку готовое название — и русский читатель увидел бы «Colony Achernar I built:
 * Automated Factory» либо, наоборот, английский — «Автоматический завод». Именно так и
 * выглядела эта ошибка до перевода справочников.
 * <p>
 * Ссылка пишется как ключ словаря, чтобы её нельзя было спутать с текстом:
 * {@code catalog.building.star-base}, {@code catalog.tech.colony-ship}. Неизвестная ссылка
 * отдаётся как есть — пропущенная запись видна на экране, а не прячется за исключением.
 * <p>
 * Список названий в одной подстановке (дар технологий сразу за несколько) разбирается по
 * запятой: каждая ссылка переводится отдельно, остальное остаётся как написано.
 */
@Service
public class CatalogTexts {

    /** Приставка ссылки на здание. */
    public static final String BUILDING = "catalog.building.";

    /** Приставка ссылки на технологию. */
    public static final String TECH = "catalog.tech.";

    /** Чем разделены ссылки, когда их в подстановке несколько. */
    private static final String LIST_SEPARATOR = ", ";

    private final BuildingCatalog buildings;
    private final ResearchCatalog research;

    public CatalogTexts(BuildingCatalog buildings, ResearchCatalog research) {
        this.buildings = buildings;
        this.research = research;
    }

    /** Ссылка на здание для подстановки в отчёт хода. */
    public static String building(String code) {
        return BUILDING + code;
    }

    /** Ссылка на технологию для подстановки в отчёт хода. */
    public static String tech(String code) {
        return TECH + code;
    }

    /**
     * Подстановка отчёта на языке читателя: ссылка на справочник превращается в название,
     * остальное возвращается неизменным.
     */
    public String resolve(String arg) {
        if (arg == null || !arg.contains("catalog.")) {
            return arg;
        }
        if (arg.contains(LIST_SEPARATOR)) {
            return Arrays.stream(arg.split(LIST_SEPARATOR))
                    .map(this::resolveOne)
                    .collect(Collectors.joining(LIST_SEPARATOR));
        }
        return resolveOne(arg);
    }

    private String resolveOne(String arg) {
        if (arg.startsWith(BUILDING)) {
            String code = arg.substring(BUILDING.length());
            var building = buildings.byCode().get(code);
            return building == null ? code : building.name();
        }
        if (arg.startsWith(TECH)) {
            String code = arg.substring(TECH.length());
            var place = research.places().get(code);
            return place == null ? code : place.option().name();
        }
        return arg;
    }
}
