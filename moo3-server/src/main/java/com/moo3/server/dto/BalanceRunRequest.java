package com.moo3.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Заказ балансового прогона из пульта администратора — этап 2 плана
 * (`balance-metrics-works.txt`).
 *
 * @param empires сколько империй в партии; чем меньше, тем чаще партия доигрывается до
 *                победы, чем больше — тем больше замеров с одной партии
 * @param races   чем играет каждая империя, по записи на место: готовая раса или
 *                случайная законная сборка на заданный бюджет. Короче числа империй —
 *                оставшимся достанутся случайные сборки на полный бюджет
 * @param combination что ПОДСАДИТЬ в сборки нарочно: от одной до трёх сторон. По жребию
 *                на каждую сборку решается, войдёт в неё каждая из них или нет, и все
 *                сочетания получаются поровну. ОДНА сторона — это не связка, а способ
 *                намерить дорогую сторону: изобретательность за десять очков попадает в
 *                случайную сборку двадцать шесть раз из тысячи двухсот, и её цену прибор
 *                не отличает от нуля. Подсаженная попадёт в половину. Пусто — обычный
 *                прогон, связки ищутся среди тех пар, что сложились сами
 * @param seed    зерно первой партии; пусто — берётся от часов. Заданное зерно делает
 *                прогон повторимым целиком
 */
public record BalanceRunRequest(
        String galaxySize,

        @NotNull @Min(2) @Max(8)
        Integer empires,

        @NotNull @Min(1) @Max(2000)
        Integer games,

        @NotNull @Min(10) @Max(1000)
        Integer turns,

        List<RaceSlot> races,

        List<String> combination,

        /**
         * Род прогона: {@code MEASURE} — приговоры ценам, {@code ORACLE} — поиск
         * сильнейших сборок. Пусто — замер.
         */
        String kind,

        /** Сколько сборок в первом поколении поиска; только для оракула. */
        @Min(4) @Max(200)
        Integer population,

        Long seed
) {

    /**
     * Чем играет одна империя прогона.
     *
     * @param raceCode код готовой расы MOO II; пусто — собрать случайную
     * @param budget   бюджет случайной сборки; пусто — полный бюджет конструктора
     */
    public record RaceSlot(String raceCode, Integer budget) {
    }
}
