package com.moo3.server.domain.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Плодородность планеты и множитель населения — п. 4.1.2.
 * <p>
 * Пояса астероидов и газовые гиганты колонизировать нельзя, у них нет места
 * в цепочке терраформирования ({@link #getTerraformOrder()} == 0).
 * <p>
 * Еда с одного фермера — базовые значения MOO II без расовых бонусов и технологий:
 * Gaya даёт 3, влажные климаты 2, сухие 1, на безжизненных фермер не даёт ничего.
 */
public enum PlanetClimate {

    TOXIC("Toxic", 10, true, 1, 0, "TOXIC_WASTE_ELIMINATION"),
    RADIATED("Radiated", 20, true, 2, 0, "RADIATION_SHIELD"),
    BARREN("Barren", 30, true, 3, 0, "TERRAFORMING"),
    DESERT("Desert", 40, true, 4, 1, "TERRAFORMING"),
    TUNDRA("Tundra", 50, true, 5, 1, "TERRAFORMING"),
    ARID("Arid", 60, true, 6, 1, "TERRAFORMING"),
    SWAMP("Swamp", 70, true, 7, 2, "TERRAFORMING"),
    OCEAN("Ocean", 80, true, 8, 2, "TERRAFORMING"),
    TERRAN("Terran", 90, true, 9, 2, "GAIA_TRANSFORMATION"),
    GAIA("Gaya", 100, true, 10, 3, null),
    ASTEROID_BELT("Asteroid belt", 0, false, 0, 0, null),
    GAS_GIANT("Gas giant", 0, false, 0, 0, null);

    private final String label;
    /** Множитель населения в процентах. */
    private final Integer populationMultiplierPercent;
    private final Boolean colonizable;
    /** Позиция в цепочке терраформирования, 0 — вне цепочки. */
    private final Integer terraformOrder;
    /** Единиц еды с одного фермера. */
    private final Integer foodPerFarmer;
    /**
     * Какая технология нужна, чтобы переделать климат в следующий; {@code null} — переделывать
     * дальше некуда (Gaya) или нечего (пояс астероидов, газовый гигант).
     * <p>
     * Жило это в таблице {@code ref_planet_climate} — второй копией тех же климатов рядом с
     * этим перечислением. Копию убрали (18.09.2026): справочник, разложенный по двум местам,
     * рано или поздно разъезжается, а база к тому же не даёт поднять игру на пустой схеме —
     * балансовому прогону в памяти справочники браться неоткуда.
     */
    private final String requiredTechCode;

    PlanetClimate(String label,
                  Integer populationMultiplierPercent,
                  Boolean colonizable,
                  Integer terraformOrder,
                  Integer foodPerFarmer,
                  String requiredTechCode) {
        this.label = label;
        this.populationMultiplierPercent = populationMultiplierPercent;
        this.colonizable = colonizable;
        this.terraformOrder = terraformOrder;
        this.foodPerFarmer = foodPerFarmer;
        this.requiredTechCode = requiredTechCode;
    }

    public String getRequiredTechCode() {
        return requiredTechCode;
    }

    public String getLabel() {
        return label;
    }

    public Integer getPopulationMultiplierPercent() {
        return populationMultiplierPercent;
    }

    public Boolean getColonizable() {
        return colonizable;
    }

    public Integer getTerraformOrder() {
        return terraformOrder;
    }

    public Integer getFoodPerFarmer() {
        return foodPerFarmer;
    }

    /** Климаты, участвующие в терраформировании, в порядке улучшения (п. 4.1.2.1). */
    public static List<PlanetClimate> terraformChain() {
        return Arrays.stream(values())
                .filter(climate -> climate.terraformOrder > 0)
                .sorted((a, b) -> Integer.compare(a.terraformOrder, b.terraformOrder))
                .toList();
    }

    /** Следующая ступень терраформирования, если она существует. */
    public Optional<PlanetClimate> next() {
        if (terraformOrder == 0) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(climate -> climate.terraformOrder.equals(terraformOrder + 1))
                .findFirst();
    }
}
