package com.sddnw.server.service;

/**
 * Когда игрок замечает летящий к нему вражеский флот — backlog-promo, пункт 9.
 * <p>
 * Летящий флот у нас не видит никто, даже сканеры (п. 8, {@code ExplorationService}): он не
 * стоит ни в одной системе. Предупреждение поэтому не имеет права знать больше, чем знали
 * бы глаза игрока, — иначе это была бы разведка сильнее всякого сканера. Правил два:
 * <ul>
 *   <li><b>на подлёте флот виден всегда</b>: в последний ход пути он уже у самой системы, и
 *       колония видит его своими глазами — сканера для этого не нужно;</li>
 *   <li><b>раньше — только в пределах сканера</b>: положение флота в пути считается по
 *       прямой между системой вылета и целью пропорционально прошедшим ходам, и если до
 *       него от любой своей колонии или стоящего флота не дальше дальности сканера — его
 *       заметили.</li>
 * </ul>
 * В MOO II движущиеся флоты видны на карте в пределах сканеров — отсюда и второе правило.
 * Первое — реконструкция: без него империя без сканеров (а их на старте нет) узнавала бы о
 * нападении только из итогов боя, то есть ровно тогда, когда предупреждать поздно.
 * <p>
 * <b>Предупреждают ОДИН раз</b> — на ходу, когда флот заметили впервые: повтор каждый ход
 * при долгом перелёте заслонял бы итоги и приучал бы их не читать. «Заметили впервые»
 * считается без памяти: заметен сейчас и не был заметен ходом раньше.
 */
public final class ThreatRules {

    private ThreatRules() {
    }

    /**
     * Сколько ходов у игрока на ответ: флот садится в конце хода {@code arrivalTurn - 1}
     * (фаза прибытия сажает флоты с {@code arrivalTurn <= turn + 1}).
     */
    public static Integer turnsLeft(Integer arrivalTurn, Integer turn) {
        return arrivalTurn - 1 - turn;
    }

    /**
     * Доля пройденного пути к концу хода {@code turn}: от 0 у системы вылета до 1 у цели.
     */
    public static Double progress(Integer departureTurn, Integer arrivalTurn, Integer turn) {
        int total = Math.max(1, arrivalTurn - departureTurn);
        double elapsed = turn + 1 - departureTurn;
        return Math.max(0.0, Math.min(1.0, elapsed / total));
    }

    /**
     * Заметен ли флот к концу хода {@code turn}.
     *
     * @param nearestPost расстояние от флота до ближайшей своей колонии или стоящего флота,
     *                    в парсеках
     * @param scanRange   дальность сканеров в парсеках; 0 — сканеров нет
     */
    public static Boolean noticed(Integer arrivalTurn, Integer turn, Double nearestPost, Integer scanRange) {
        if (turnsLeft(arrivalTurn, turn) <= 1) {
            return Boolean.TRUE;
        }
        return scanRange > 0 && nearestPost <= scanRange;
    }
}
