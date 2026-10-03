package com.sddnw.server.service;

import com.sddnw.server.domain.enums.FuelTech;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Числовые правила перелёта флота — п. 8: дальность, время в пути и право менять курс.
 * <p>
 * Всё, что в MOO II меряется парсеками, живёт здесь: карта этой игры размечена в ГРЕ, и
 * переводом занимается одно место, а не каждый вызывающий. Правила три:
 * <ol>
 *   <li><b>Дальность.</b> Лететь можно не дальше, чем позволяют топливные элементы, и
 *       меряется дальность от ближайшей своей колонии — {@link FuelTech}. Топливо даёт
 *       империя всем кораблям сразу, а дополнительные баки — модуль КОРАБЛЯ, и его
 *       прибавка приходит сюда процентом (п. 8).</li>
 *   <li><b>Время в пути.</b> Расстояние, делённое на скорость самого медленного корабля
 *       флота: скорость двигателей в справочнике задана в парсеках за ход.</li>
 *   <li><b>Смена курса.</b> Улетевший флот приказов не слышит, пока империя не изучит
 *       Hyperspace Communications, — в оригинале это её единственное назначение,
 *       не считая командных очков.</li>
 * </ol>
 */
@Service
public class FlightRules {

    /**
     * Технология, позволяющая перенаправить флот в полёте — Physics, уровень
     * Hyper-Dimensional Physics (6000 ОИ). До неё отданный приказ отменить нельзя: флот
     * уходит в подпространство и связи с ним нет.
     */
    public static final String REDIRECT_TECH = "hyperspace-communications";

    /**
     * Дальность империи в парсеках: лучшее изученное топливо, без баков.
     * <p>
     * Карта меряется теми же парсеками (п. 4.2), поэтому переводить дальность больше не
     * во что: четыре парсека Standard Fuel Cells — это четыре парсека на карте.
     */
    public Integer rangeParsecs(Set<String> technologies) {
        return FuelTech.baseRangeParsecs(technologies);
    }

    /**
     * Дальность корабля или флота — п. 8: топливо империи плюс проценты его собственных
     * баков ({@code ShipEffectType.FUEL_RANGE_PERCENT}).
     * <p>
     * У флота процент берётся по САМОМУ КОРОТКОМУ кораблю — тем же правилом, что и
     * скорость по самому медленному: строй летит вместе, и корабль без баков держит при
     * себе весь флот.
     * <p>
     * Неограниченную дальность баки не трогают: {@link FuelTech#UNLIMITED_PARSECS} и так
     * накрывает самую большую галактику, а полтора таких числа не значили бы ничего.
     */
    public Integer rangeParsecs(Set<String> technologies, Integer rangePercent) {
        int base = FuelTech.baseRangeParsecs(technologies);
        if (base >= FuelTech.UNLIMITED_PARSECS) {
            return FuelTech.UNLIMITED_PARSECS;
        }
        return base + base * Math.max(0, rangePercent) / 100;
    }

    /** Расстояние между точками карты в парсеках. */
    public Double distanceParsecs(Double fromX, Double fromY, Double toX, Double toY) {
        return Math.hypot(toX - fromX, toY - fromY);
    }

    /**
     * Сколько ходов флот будет в пути.
     * <p>
     * Скорость флота — парсеки за ход по самому медленному кораблю, расстояние тоже в
     * парсеках: делить одно на другое больше нечем. Меньше одного хода перелёт не бывает:
     * ход — наименьшая единица времени в игре, и мгновенных перелётов в MOO II нет.
     */
    public Integer travelTurns(Double distanceParsecs, Integer speedParsecs) {
        int speed = Math.max(1, speedParsecs);
        return Math.max(1, (int) Math.ceil(distanceParsecs / speed));
    }

    /** Империя умеет разговаривать с кораблями в полёте — только тогда курс можно сменить. */
    public Boolean canRedirect(Set<String> technologies) {
        return technologies.contains(REDIRECT_TECH);
    }
}
