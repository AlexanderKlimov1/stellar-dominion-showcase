package com.moo3.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Сохранение проекта корабля в ячейку — п. 8.
 *
 * @param slot       ячейка, 1..6; занятая ячейка переписывается, старый проект остаётся
 *                   у построенных кораблей
 * @param components состав: коды компонентов и сколько раз каждый взят
 */
public record SaveShipDesignRequest(
        @NotNull @Min(1) @Max(6) Integer slot,
        @NotBlank @Size(max = 64) String name,
        @NotBlank String hullCode,
        @NotEmpty List<@Valid ShipComponentChoice> components
) {

    /**
     * Компонент проекта: код из справочника, количество и модификации ствола — п. 8.
     * Модификации бывают только у оружия; у прочих гнёзд поле пустое.
     */
    public record ShipComponentChoice(
            @NotBlank String code,
            @NotNull @Min(1) Integer count,
            List<String> modifications
    ) {
    }
}
