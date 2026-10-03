package com.sddnw.server.dto;

/** Справочник размеров планет — п. 4.1.1. */
public record PlanetSizeDto(
        String code,
        String label,
        Integer basePopulation
) {
}
