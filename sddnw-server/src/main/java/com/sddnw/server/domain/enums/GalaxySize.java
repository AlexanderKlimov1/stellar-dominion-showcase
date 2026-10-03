package com.sddnw.server.domain.enums;

/**
 * Размеры галактик — п. 4.2 / 4.2.1, числа MOO II.
 * <p>
 * Меряются <b>парсеками</b>, теми же, что и дальность топливных элементов (п. 8): 4
 * парсека Standard Fuel Cells на карте и есть четыре парсека, переводить нечего. Раньше
 * между картой и топливом стоял множитель ГРЕ→парсек, свой у каждого размера, и от него
 * зависело, дотянется ли империя до соседа; теперь единица одна.
 * <p>
 * Ширина взята из оригинала — 20, 27, 33 и 38 парсеков, — а высота из пропорции карты
 * MOO II 1,4:1 («десять парсеков в высоту на каждые четырнадцать в ширину»). Числа
 * оригинал не публикует, они сняты с игры наблюдением, и это единственное, что о них
 * известно; в остальном они точны настолько, насколько это вообще возможно.
 * <p>
 * Количество звёзд оставлено прежним, своим: плотность галактики — правило этой игры, а
 * не MOO II, и трогать её задача не требовала. Получается близко к оригиналу: на Huge
 * 12,8 квадратного парсека на звезду против 14,5 в MOO II.
 * <p>
 * Количество звёзд не включает особую звезду Wardenhold, она добавляется генератором
 * отдельно.
 */
public enum GalaxySize {

    SMALL("Small", 20, 14, 32),
    MEDIUM("Medium", 27, 19, 48),
    LARGE("Large", 33, 24, 64),
    HUGE("Huge", 38, 27, 80);

    /**
     * Значение по умолчанию в лобби (п. 3): с ним открывается окно новой игры.
     * <p>
     * <b>Малая, а не огромная.</b> Прежде здесь стояла Huge, и первая партия того, кто
     * обучение пропустил, начиналась на восьмидесяти звёздах против семи соперников — не
     * первая партия, а марафон, который новичок не доигрывает и потому не узнаёт, чем она
     * кончилась. Огромная галактика никуда не делась: её выбирают в окне нажатием, а
     * начинать с неё незачем (трек техдолга, пункт 5).
     * <p>
     * Это же умолчание и у API — для запроса, не назвавшего размер. Прогоны называют
     * размер всегда, поэтому на них правка не сказывается.
     */
    public static final GalaxySize DEFAULT = SMALL;

    private final String label;
    private final Integer widthParsecs;
    private final Integer heightParsecs;
    private final Integer starCount;

    GalaxySize(String label, Integer widthParsecs, Integer heightParsecs, Integer starCount) {
        this.label = label;
        this.widthParsecs = widthParsecs;
        this.heightParsecs = heightParsecs;
        this.starCount = starCount;
    }

    public String getLabel() {
        return label;
    }

    public Integer getWidthParsecs() {
        return widthParsecs;
    }

    public Integer getHeightParsecs() {
        return heightParsecs;
    }

    /** Обычные звёзды, без особой. */
    public Integer getStarCount() {
        return starCount;
    }

    /** Обычные звёзды и особая. */
    public Integer getTotalStarCount() {
        return starCount + 1;
    }
}
