package com.moo3.server.dto;

/** Справочник богатства минералами — п. 4.1.3. */
public record MineralRichnessDto(
        String code,
        String label,
        Integer productionPerWorker
) {
}
