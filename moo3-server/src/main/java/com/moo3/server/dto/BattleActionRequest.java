package com.moo3.server.dto;

import com.moo3.server.domain.enums.BattleActionType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Ход корабля в бою — п. 8.
 * <p>
 * Корабль сперва двигается, потом стреляет: движение ход не заканчивает, залп и пропуск —
 * заканчивают. Отступление кончает бой целиком, и поле остаётся за противником.
 *
 * @param shipId       чей ход делается; должен совпадать с кораблём, чья очередь
 * @param x            куда идти — только для MOVE
 * @param y            куда идти — только для MOVE
 * @param targetShipId по кому залп — только для FIRE
 */
public record BattleActionRequest(
        @NotNull UUID shipId,
        @NotNull BattleActionType action,
        Integer x,
        Integer y,
        UUID targetShipId
) {
}
