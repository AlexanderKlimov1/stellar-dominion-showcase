package com.moo3.server.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Сколько кораблей одного проекта уходит в перелёт — п. 8.
 * <p>
 * Флот не обязан лететь целиком: игрок отправляет часть кораблей, а остальные остаются
 * держать систему. Поэтому отправка описывается не числом кораблей, а списком «проект —
 * сколько»: корабли разных проектов и стоят по-разному, и в бою делают разное.
 *
 * @param designId проект корабля; корабли этого проекта должны быть в отправляющем флоте
 * @param ships    сколько кораблей этого проекта уходит; больше, чем есть, взять нельзя
 */
public record FleetShipOrder(
        @NotNull
        UUID designId,

        @NotNull
        @Positive
        Integer ships
) {
}
