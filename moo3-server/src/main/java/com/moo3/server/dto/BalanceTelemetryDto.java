package com.moo3.server.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Телеметрия партии для балансировки — этап 0 плана (`balance-metrics-works.txt`).
 * <p>
 * Одна партия целиком: её условия, империи со своими расами и стартовым положением и
 * летопись каждой империи по ходам. Всё, на чём держится измерение ценности особенностей
 * расы, отдаётся одним запросом — прогону незачем собирать это по крупицам, а анализу
 * незачем ходить в базу.
 * <p>
 * Летопись берётся из {@code empire_history}: она и так пишется каждый ход каждой империи
 * ({@code EmpireHistoryPhase}), и заводить ради балансировки вторую такую таблицу значило
 * бы считать одно и то же дважды.
 *
 * @param seed        зерно партии: по нему партия повторяется целиком
 * @param winnerSlot  место победителя; пусто — партия ещё идёт или кончилась без победы
 */
public record BalanceTelemetryDto(
        UUID gameId,
        String name,
        Long seed,
        String galaxySize,
        Integer starCount,
        Integer turn,
        String status,
        Integer winnerSlot,
        String victoryKind,
        List<EmpireTelemetryDto> empires,
        /**
         * Всё, что за партию построено, с ходом постройки — п. 6 плана.
         * <p>
         * <b>Зачем в телеметрии.</b> Этим проверяется ХОЛОСТОЙ ХОД: вещь, которая стоила
         * производства, обязана за следующие двадцать ходов дать владельцу прирост сверх
         * фона — населения, флота, колоний, казны или технологий. Раньше проверка читала
         * состояние партии прямо из Postgres, и с переездом прогонов в память (H2) ослепла
         * целиком: колоний ноль, построек ноль, а отчёт бодро печатал «холостых механик не
         * найдено» (журнал, п. 3.93). Теперь она спрашивает то же самое через API — и видит
         * партию на любом движке, потому что спрашивает игру, а не базу.
         * <p>
         * Ход постройки уже хранится у самой постройки, поэтому опрашивать партию по ходам
         * не нужно вовсе: одного запроса в конце хватает на весь разбор.
         */
        List<BuiltDto> built
) {

    /**
     * Построенное: чьё, где, что и когда.
     *
     * @param ownerSlot место ВЛАДЕЛЬЦА КОЛОНИИ на момент запроса. У захваченной колонии
     *                  строил здание прежний хозяин, и привязка тогда врёт — но переходы
     *                  колоний считаные за партию, а разбор смотрит на прирост империи, а
     *                  не на саму постройку.
     */
    public record BuiltDto(Integer ownerSlot, String planet, String code, Integer builtTurn) {
    }

    /**
     * Империя партии: чем играла и как стояла.
     *
     * @param traits        коды особенностей расы — то, ценность чего и меряется
     * @param homeSize      размер родного мира, {@code homeClimate} — его климат,
     *                      {@code homeMinerals} — богатство недр
     * @param nearbyPlanets сколько пригодных для колонизации планет в {@code NEARBY_PARSECS}
     *                      парсеках от родной звезды: главная ковариата стартового угла
     * @param nearestRival  расстояние до ближайшей чужой родной звезды в парсеках
     * @param used          чем империя за партию пользовалась: сколько раз высаживала
     *                      десант, брала колонии, крала технологии, слала флоты, воевала.
     *                      Без этого нельзя отличить «сторона расы слаба» от «механика ни
     *                      разу не сработала» — п. 6 плана
     * @param history       летопись по ходам
     */
    public record EmpireTelemetryDto(
            UUID playerId,
            Integer slot,
            String name,
            String raceCode,
            String raceName,
            List<String> traits,
            String government,
            String personality,
            String objective,
            Boolean ai,
            String homeSize,
            String homeClimate,
            String homeMinerals,
            Integer nearbyPlanets,
            Double nearestRival,
            Map<String, Integer> used,
            List<TurnRowDto> history) {
    }

    /**
     * Один ход одной империи — та же строка, что рисует график окна «Инфо».
     *
     * @param might сводная мощь империи ({@code EmpireMightRules}): суррогат исхода партии,
     *              которым измеряется сила расы до того, как партия кончится
     */
    public record TurnRowDto(
            Integer turn,
            Integer populationK,
            Integer colonies,
            Integer buildings,
            Integer production,
            Integer research,
            Integer fleetPower,
            Integer technologies,
            Integer credits,
            /** Доход за ход — поток, в отличие от казны: мерило денег в балансировке. */
            Integer income,
            Integer might) {
    }
}
