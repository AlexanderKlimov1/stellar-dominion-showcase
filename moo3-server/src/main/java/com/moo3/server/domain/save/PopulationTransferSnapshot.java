package com.moo3.server.domain.save;

/**
 * Рейс с жителями в слепке партии — п. 4.1.1.
 * <p>
 * Планеты названы номерами: система — своим местом в списке систем слепка, планета —
 * своим местом в списке планет этой системы. Идентификаторов в слепке нет вовсе: при
 * загрузке заводится новая партия со своими.
 * <p>
 * Рейсы сохраняются потому, что в них летят живые жители: потеряй игра рейс при загрузке —
 * потерялось бы и население, которое уже списано с колонии-отправителя.
 */
public record PopulationTransferSnapshot(
        Integer ownerSlot,
        Integer fromSystemIndex,
        Integer fromPlanetIndex,
        Integer toSystemIndex,
        Integer toPlanetIndex,
        Integer population,
        Integer departedTurn
) {
}
