package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Голос одной империи на выборах — п. 3.
 *
 * @param weight         голосов у империи: столько же, сколько населения
 * @param choicePlayerId за кого отдан голос; {@code null} — воздержалась
 * @param candidate      эта империя — кандидат
 * @param yours          это империя того, кто смотрит сцену
 * @param pending        голос ещё не объявлен: совет открыт, и чужих голосов до итога не
 *                       показывают, а свой — пока игрок не ответил
 */
public record CouncilVoterDto(
        UUID playerId,
        String name,
        String color,
        Integer weight,
        UUID choicePlayerId,
        Boolean candidate,
        Boolean yours,
        Boolean pending
) {
}
