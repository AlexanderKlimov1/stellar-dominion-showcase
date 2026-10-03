package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerEntity;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Итог по могуществу — п. 3, backlog-promo, пункт 2 (решение хозяина проекта).
 * <p>
 * <b>Зачем.</b> Главная болезнь жанра — затянутый финал: исход ясен за сотню ходов до
 * конца, а игроку осталось управлять тридцатью колониями, и партию бросают недоигранной.
 * Здесь у партии есть срок: не кончилась ничем к {@link #TURN_LIMIT}-му ходу — победа
 * отдаётся сильнейшей империи. Срок виден заранее, поэтому он не обрыв, а цель: отстающий
 * знает, сколько ходов у него на рывок, а ведущий — сколько ему держаться.
 * <p>
 * <b>Мерило — первая линия графика «Инфо»</b> ({@link EmpireMightRules}, тот же замер, что
 * пишет летопись): игрок весь срок видит на экране ровно то, по чему его рассудят, а
 * соседи-ИИ судят друг друга по той же формуле. Второй меры силы в игре быть не должно.
 * <p>
 * Судят только ЖИВЫЕ империи — с колониями: флот без колоний империей не считается и в
 * покорении, иначе разбитая империя с уцелевшей эскадрой могла бы выиграть по очкам.
 * Равенство мощи разрешает место игрока: партия обязана повторяться (см. {@code GameOrder}).
 */
public final class MightVictoryRules {

    /** На каком ходу подводится итог; решение хозяина проекта. */
    public static final int TURN_LIMIT = 300;

    /** За сколько ходов до срока напоминать галактике: за полсотни, за десять и за один. */
    private static final Set<Integer> REMIND_BEFORE = Set.of(50, 10, 1);

    private MightVictoryRules() {
    }

    /** Подводится ли итог на этом ходу. */
    public static Boolean due(Integer turn) {
        return turn >= TURN_LIMIT;
    }

    /** Напоминать ли о сроке на этом ходу; число — сколько ходов осталось. */
    public static Boolean remind(Integer turn) {
        return REMIND_BEFORE.contains(TURN_LIMIT - turn);
    }

    /**
     * Сильнейшая из живых империй; пусто — живых нет.
     *
     * @param might мощь по игрокам — первая линия графика «Инфо»
     * @param alive игроки, у которых есть колонии
     */
    public static PlayerEntity winner(Map<UUID, Integer> might, Collection<PlayerEntity> players,
                                      Set<UUID> alive) {
        return players.stream()
                .filter(player -> alive.contains(player.getId()))
                .max(Comparator.comparingInt((PlayerEntity player) -> might.getOrDefault(player.getId(), 0))
                        // При равной мощи побеждает меньшее место: место сравнивается
                        // обратным порядком, и меньшее оказывается «большим» для max.
                        .thenComparing(PlayerEntity::getSlot, Comparator.reverseOrder()))
                .orElse(null);
    }
}
