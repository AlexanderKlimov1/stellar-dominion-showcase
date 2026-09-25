package com.moo3.server.domain.enums;

import java.util.Arrays;
import java.util.Set;

/**
 * Технологии сканеров — п. 15: как далеко империя замечает чужое присутствие.
 * <p>
 * В MOO2 разведать систему можно только кораблём: пока туда никто не долетел, о её
 * планетах не известно ничего. Сканеры этого не меняют — они видят <b>присутствие</b>:
 * чужие колонии и корабли в системе. Что именно там за планеты, сканер не расскажет.
 * <p>
 * <b>Дальности — из оригинала, в парсеках.</b> MOO2 меряет их так: «Space Scanner
 * замечает корабли за 1 + класс размера (2–7) парсеков», Tachyon — за 3 + класс (4–9),
 * Neutron — за 5 + класс (6–11). Класса размера у нашего обнаружения нет: сканер видит
 * присутствие в системе, а не отдельный корабль, — поэтому взята середина диапазона
 * оригинала, дальность до корабля среднего размера. Sensors оригинал числом не называет;
 * здесь это следующая ступень той же лестницы. Правятся здесь.
 * <p>
 * Коды совпадают с кодами технологий дерева ({@code resources/Technologies/tech.json}):
 * их строит {@code ResearchCatalog} из названия — «Space Scanner» → {@code space-scanner}.
 */
public enum ScannerTech {

    /** Physics, уровень 1 (50 ОИ): 1 + класс размера, 2–7 парсеков в оригинале. */
    SPACE_SCANNER("space-scanner", "Space Scanner", 4),

    /** Physics, уровень 3 (250 ОИ): 3 + класс размера, 4–9 парсеков в оригинале. */
    TACHYON_SCANNER("tachyon-scanner", "Tachyon Scanner", 6),

    /** Physics, уровень 4 (900 ОИ): 5 + класс размера, 6–11 парсеков в оригинале. */
    NEUTRON_SCANNER("neutron-scanner", "Neutron Scanner", 8),

    /** Physics, уровень 10 (6000 ОИ): следующая ступень — треть большой галактики. */
    SENSORS("sensors", "Sensors", 10);

    private final String code;
    private final String name;
    private final int rangeParsecs;

    ScannerTech(String code, String name, int rangeParsecs) {
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

    /** Дальность обнаружения в парсеках от своей колонии или своего флота. */
    public Integer getRangeParsecs() {
        return rangeParsecs;
    }

    /**
     * Дальность сканеров империи: берётся лучший из изученных.
     * <p>
     * Сканеры не складываются — работает самый дальнобойный, как и в MOO2, где приборы
     * корабля заменяют друг друга, а не суммируются. Ноль означает, что сканеров нет
     * вовсе: тогда о чужом присутствии известно только из разведанных систем.
     */
    public static Integer bestRange(Set<String> technologies) {
        return Arrays.stream(values())
                .filter(scanner -> technologies.contains(scanner.getCode()))
                .map(ScannerTech::getRangeParsecs)
                .max(Integer::compareTo)
                .orElse(0);
    }
}
