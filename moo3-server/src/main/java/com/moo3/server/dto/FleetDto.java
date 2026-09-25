package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Флот империи — п. 8.
 *
 * @param attackPercent  расовая прибавка к атаке кораблей
 * @param defensePercent расовая прибавка к защите кораблей
 * @param designs что империя может строить
 * @param fleets  построенные флоты по системам и в пути — п. 8
 * @param rangeParsecs дальность полёта в парсеках от ближайшей своей колонии
 * @param reachableSystemIds куда флоты долетают: по этому списку карта и рисует линию
 *                           к выбранной звезде — зелёную или красную
 * @param canRedirect империя умеет менять курс флота в полёте (Hyperspace Communications)
 */
public record FleetDto(
        Integer attackPercent,
        Integer defensePercent,
        List<ShipDesignDto> designs,
        List<FleetGroupDto> fleets,
        Integer rangeParsecs,
        List<UUID> reachableSystemIds,
        Boolean canRedirect,
        /**
         * Раса телепатов — п. 7: крупный корабль подчиняет чужую колонию без десанта,
         * и экран флота показывает эту кнопку только им.
         */
        Boolean telepathic,
        /** Сколько командных очков занимают корабли империи — п. 8. */
        Integer commandUsed,
        /** Сколько их даёт империи её колонии — п. 8: у военачальников вдвое больше. */
        Integer commandCapacity
) {
}
