package com.moo3.server.dto;

import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Проверяемая связка сторон и что о ней известно — этап 2, п. 2.16 плана.
 *
 * @param traits    коды сторон, {@code names} — они же названиями
 * @param note      зачем эту связку стоит проверить — единственное, чего замер не
 *                  восстановит сам
 * @param verdict   что ответил последний прогон; пусто — связку ещё не проверяли
 * @param extra     прибавка связки сверх её частей, в процентных пунктах
 * @param carriers  у скольких империй последнего прогона взяты все её стороны сразу
 */
public record BalanceCombinationDto(
        UUID id,
        List<String> traits,
        List<String> names,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime checkedAt,
        UUID runId,
        String verdict,
        String verdictLabel,
        Double extra,
        Double error,
        Integer carriers
) {

    /**
     * Заказ на запоминание связки.
     * <p>
     * Связок из полусотни сторон больше двадцати тысяч, и перебирать их незачем: смысл
     * имеют считанные — те, о которых есть догадка. Догадку и записываем.
     */
    public record Request(
            @Size(min = 2, max = 3)
            List<String> traits,
            /**
             * Догадка словами, до пятисот знаков — ровно столько держит колонка
             * {@code balance_combination.note}.
             * <p>
             * Предел назван ЗДЕСЬ, а не только в схеме: длинная записка уходила в базу и
             * возвращалась пятисотым ответом с хибернейтовским стеком, из которого хозяин
             * прогона не узнавал ни причины, ни предела. Отказ должен называть, что не так.
             */
            @Size(max = NOTE_LIMIT)
            String note
    ) {

        /** Сколько знаков держит записка — длина колонки в схеме. */
        public static final int NOTE_LIMIT = 500;
    }
}
