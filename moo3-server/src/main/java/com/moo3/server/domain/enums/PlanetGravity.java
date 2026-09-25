package com.moo3.server.domain.enums;

/**
 * Тяжесть планеты — п. 4.1, п. 7.
 * <p>
 * В MOO II у каждого мира своя тяжесть, и раса, к ней не привыкшая, работает на нём
 * хуже: обычная теряет четверть выработки на лёгком мире и половину на тяжёлом. Раса
 * малой тяжести чувствует себя дома на лёгких мирах, но платит за это на обычных; раса
 * большой тяжести одинаково хорошо работает и на тяжёлых, и на обычных, а на лёгких
 * теряет половину. Числа — из описания сторон расы (п. 7).
 * <p>
 * <b>Реконструкция — откуда тяжесть берётся.</b> Таблицы «размер → тяжесть» оригинал не
 * публикует, но связь очевидна и видна в самой игре: крошечные и малые миры лёгкие,
 * средние и большие обычные, огромные тяжёлые. Поэтому тяжесть здесь не хранится, а
 * считается от размера ({@link #of}) — тогда ей неоткуда разъехаться с размером, и
 * терраформирование, меняющее климат, тяжесть не трогает, как и в оригинале.
 */
public enum PlanetGravity {

    LOW("Малая тяжесть"),
    NORMAL("Обычная тяжесть"),
    HIGH("Большая тяжесть");

    /** Сколько выработки теряет непривычная раса на лёгком мире — п. 7. */
    private static final int LOW_PENALTY_PERCENT = -25;

    /** Сколько её теряется на тяжёлом мире — п. 7. */
    private static final int HIGH_PENALTY_PERCENT = -50;

    private final String label;

    PlanetGravity(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Тяжесть мира по его размеру — см. реконструкцию в описании класса. */
    public static PlanetGravity of(PlanetSize size) {
        return switch (size) {
            case TINY, SMALL -> LOW;
            case MEDIUM, LARGE -> NORMAL;
            case HUGE -> HIGH;
        };
    }

    /**
     * Тяжесть мира с оглядкой на то, родной ли он, — п. 4.1, п. 7.
     * <p>
     * <b>Родной мир расе всегда по силам</b>: она на нём выросла, и штрафа за тяжесть на
     * нём нет ни у кого — в MOO II раса начинает партию в полную силу, а «большой родной
     * мир» за очко расы делает мир просторнее, а не хуже. Без этой оговорки сторона
     * «большой родной мир» (1 очко) оборачивалась бы половиной производства на старте.
     */
    public static PlanetGravity of(PlanetSize size, Boolean homeworld) {
        return Boolean.TRUE.equals(homeworld) ? NORMAL : of(size);
    }

    /**
     * Сколько процентов производства теряет на этом мире раса с такими привычками — п. 7.
     *
     * @param lowGravityRace  раса малой тяжести: лёгкие миры ей родные, обычные — уже нет
     * @param highGravityRace раса большой тяжести: тяжёлые и обычные миры ей нипочём
     */
    public Integer productionPercent(Boolean lowGravityRace, Boolean highGravityRace) {
        boolean low = Boolean.TRUE.equals(lowGravityRace);
        boolean high = Boolean.TRUE.equals(highGravityRace);
        return switch (this) {
            case LOW -> low ? 0 : (high ? HIGH_PENALTY_PERCENT : LOW_PENALTY_PERCENT);
            case NORMAL -> low ? LOW_PENALTY_PERCENT : 0;
            case HIGH -> high ? 0 : HIGH_PENALTY_PERCENT;
        };
    }
}
