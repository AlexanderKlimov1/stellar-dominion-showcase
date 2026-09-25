package com.moo3.server.dto;

import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.WeaponKind;

import java.util.List;

/**
 * Компонент корабля в окне дизайна — п. 8.
 * <p>
 * Место и цена даны для фрегата. На крупном корпусе их умножает {@code systemFactor}
 * корпуса, но только если {@code scalesWithHull} — у оружия место от корпуса не зависит.
 *
 * @param weaponKind    вид выстрела: по нему окно дизайна раскладывает оружие лучами,
 *                      снарядами и ракетами, как в списке выбора оружия MOO II.
 *                      Пусто у всего, что не оружие
 * @param effects       что компонент даёт кораблю: тип, количество и подпись
 * @param available     изучена ли технология компонента
 * @param modifications коды модификаций, которые носит именно это оружие — п. 8: набор у
 *                      каждого ствола свой, и окно дизайна предлагает только их
 */
public record ShipComponentDto(
        String code,
        String name,
        String description,
        ShipComponentSlot slot,
        Integer space,
        Integer cost,
        Boolean scalesWithHull,
        WeaponKind weaponKind,
        /** Обволакивает ли удар цель сам собой — п. 8: у плазменной пушки и Копья новы. */
        Boolean envelops,
        String requiredTechCode,
        /**
         * Название нужной технологии на языке читателя — п. 3.5; пусто, если технологии
         * не нужно вовсе.
         * <p>
         * Имя, а не код: окно выбора говорило «нужна технология: electronic-computer», и
         * игрок читал строку из базы вместо названия. Та же огреха, что когда-то была у
         * кражи технологии в отчёте хода.
         */
        String requiredTechName,
        Boolean available,
        List<ShipEffectDto> effects,
        List<String> modifications
) {
}
