package com.moo3.server.domain.save;

import java.util.List;

/**
 * Проект корабля в слепке партии — п. 8.
 * <p>
 * Владелец назван слотом: при загрузке игроки заводятся заново и получают новые
 * идентификаторы. Корпус и компоненты — коды справочника, а он лежит в файле и от
 * слепка не зависит: партия, загруженная после правки баланса, увидит новые числа
 * у тех же проектов.
 * <p>
 * Вытесненные проекты сохраняются тоже: по ним летают корабли, и без них состав
 * загруженного флота стал бы безымянным.
 *
 * @param components состав проекта: код компонента и сколько раз он взят
 * @param role       боевой корабль или гражданский — п. 8; у слепков, снятых до
 *                   появления расселения, поля нет, и проект считается боевым
 */
public record ShipDesignSnapshot(
        Integer ownerSlot,
        Integer slot,
        String name,
        String hullCode,
        Integer createdTurn,
        Boolean obsolete,
        String role,
        List<ShipDesignComponentSnapshot> components
) {
}
