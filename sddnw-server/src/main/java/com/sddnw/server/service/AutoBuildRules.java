package com.sddnw.server.service;

import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.ColonyProject;
import com.sddnw.server.domain.PopulationJobs;
import com.sddnw.server.dto.ColonyProjectDto;

import java.util.Collection;
import java.util.Map;

/**
 * Что колония возьмёт строить сама — кнопка {@code AUTO BUILD} оригинала, п. 10.
 * <p>
 * Правило одно на игрока и на ИИ, и живёт оно здесь по той же причине, по которой в этом
 * проекте вообще не заводят вторых сводов правил: империя ИИ выбирает постройку по
 * ОКУПАЕМОСТИ ({@link AiEmpireService#paybackTurns}), и колония человека с включённым
 * автостроем обязана выбирать так же. Разойдись эти два выбора — и «автострой» значил бы
 * одно у соседа и другое у игрока.
 * <p>
 * <b>Берутся только здания.</b> Корабли, колониальные базы и шпионы автострой не трогает:
 * это решения о флоте, расселении и разведке — о том, чего колония сама за империю решать
 * не может. Автострой отвечает на вопрос «чем занять освободившуюся колонию», а не «какой
 * у империи план».
 * <p>
 * Класс без состояния и без базы: это числовое правило, и проверяется оно юнит-тестом.
 */
public final class AutoBuildRules {

    private AutoBuildRules() {
    }

    /**
     * Самое быстроокупаемое здание из доступного колонии.
     *
     * @param horizon за сколько ходов здание должно окупиться, чтобы его стоило брать;
     *                {@code null} — горизонта нет, берётся лучшее из того, что окупается
     *                вообще
     * @return код здания или {@code null}, если брать нечего
     */
    public static String worthwhile(Map<String, Building> catalog,
                                    PopulationJobs jobs,
                                    Collection<ColonyProjectDto> available,
                                    Integer horizon) {
        String best = null;
        Integer bestTurns = null;
        Integer bestCost = null;
        for (ColonyProjectDto project : available) {
            Building building = catalog.get(project.code());
            Integer turns = AiEmpireService.paybackTurns(building, jobs);
            if (turns == null || (horizon != null && turns > horizon)) {
                continue;
            }
            // При равной окупаемости берётся ДЕШЁВОЕ: оно освободит стапель раньше, а
            // колония тем временем вырастет и оценит следующее здание уже по-новому.
            // Ничья решается кодом здания — партия обязана повторяться до последнего числа.
            if (bestTurns == null || turns < bestTurns
                    || (turns.equals(bestTurns) && building.cost() < bestCost)
                    || (turns.equals(bestTurns) && building.cost().equals(bestCost)
                            && building.code().compareTo(best) < 0)) {
                best = building.code();
                bestTurns = turns;
                bestCost = building.cost();
            }
        }
        return best;
    }

    /**
     * Что взять колонии с автостроем, когда очередь опустела, — п. 10.
     * <p>
     * Сперва самое окупаемое БЕЗ горизонта: автострой выбирает не «стоит ли вообще
     * развиваться», а «чем заняться сейчас», и отказываться от единственного доступного
     * здания ему незачем. Не окупается ничто — берётся самое дешёвое здание: колония
     * растёт, и то, что сегодня не окупается, завтра окупится, а простаивать она не должна.
     * Нет и зданий — {@code null}, и тогда фаза производства ставит товары, как и всегда.
     */
    public static String next(Map<String, Building> catalog,
                              PopulationJobs jobs,
                              Collection<ColonyProjectDto> available) {
        Collection<ColonyProjectDto> buildings = available.stream()
                .filter(project -> catalog.containsKey(project.code()))
                .filter(project -> !Boolean.TRUE.equals(ColonyProject.endless(project.code())))
                .toList();
        String payback = worthwhile(catalog, jobs, buildings, null);
        if (payback != null) {
            return payback;
        }
        return buildings.stream()
                .filter(project -> project.cost() != null)
                // Ничья решается кодом: партия обязана повторяться до последнего числа.
                .min(java.util.Comparator.comparing(ColonyProjectDto::cost)
                        .thenComparing(ColonyProjectDto::code))
                .map(ColonyProjectDto::code)
                .orElse(null);
    }
}
