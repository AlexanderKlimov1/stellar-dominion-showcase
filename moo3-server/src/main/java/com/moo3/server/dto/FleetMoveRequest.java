package com.moo3.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Перелёт флота в другую систему — п. 8.
 * <p>
 * Времени в пути нет: флот оказывается на месте сразу. Это упрощение, а не правило MOO II —
 * скорости, расстояния и топливо встанут сюда же, когда появятся.
 *
 * Лететь может весь флот или его часть: игрок отбирает корабли по проектам, а остальные
 * остаются держать систему.
 *
 * @param targetSystemId куда лететь; система должна быть игроку известна
 * @param ships          что отправляем; пусто — весь флот целиком
 */
public record FleetMoveRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetSystemId,

        @Valid
        List<FleetShipOrder> ships
) {

    /** Отправка без списка кораблей — это отправка всего флота, как было до дробления. */
    public List<FleetShipOrder> shipsOrEmpty() {
        return ships == null ? List.of() : ships;
    }
}
