package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.WeaponKind;

import java.util.UUID;

/**
 * Что случилось в бою — п. 8: строка журнала боя и повод для картинки на экране.
 * <p>
 * События копятся за один запрос игрока: пока ходят корабли ИИ, экран не спрашивает
 * сервер по кораблю за раз — он получает их пачкой и проигрывает подряд. Взрыв корабля
 * рисуется по событию {@code DESTROYED}.
 *
 * @param type   MOVE, FIRE, DESTROYED, RETREAT, FINISHED; у ракет ещё LAUNCH (ушла, но не
 *               долетела), MISSILE_MOVE (летит дальше) и MISSILE_LOST (кончилось топливо или
 *               цели больше нет) — backlog-promo, пункт 30
 * @param shipId кто действовал
 * @param targetShipId по кому, если это залп
 * @param damage сколько урона дошло до цели
 * @param weaponKind чем стреляли: луч, снаряд или ракета — сцена рисует их по-разному
 * @param text   готовая строка для журнала боя
 * @param shots  сколько выстрелов ушло этим видом оружия — только у залпа: по нему сцена
 *               выпускает столько ракет, сколько их ушло на самом деле (п. 8)
 * @param fromX  откуда летит выстрел, если не от корабля {@code shipId}: ракета в полёте
 *               долетает с той клетки, где была (backlog-promo, пункт 30); пусто — от корабля
 * @param fromY  то же по высоте
 */
public record BattleEventDto(
        String type,
        UUID shipId,
        UUID targetShipId,
        Integer x,
        Integer y,
        Integer damage,
        WeaponKind weaponKind,
        String text,
        Integer shots,
        Integer fromX,
        Integer fromY
) {

    /** Залп от корабля — выстрел летит с его клетки. */
    public BattleEventDto(String type, UUID shipId, UUID targetShipId, Integer x, Integer y,
                          Integer damage, WeaponKind weaponKind, String text, Integer shots) {
        this(type, shipId, targetShipId, x, y, damage, weaponKind, text, shots, null, null);
    }

    /** Событие без счёта выстрелов — всё, кроме залпа. */
    public BattleEventDto(String type, UUID shipId, UUID targetShipId, Integer x, Integer y,
                          Integer damage, WeaponKind weaponKind, String text) {
        this(type, shipId, targetShipId, x, y, damage, weaponKind, text, null, null, null);
    }
}
