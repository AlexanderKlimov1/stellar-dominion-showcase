package com.sddnw.server.domain;

import com.sddnw.server.domain.enums.BattleSide;
import com.sddnw.server.service.BattleRules;

import java.util.List;
import java.util.UUID;

/**
 * Ракетный залп в полёте — п. 8, backlog-promo, пункт 30.
 * <p>
 * В MOO II ракета — предмет на поле: она летит к цели своей скоростью, и топлива у неё на
 * два круга. Залп, не долетевший в круг пуска, остаётся на поле этой записью и
 * продолжает путь на смене круга ({@code TacticalBattleService.flyMissiles}).
 * <p>
 * Залп несёт с собой всё, что решает исход попадания, — выстрелы, меткость стрелявшего и
 * надбавку его офицеров к урону <b>на миг пуска</b>: ракета, уже ушедшая со стапеля, не
 * знает, что стрелявшему потом сожгли компьютер или что сам он погиб.
 *
 * @param id            номер залпа в бою — по нему сцена узнаёт ракету от хода к ходу
 * @param side          чья ракета
 * @param shooterShipId кто пустил (корабль может уже погибнуть — ракета летит дальше)
 * @param targetShipId  в кого
 * @param fuel          сколько кругов полёта ещё осталось
 * @param attackRating  меткость стрелявшего в миг пуска
 * @param damagePercent надбавка офицеров стрелявшего к урону
 */
public record BattleMissile(Integer id,
                            BattleSide side,
                            UUID shooterShipId,
                            UUID targetShipId,
                            Integer x,
                            Integer y,
                            Integer speed,
                            Integer fuel,
                            Integer attackRating,
                            Integer damagePercent,
                            List<BattleRules.Shot> shots) {

    /**
     * Тот же залп в другой клетке, с топливом на круг меньше и без ракет, сбитых по пути
     * (пункт 30): {@code left} — что от него осталось.
     */
    public BattleMissile flown(int toX, int toY, List<BattleRules.Shot> left) {
        return new BattleMissile(id, side, shooterShipId, targetShipId, toX, toY, speed,
                fuel - 1, attackRating, damagePercent, List.copyOf(left));
    }
}
