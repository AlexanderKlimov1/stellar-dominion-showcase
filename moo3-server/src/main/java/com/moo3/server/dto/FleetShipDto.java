package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Корабли одного проекта во флоте — п. 8: из чего флот собран.
 * <p>
 * Здесь же всё, что показывает карточка корабля на экране флота: щит, оружие и особые
 * модули. Взять их из списка проектов империи нельзя — вытесненный проект в тот список
 * не попадает, а корабли по нему летают и стоят в том же флоте. Поэтому карточка
 * снабжается отсюда: экран флота отвечает за корабли в строю, а не за то, что империя
 * строит сейчас.
 *
 * @param hullCode корпус проекта — по нему клиент берёт название из справочника
 * @param hullName название корпуса: фрегат, крейсер, звезда смерти
 * @param hullSize размер корпуса 1..6 — им же меряется значок корабля в сетке
 * @param shield   щит корабля; {@code null} — щита нет
 * @param weapons  чем корабль вооружён; пусто — корабль без оружия, вспомогательный
 * @param specials особые модули: боевые отсеки, усиленный корпус, десант
 * @param obsolete проект уже вытеснен из своей ячейки, но корабли по нему в строю
 * @param role     для чего корабль построен — п. 8: боевой, колониальный, застава или
 *                 транспорт. Отличить их по оружию нельзя: без оружия все трое
 *                 гражданских, а высаживают они разное
 * @param cargo    сколько жителей везут эти корабли: поселенцы или десант; у боевых ноль
 */
public record FleetShipDto(
        UUID designId,
        String designName,
        String hullCode,
        String hullName,
        Integer hullSize,
        Integer ships,
        Integer attack,
        Integer defense,
        String shield,
        List<FleetWeaponDto> weapons,
        List<String> specials,
        Boolean obsolete,
        String role,
        Integer cargo
) {
}
