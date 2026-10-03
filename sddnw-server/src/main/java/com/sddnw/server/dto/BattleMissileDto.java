package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.BattleSide;

import java.util.UUID;

/**
 * Ракетный залп в полёте на поле боя — backlog-promo, пункт 30.
 *
 * @param id     номер залпа в бою: по нему сцена узнаёт ракету от хода к ходу
 * @param count  сколько ракет в залпе
 * @param speed  клеток за круг
 * @param fuel   сколько кругов полёта ещё осталось
 */
public record BattleMissileDto(
        Integer id,
        BattleSide side,
        UUID shooterShipId,
        UUID targetShipId,
        Integer x,
        Integer y,
        Integer count,
        Integer speed,
        Integer fuel
) {
}
