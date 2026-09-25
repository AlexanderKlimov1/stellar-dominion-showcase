package com.moo3.server.domain;

import com.moo3.server.domain.enums.RaceEffectType;

import java.util.List;
import java.util.Map;

/**
 * Особенность расы из конструктора — п. 7.
 * <p>
 * Игрок покупает особенности очками расы: положительные стоят очков, отрицательные их
 * возвращают. Одна особенность может менять сразу несколько вещей — так устроены
 * правительства MOO II: демократия и богатеет, и лучше исследует, но хуже шпионит.
 *
 * @param groupCode   группа, из которой выбрана особенность
 * @param picks       во сколько очков расы обходится; отрицательное — возвращает очки
 * @param excludes    коды особенностей, с которыми эта не встаёт: литовор не берёт
 *                    сельских сторон, богатый родной мир не бывает бедным
 * @param effects     что особенность меняет и насколько
 */
public record RaceTrait(
        String code,
        LocalizedText names,
        LocalizedText descriptions,
        String groupCode,
        Integer picks,
        List<String> excludes,
        Map<RaceEffectType, Integer> effects
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
