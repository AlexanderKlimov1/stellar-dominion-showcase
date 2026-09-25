package com.moo3.server.domain.save;

/** Компонент проекта корабля в слепке партии — п. 8. */
public record ShipDesignComponentSnapshot(
        String componentCode,
        Integer count
) {
}
