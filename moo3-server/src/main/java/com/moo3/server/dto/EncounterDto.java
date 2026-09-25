package com.moo3.server.dto;

import java.util.UUID;

/**
 * Встреча флотов в системе — п. 8, со стороны одного игрока.
 * <p>
 * Что видно игроку: где встретились, чей флот напротив, чья очередь решать и чем всё
 * кончилось, если уже кончилось.
 *
 * @param yourTurn          решение сейчас за этим игроком
 * @param firstMover        этот игрок ходит первым: его флот быстрее
 * @param yourShips         кораблей у него
 * @param opponentShips     кораблей у соперника
 * @param yourInitiative    инициатива его флота
 * @param opponentInitiative инициатива флота соперника
 * @param battleId          тактический бой, если игрок выбрал ручной, — п. 8
 * @param outcome           исход боя; пусто, пока встреча не закрыта
 */
public record EncounterDto(
        UUID id,
        UUID starSystemId,
        String systemName,
        Integer turn,
        String state,
        String stateLabel,
        Boolean yourTurn,
        Boolean firstMover,
        UUID opponentPlayerId,
        String opponentName,
        Integer yourShips,
        Integer opponentShips,
        Integer yourInitiative,
        Integer opponentInitiative,
        UUID battleId,
        String outcome
) {
}
