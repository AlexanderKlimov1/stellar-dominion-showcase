package com.sddnw.server.domain.enums;

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
 * <b>А вот дополнительные баки — модуль корабля</b>, как и в MOO II
 * ({@code ShipEffectType.FUEL_RANGE_PERCENT}, справочник компонентов): они занимают место,
 * стоят денег и достаются тому кораблю, которому их поставили. Здесь их больше нет: до
 * 29.09.2026 изученные баки давали +50 % дальности ВСЕЙ империи разом — так было проще, но
 * тогда игрок, изучив их, не находил их в окне дизайна и не мог поставить, а выбора
 * «место под баки или под пушку» не было вовсе.
 * <p>
 * Коды совпадают с кодами технологий дерева ({@code resources/Technologies/tech.json}):
 * их строит {@code ResearchCatalog} из названия — «Deuterium Fuel Cells» →
 * {@code deuterium-fuel-cells}.
 *
 * @see com.sddnw.server.service.FlightRules расстояния и время в пути — в тех же парсеках
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
     * Код технологии дополнительных баков в дереве — он же код самого модуля в справочнике
     * компонентов: технология открывает ровно одну вещь, и разводить им коды незачем.
     * Прибавка к дальности лежит там же, в справочнике, действием
     * {@code FUEL_RANGE_PERCENT} — числа компонентов правятся данными, а не кодом.
     */
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
     * Дальность империи в парсеках: лучший изученный топливный элемент, без баков.
     * <p>
     * Элементы не складываются — работает самый дальнобойный: корабль укомплектован одним
     * топливом, а не всеми сразу. Без изученного топлива остаются Standard Fuel Cells: в
     * MOO II они есть у любой империи с первого хода, иначе флот не уходил бы от дома
     * вовсе.
     * <p>
     * По этой же дальности в MOO II завязывается знакомство империй: «контакт возникает,
     * когда одна из сторон может послать корабли <b>без дополнительных баков</b> в
     * системы, занятые другой», — а баки с 29.09.2026 и есть снаряжение отдельного
     * корабля, так что второго счёта для знакомства больше не нужно.
     */
    public static Integer baseRangeParsecs(Set<String> technologies) {
        return Arrays.stream(values())
                .filter(fuel -> technologies.contains(fuel.getCode()))
                .mapToInt(FuelTech::getRangeParsecs)
                .max()
                .orElse(STANDARD_FUEL_CELLS.rangeParsecs);
    }
}
