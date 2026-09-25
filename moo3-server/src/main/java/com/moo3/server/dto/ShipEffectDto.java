package com.moo3.server.dto;

import com.moo3.server.domain.enums.ShipEffectType;

/**
 * Что компонент даёт кораблю — п. 8: тип, количество и готовая подпись для экрана.
 * <p>
 * Число отдаётся вместе с подписью потому, что окно дизайна считает характеристики
 * проекта на лету, пока игрок его собирает: сохранённого проекта ещё нет, спросить
 * сервер не о чем. Правила расчёта повторены в {@code shipDesignRules.ts} — они
 * держатся на этих числах.
 */
public record ShipEffectDto(
        ShipEffectType type,
        Integer amount,
        String label
) {
}
