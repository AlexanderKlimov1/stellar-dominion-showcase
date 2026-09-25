package com.moo3.server.dto;

import com.moo3.server.domain.enums.BattleSide;

import java.util.List;
import java.util.UUID;

/**
 * Тактический бой — п. 8: всё, что нужно сцене боя.
 * <p>
 * Ходят корабли, а не игроки: очередь построена по инициативе и идёт сквозь обе стороны.
 * {@code currentShipId} — чей ход сейчас, {@code yourTurn} — ваш ли это корабль.
 *
 * @param width       ширина поля в клетках
 * @param height      высота поля в клетках
 * @param weaponRange дальность залпа в клетках — одна на все стволы
 * @param queue       очередь хода в этом круге: корабли по убыванию инициативы
 * @param events      что случилось с прошлого запроса: ходы ИИ и залпы
 * @param attackerPower сила уцелевших кораблей нападающего — по ней видно, кто сильнее
 * @param defenderPower сила уцелевших кораблей обороняющегося
 */
public record BattleDto(
        UUID id,
        UUID starSystemId,
        String systemName,
        Integer turn,
        Integer round,
        String state,
        String stateLabel,
        Integer width,
        Integer height,
        Integer weaponRange,
        BattleSide yourSide,
        String attackerName,
        String defenderName,
        UUID currentShipId,
        Boolean yourTurn,
        Integer attackerPower,
        Integer defenderPower,
        List<BattleShipDto> ships,
        List<UUID> queue,
        List<BattleEventDto> events,
        String outcome
) {
}
