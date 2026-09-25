package com.moo3.server.domain.save;

import com.moo3.server.domain.enums.AiObjective;
import com.moo3.server.domain.enums.AiPersonality;
import com.moo3.server.domain.enums.PlayerType;

import java.util.List;

/**
 * Игрок в слепке партии.
 *
 * @param homeSystemIndex номер родной системы в списке {@link GameSnapshot#systems()}
 * @param researchPoints  очки, вложенные в текущее исследование — п. 9
 * @param technologies    изученные технологии — п. 9
 * @param credits         казна игрока — п. 10
 * @param raceName        название расы, собранной игроком — п. 7; {@code null} — раса
 *                        из справочника или слепок, снятый до конструктора расы
 * @param raceTraits      коды особенностей расы — п. 7
 * @param espionagePoints накопленные очки шпионажа — п. 13; {@code null} в слепках,
 *                        снятых до появления разведки
 * @param spies           сколько у империи шпионов — п. 13
 * @param freighters      сколько у империи грузовых кораблей — п. 4.1.1; {@code null}
 *                        в слепках, снятых до грузового флота
 * @param agents          сами шпионы с их заданиями — п. 13; {@code null} в слепках,
 *                        снятых до появления заданий
 * @param aiPersonality   характер правителя ИИ — п. 15; {@code null} у человека и в
 *                        слепках, снятых до появления характеров
 * @param aiObjective     устремление правителя ИИ — п. 15; {@code null} там же
 * @param history         летопись империи по ходам — п. 11.1, график окна «Инфо»;
 *                        {@code null} в слепках, снятых до летописи
 */
public record PlayerSnapshot(
        Integer slot,
        String name,
        PlayerType playerType,
        String raceCode,
        String color,
        Integer homeSystemIndex,
        String researchCategoryCode,
        Integer researchLevelOrder,
        String researchOptionCode,
        Integer researchPoints,
        List<PlayerTechnologySnapshot> technologies,
        Integer credits,
        String raceName,
        List<String> raceTraits,
        Integer espionagePoints,
        Integer spies,
        Integer freighters,
        List<SpySnapshot> agents,
        AiPersonality aiPersonality,
        AiObjective aiObjective,
        List<EmpireHistorySnapshot> history
) {
}
