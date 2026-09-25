package com.moo3.server.dto;

/**
 * Изученная игроком технология — п. 9.
 *
 * @param acquiredTurn ход, на конец которого случился прорыв
 */
public record AcquiredTechnologyDto(
        String categoryCode,
        Integer levelOrder,
        String optionCode,
        String name,
        Integer acquiredTurn
) {
}
