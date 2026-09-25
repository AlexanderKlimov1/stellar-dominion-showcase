package com.moo3.server.domain.enums;

/**
 * Технологии, открывающие ступени терраформирования — п. 4.1.2.1.
 */
public enum TerraformingTech {

    TOXIC_WASTE_ELIMINATION("Toxic Waste Elimination"),
    RADIATION_SHIELD("Radiation Shield"),
    TERRAFORMING("Terraforming"),
    GAIA_TRANSFORMATION("Gaya Transformation");

    private final String label;

    TerraformingTech(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
