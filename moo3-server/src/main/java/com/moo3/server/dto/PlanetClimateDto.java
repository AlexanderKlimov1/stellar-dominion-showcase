package com.moo3.server.dto;

/** Справочник плодородности и цепочки терраформирования — п. 4.1.2 / 4.1.2.1. */
public record PlanetClimateDto(
        String code,
        String label,
        Integer populationMultiplierPercent,
        Boolean colonizable,
        Integer terraformOrder,
        String nextClimateCode,
        String requiredTechCode
) {
}
