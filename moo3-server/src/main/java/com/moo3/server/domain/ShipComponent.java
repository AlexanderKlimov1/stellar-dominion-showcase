package com.moo3.server.domain;

import com.moo3.server.domain.enums.ShipComponentSlot;
import com.moo3.server.domain.enums.ShipEffectType;
import com.moo3.server.domain.enums.WeaponKind;

import java.util.Map;

/**
 * Компонент корабля из справочника — п. 8.
 * <p>
 * Место и цена указаны для фрегата: на крупном корпусе их умножает
 * {@link ShipHull#systemFactor()}, если компонент обслуживает весь корабль. Компонент
 * доступен после своей технологии {@code requiredTechCode}; без неё — с начала партии.
 *
 * @param effects    что компонент даёт кораблю: тип и количество
 * @param weaponKind каким выглядит выстрел — только у оружия, у остального {@code null}
 */
public record ShipComponent(
        String code,
        LocalizedText names,
        LocalizedText descriptions,
        ShipComponentSlot slot,
        Integer space,
        Integer cost,
        String requiredTechCode,
        Map<ShipEffectType, Integer> effects,
        WeaponKind weaponKind,
        /**
         * Обволакивает ли удар цель сам собой — п. 8: «naturally enveloping» оригинала.
         * <p>
         * Такого оружия в MOO II два — плазменная пушка и Копьё новы, — и модификации ENV
         * им не предлагают вовсе: они уже обволакивают. Признак нужен здесь, а не в
         * модификациях, потому что он у самого ствола, и тогда сила его считается одним
         * правилом с модификацией ({@code ShipDesignRules.Item.envelops}).
         */
        Boolean envelops,
        /**
         * Какие модификации носит это оружие — п. 8; {@code null} — все, что подходят его
         * виду. Пустой список значит «никаких»: так стоит звёздный конвертер, который в
         * оригинале не переделывают вовсе.
         */
        java.util.List<String> modifications,
        Integer sortOrder,
        /**
         * Часть КОСМИЧЕСКОГО ЧУДИЩА, а не корабля (п. 11.1): когти, шкура, плавники.
         * <p>
         * Технологии у неё нет вовсе, а значит, в окне дизайна она выглядела бы изученной
         * с первого хода и попадала бы в проекты империи. Прячет её справочник
         * ({@code ShipCatalog.components}), а по коду она достаётся по-прежнему: бою
         * нужно собрать чудище, а окну осмотра — показать, чем оно бьёт.
         */
        Boolean monster
) {

    /** Название на языке читателя — п. 3.5. */
    public String name() {
        return names.text();
    }

    /** Описание на языке читателя — п. 3.5. */
    public String description() {
        return descriptions == null ? null : descriptions.text();
    }

    /** Носит ли это оружие такую модификацию — п. 8. */
    public Boolean carries(WeaponModification modification) {
        if (!Boolean.TRUE.equals(modification.fits(weaponKind))) {
            return false;
        }
        return modifications == null || modifications.contains(modification.code());
    }

    /** Сколько компонент даёт этого эффекта; ноль — не даёт вовсе. */
    public Integer amount(ShipEffectType type) {
        return effects.getOrDefault(type, 0);
    }

    /** Место компонента на этом корпусе — с поправкой на размер, если она нужна. */
    public Integer spaceOn(ShipHull hull) {
        return slot.scalesWithHull() ? space * hull.systemFactor() : space;
    }

    /** Цена компонента на этом корпусе — по тому же правилу, что и место. */
    public Integer costOn(ShipHull hull) {
        return slot.scalesWithHull() ? cost * hull.systemFactor() : cost;
    }
}
