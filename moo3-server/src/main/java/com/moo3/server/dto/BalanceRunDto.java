package com.moo3.server.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Балансовый прогон для пульта администратора — этап 2 плана.
 *
 * @param traitCosts снимок цен на миг запуска: прогон мерил именно их, а не те, что в
 *                   файле сейчас
 * @param judgements приговоры ценам; пусто — прогон ещё идёт
 * @param combination проверяемая связка: стороны, подсаженные в сборки нарочно. Пусто —
 *                   обычный прогон, связки искались среди сложившихся сами
 * @param pointValue измеренный курс очка: сколько долей выработки приносит одно очко
 * @param residual НЕВЯЗКА ЦЕНЫ — мера успеха круга (п. 3.59 журнала): на сколько очков цена
 *                 расходится с тем, что сторона даёт, медианой по сторонам. Счёт адекватных
 *                 для этого не годится: он растёт от размытости прогона, а не от баланса
 */
public record BalanceRunDto(
        UUID id,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt,
        String status,
        String galaxySize,
        Integer empires,
        Integer games,
        Integer turns,
        Integer played,
        Long seed,
        List<RaceSlotDto> races,
        Map<String, Integer> traitCosts,
        Double pointValue,
        Double residual,
        Integer measured,
        List<JudgementDto> judgements,
        List<SynergyDto> synergies,
        List<String> combination,
        String kind,
        Integer population,
        OracleDto oracle,
        String failure
) {

    /** Чем играла империя: название расы и её стороны — так их видно и без справочника. */
    public record RaceSlotDto(Integer slot, String name, List<String> traits, Integer budget) {
    }

    /**
     * Приговор цене одной стороны.
     *
     * @param strength  измеренная сила по всем мерилам разом, в процентных пунктах
     * @param production из чего она сложилась: доля выработки
     * @param research   она же по науке
     * @param espionage  она же по разведке
     * @param money      она же по деньгам: доход за ход
     * @param military   она же по военной силе: сила флотов
     * @param ground     она же по наземному бою: захваченные колонии
     * @param technology она же по широте науки: число изученных технологий
     * @param fairPrice        цена, которую эта сила заслуживает по измеренному курсу очка
     * @param recommendedPrice куда двигать цену одним шагом; пусто — двигать не надо
     * @param verdict   код приговора, {@code verdictLabel} — он же словами
     */
    public record JudgementDto(
            String code,
            String name,
            Integer price,
            Integer takers,
            Double strength,
            Double error,
            Double production,
            Double research,
            Double espionage,
            Double money,
            Double military,
            Double ground,
            Double technology,
            Double fairPrice,
            Integer recommendedPrice,
            String verdict,
            String verdictLabel
    ) {
    }

    /**
     * Ладдер оракула сборок — этап 3: сильнейшие сборки и ответ на оба условия плана.
     *
     * @param best       сила сильнейшей сборки
     * @param median     сила середины верхушки
     * @param gap        насколько сильнейшая обгоняет середину верхушки
     * @param dominated  есть ли доминирующая сборка
     * @param deadTraits стороны, не вошедшие ни в одну сборку верхушки
     * @param unseenTraits стороны, которых поиск не пробовал вообще: про них сказать нечего
     */
    public record OracleDto(
            List<OracleBuildDto> ladder,
            Double best,
            Double median,
            Double gap,
            Boolean dominated,
            List<String> deadTraits,
            List<String> deadNames,
            List<String> unseenTraits,
            List<String> unseenNames,
            Integer generations
    ) {
    }

    /** Одна сборка ладдера. */
    public record OracleBuildDto(
            List<String> traits,
            List<String> names,
            Integer budget,
            Integer games,
            Double strength,
            Double error
    ) {
    }

    /**
     * Связка двух или трёх сторон: что они дают вместе сверх того, что дают порознь.
     *
     * @param members  коды сторон связки, {@code names} — они же названиями
     * @param pairs    у скольких империй взяты все её стороны сразу
     * @param price    что связка стоит по таблице — сумма цен её сторон
     * @param extra    прибавка сверх всего, что дают её части поодиночке и более мелкими
     *                 связками, в процентных пунктах
     * @param together вся сила связки: её стороны, её внутренние пары и сама прибавка
     * @param verdict  код приговора, {@code verdictLabel} — он же словами
     */
    public record SynergyDto(
            List<String> members,
            List<String> names,
            Integer pairs,
            Integer price,
            Double extra,
            Double error,
            Double together,
            String verdict,
            String verdictLabel
    ) {
    }
}
