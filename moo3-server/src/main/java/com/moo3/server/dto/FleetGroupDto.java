package com.moo3.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Флот игрока в звёздной системе или в пути к ней — п. 8.
 * <p>
 * У флота в пути {@code starSystemId} — это система вылета: она же начало линии полёта
 * на карте. Где именно флот сейчас, клиент считает сам по номерам ходов вылета и прибытия:
 * сервер шлёт маршрут и сроки, а не координаты, — на карте флот идёт по прямой.
 *
 * @param ships      кораблей в строю всего
 * @param initiative инициатива флота: по ней решают, кто первым выбирает бой при встрече
 * @param power      боевая сила флота: сумма сил его кораблей
 * @param speed      скорость флота в парсеках за ход — по самому медленному кораблю
 * @param composition из каких проектов флот собран
 * @param targetSystemId куда летит; пусто — флот стоит в системе
 * @param targetSystemName название системы назначения; у неразведанной его нет
 * @param departureTurn ход вылета
 * @param arrivalTurn ход, на котором флот окажется на месте
 */
public record FleetGroupDto(
        UUID id,
        UUID starSystemId,
        String systemName,
        Integer ships,
        Integer initiative,
        Integer power,
        Integer speed,
        List<FleetShipDto> composition,
        UUID targetSystemId,
        String targetSystemName,
        Integer departureTurn,
        Integer arrivalTurn
) {
}
