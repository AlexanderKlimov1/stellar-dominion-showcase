package com.moo3.server.dto;

import com.moo3.server.domain.enums.WeaponKind;

import java.util.UUID;

/**
 * Что случилось в бою — п. 8: строка журнала боя и повод для картинки на экране.
 * <p>
 * События копятся за один запрос игрока: пока ходят корабли ИИ, экран не спрашивает
 * сервер по кораблю за раз — он получает их пачкой и проигрывает подряд. Взрыв корабля
 * рисуется по событию {@code DESTROYED}.
 *
 * @param type   MOVE, FIRE, DESTROYED, RETREAT, FINISHED
 * @param shipId кто действовал
 * @param targetShipId по кому, если это залп
 * @param damage сколько урона дошло до цели
 * @param weaponKind чем стреляли: луч, снаряд или ракета — сцена рисует их по-разному
 * @param text   готовая строка для журнала боя
 */
public record BattleEventDto(
        String type,
        UUID shipId,
        UUID targetShipId,
        Integer x,
        Integer y,
        Integer damage,
        WeaponKind weaponKind,
        String text
) {
}
