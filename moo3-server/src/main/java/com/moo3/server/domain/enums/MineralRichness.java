package com.moo3.server.domain.enums;

/**
 * Минералы: единиц продукции на одного рабочего без дополнительных технологий — п. 4.1.3.
 */
public enum MineralRichness {

    ULTRA_POOR("Ultra poor", 1),
    POOR("Poor", 2),
    RICH("Rich", 3),
    AVERAGE("Average", 4),
    ULTRA_RICH("Ultra rich", 5);

    private final String label;
    private final Integer productionPerWorker;

    MineralRichness(String label, Integer productionPerWorker) {
        this.label = label;
        this.productionPerWorker = productionPerWorker;
    }

    public String getLabel() {
        return label;
    }

    public Integer getProductionPerWorker() {
        return productionPerWorker;
    }
}
