package com.moo3.server.domain.save;

import com.moo3.server.domain.enums.SpyMission;

/**
 * Шпион в слепке партии — п. 13.
 *
 * @param targetSlot слот империи, к которой отправлен агент; {@code null} — работает дома
 */
public record SpySnapshot(
        SpyMission mission,
        Integer points,
        Integer targetSlot,
        Integer createdTurn
) {
}
