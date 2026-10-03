package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.BattleActionType;
import jakarta.validation.constraints.NotNull;

import java.util.List;
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
 * @param weaponRows   какие строки оружия стреляют — номера в списке {@code weapons}
 *                     корабля, как в оригинале, где стволы включают и выключают в
 *                     полосе боя; пусто — стреляет всё
 * @param targetMissileId по какому залпу ракет в полёте стрелять вместо корабля — только для
 *                     FIRE (backlog-promo, пункт 30); номер — {@code BattleMissileDto.id}
 */
public record BattleActionRequest(
        @NotNull UUID shipId,
        @NotNull BattleActionType action,
        Integer x,
        Integer y,
        UUID targetShipId,
        List<Integer> weaponRows,
        Integer targetMissileId
) {
}
