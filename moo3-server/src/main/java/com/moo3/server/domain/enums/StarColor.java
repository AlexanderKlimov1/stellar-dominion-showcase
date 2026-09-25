package com.moo3.server.domain.enums;

/**
 * Цвет звезды на карте галактики — п. 11.3.
 */
public enum StarColor {

    RED("Red", "#ff4b3e"),
    WHITE("White", "#f2f5ff"),
    YELLOW("Yellow", "#ffd447");

    private final String label;
    private final String hexColor;

    StarColor(String label, String hexColor) {
        this.label = label;
        this.hexColor = hexColor;
    }

    public String getLabel() {
        return label;
    }

    public String getHexColor() {
        return hexColor;
    }
}
