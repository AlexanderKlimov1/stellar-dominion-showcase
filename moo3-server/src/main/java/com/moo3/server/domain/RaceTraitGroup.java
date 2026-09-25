package com.moo3.server.domain;

import java.util.List;

/**
 * Группа особенностей расы — п. 7: одна сторона расы.
 * <p>
 * Обычная группа — переключатель: из роста населения, еды, промышленности, науки, денег
 * и правительства берут не больше одного варианта. Группа с {@code multiple} — набор
 * независимых сторон, и берут из неё сколько угодно: так в MOO II устроены особые
 * способности, где всевидящий бывает и скрытным, и подземным разом.
 *
 * @param multiple можно ли взять несколько вариантов сразу
 */
public record RaceTraitGroup(
        String code,
        LocalizedText names,
        LocalizedText descriptions,
        Boolean multiple,
        List<RaceTrait> options
) {

    /** Название на языке читателя — п. 3.5. */
    public String name() {
        return names.text();
    }

    /** Описание на языке читателя — п. 3.5. */
    public String description() {
        return descriptions == null ? null : descriptions.text();
    }
}
