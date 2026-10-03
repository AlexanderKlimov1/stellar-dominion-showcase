package com.sddnw.server.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Часы хода в партии людей — backlog-promo, пункт 11.
 * <p>
 * Ход считается, когда закончили все люди, и один медленный игрок держит остальных, а
 * проигрывающий обычно уходит — и партия умирает для всех. Правил три, и все три — решение
 * хозяина проекта (02.10.2026):
 * <ul>
 *   <li><b>срок хода</b> — настройка партии ({@link #ALLOWED_SECONDS}, по умолчанию без
 *       срока). Истёк — ход считается сам: опоздавший пропускает свои решения этого хода,
 *       колонии строят по очереди, наука идёт к выбранной цели;</li>
 *   <li><b>ушедший</b> — тот, кто нажал «покинуть партию», пропустил {@link #MISSED_LIMIT}
 *       срока подряд или {@link #IDLE} не сделал ни одного настоящего действия. Его империю
 *       ведёт ИИ, пока он не вернётся: любое его действие возвращает ему место;</li>
 *   <li><b>всё это — только когда кто-то ждёт</b>: хотя бы один присутствующий человек уже
 *       закончил ход. Без этой оговорки одиночная партия против ИИ шла бы сама, пока
 *       игрок отошёл от стола, а ждать в ней некому — и торопить некого.</li>
 * </ul>
 */
public final class TurnClockRules {

    /** Сроки хода, которые предлагает окно новой игры: 1,5, 2, 3, 5 и 10 минут. */
    public static final List<Integer> ALLOWED_SECONDS = List.of(90, 120, 180, 300, 600);

    /** Столько без единого настоящего действия — и игрок считается ушедшим. */
    public static final Duration IDLE = Duration.ofMinutes(3);

    /** Столько сроков подряд без конца хода — и игрок считается ушедшим. */
    public static final Integer MISSED_LIMIT = 3;

    private TurnClockRules() {
    }

    /** Годится ли срок хода для партии: пусто — без срока. */
    public static Boolean allowed(Integer seconds) {
        return seconds == null || ALLOWED_SECONDS.contains(seconds);
    }

    /** Когда истекает ход; пусто — у партии нет срока или ход ещё не начинался. */
    public static OffsetDateTime deadline(OffsetDateTime turnStartedAt, Integer seconds) {
        if (turnStartedAt == null || seconds == null) {
            return null;
        }
        return turnStartedAt.plusSeconds(seconds);
    }

    /** Истёк ли срок хода к этому мгновению. */
    public static Boolean expired(OffsetDateTime turnStartedAt, Integer seconds, OffsetDateTime now) {
        OffsetDateTime deadline = deadline(turnStartedAt, seconds);
        return deadline != null && !now.isBefore(deadline);
    }

    /** Ушёл ли игрок по безделью: последнее настоящее действие — не позже чем {@link #IDLE} назад. */
    public static Boolean idle(OffsetDateTime lastAction, OffsetDateTime now) {
        return !lastAction.plus(IDLE).isAfter(now);
    }
}
