package com.moo3.server.domain.save;

/**
 * Корабли одного проекта во флоте из слепка партии — п. 8.
 *
 * @param designIndex порядковый номер проекта в списке проектов слепка: идентификаторов
 *                    слепок не хранит
 * @param colonists   сколько жителей везут эти корабли — п. 4.1, п. 12: поселенцы и
 *                    десант; у слепков, снятых до расселения, поля нет
 */
public record FleetShipSnapshot(
        Integer designIndex,
        Integer ships,
        Integer colonists
) {
}
