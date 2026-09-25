package com.moo3.server.domain.enums;

import java.util.Arrays;
import java.util.Set;

/**
 * Топливные элементы — как далеко от своих колоний уходят корабли империи (п. 8).
 * <p>
 * В MOO II дальность полёта меряется парсеками <b>от ближайшей своей колонии</b>, а не от
 * места, где флот стоит: империя летает внутри облака вокруг своих миров, и расширяют это
 * облако колонии, а не двигатели. Числа взяты из оригинала: Standard Fuel Cells — 4
 * парсека, Deuterium — 5, Iridium — 6, Ytterbium — 8, Thorium — сколько угодно.
 * <p>
 * Топливо не ставится в проект корабля: все корабли империи автоматически укомплектованы
 * лучшим изученным элементом, поэтому дальность — свойство империи, а не отдельного
 * корабля. По той же причине здесь нет ни стоимости, ни занимаемого места.
 * <p>
 * Коды совпадают с кодами технологий дерева ({@code resources/Technologies/tech.json}):
 * их строит {@code ResearchCatalog} из названия — «Deuterium Fuel Cells» →
 * {@code deuterium-fuel-cells}.
 *
 * @see com.moo3.server.service.FlightRules расстояния и время в пути — в тех же парсеках
 */
public enum FuelTech {

    /** Chemistry, уровень 1: есть у всех с первого хода — им укомплектован любой корабль. */
    STANDARD_FUEL_CELLS("standard-fuel-cells", "Standard Fuel Cells", 4),

    /** Chemistry, уровень 2 (250 ОИ). */
    DEUTERIUM_FUEL_CELLS("deuterium-fuel-cells", "Deuterium Fuel Cells", 6),

    /** Chemistry, уровень 4 (900 ОИ). */
    IRIDIUM_FUEL_CELLS("iridium-fuel-cells", "Iridium Fuel Cells", 9),

    /** Chemistry, уровень 6 (3500 ОИ). */
    URIDIUM_FUEL_CELLS("uridium-fuel-cells", "Ytterbium Fuel Cells", 12),

    /**
     * Chemistry, уровень 7 (4500 ОИ): дальность перестаёт что-либо ограничивать.
     * Число повторяет {@link #UNLIMITED_PARSECS} — на константу, объявленную ниже,
     * из списка констант перечисления сослаться нельзя.
     */
    THORIUM_FUEL_CELLS("thorium-fuel-cells", "Thorium Fuel Cells", 1_000);

    /**
     * Неограниченная дальность Thorium Fuel Cells числом: самая большая галактика —
     * 38×27 парсеков, и тысяча парсеков накрывает её с запасом. Отдельным признаком делать не
     * стали — сравнение с числом читается там же, где и все остальные дальности.
     */
    public static final int UNLIMITED_PARSECS = 1_000;

    /**
     * Extended Fuel Tanks (Chemistry, уровень 1) — прибавка к дальности в процентах.
     * <p>
     * В оригинале это компонент корабля, дающий ему +50 % дальности. Компонентов дальности
     * в проекте у нас нет (топливо в проект не ставится), поэтому технология действует на
     * всю империю сразу. Отступление сознательное: иначе изученный бак не давал бы ничего.
     */
    public static final int EXTENDED_TANKS_PERCENT = 50;

    /** Код технологии дополнительных баков в дереве. */
    public static final String EXTENDED_TANKS_CODE = "extended-fuel-tanks";

    private final String code;
    private final String name;
    private final int rangeParsecs;

    FuelTech(String code, String name, int rangeParsecs) {
        this.code = code;
        this.name = name;
        this.rangeParsecs = rangeParsecs;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /** Дальность в парсеках от ближайшей своей колонии. */
    public Integer getRangeParsecs() {
        return rangeParsecs;
    }

    /**
     * Дальность империи в парсеках: лучший изученный элемент плюс дополнительные баки.
     * <p>
     * Элементы не складываются — работает самый дальнобойный: корабль укомплектован одним
     * топливом, а не всеми сразу. Без изученного топлива остаются Standard Fuel Cells: в
     * MOO II они есть у любой империи с первого хода, иначе флот не уходил бы от дома
     * вовсе.
     */
    public static Integer bestRangeParsecs(Set<String> technologies) {
        int best = baseRangeParsecs(technologies);
        if (best >= UNLIMITED_PARSECS) {
            return UNLIMITED_PARSECS;
        }
        return technologies.contains(EXTENDED_TANKS_CODE)
                ? best + best * EXTENDED_TANKS_PERCENT / 100
                : best;
    }

    /**
     * Дальность одного топлива, без дополнительных баков — п. 15.
     * <p>
     * По ней в MOO II завязывается знакомство империй: «контакт возникает, когда одна из
     * сторон может послать корабли <b>без дополнительных баков</b> в системы, занятые
     * другой». Баки — снаряжение отдельного корабля, а не свойство империи, и считать по
     * ним, кто с кем знаком, значило бы знакомить половину галактики за одну технологию.
     */
    public static Integer baseRangeParsecs(Set<String> technologies) {
        return Arrays.stream(values())
                .filter(fuel -> technologies.contains(fuel.getCode()))
                .mapToInt(FuelTech::getRangeParsecs)
                .max()
                .orElse(STANDARD_FUEL_CELLS.rangeParsecs);
    }
}
