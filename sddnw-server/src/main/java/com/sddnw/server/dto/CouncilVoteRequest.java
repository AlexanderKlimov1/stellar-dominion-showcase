package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Голос игрока в Высшем совете — п. 3.
 *
 * @param choicePlayerId за кого голосует игрок; {@code null} — воздерживается. Голосовать
 *                       можно только за одного из двух кандидатов.
 */
public record CouncilVoteRequest(UUID choicePlayerId) {
}
