package com.moo3.server.dto;

import java.util.List;

/**
 * Особенность расы в конструкторе — п. 7.
 *
 * @param picks       цена в очках расы; отрицательная — особенность возвращает очки
 * @param excludes    коды особенностей, с которыми эта не встаёт
 * @param effects     что особенность меняет: одна может менять сразу несколько сторон
 */
public record RaceTraitDto(
        String code,
        String name,
        String description,
        Integer picks,
        List<String> excludes,
        List<Effect> effects
) {

    /**
     * Одно действие особенности.
     *
     * @param type   тип действия: часть идёт колониям, часть — бою, шпионажу и кораблям
     * @param amount величина
     */
    public record Effect(String type, Integer amount) {
    }
}
