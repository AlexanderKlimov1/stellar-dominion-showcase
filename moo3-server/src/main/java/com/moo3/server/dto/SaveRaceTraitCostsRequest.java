package com.moo3.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Правка цен особенностей расы — п. 7: то, что сохраняет экран «Стоимость особенностей рас».
 * <p>
 * Правится только цена и бюджет очков: набор особенностей и их действия живут в файле
 * справочника и редактором не трогаются.
 *
 * @param picks  бюджет очков расы; {@code null} — оставить прежний
 * @param traits новые цены особенностей; пусто — меняется только бюджет
 */
public record SaveRaceTraitCostsRequest(
        @Min(1) @Max(100) Integer picks,
        List<@Valid TraitCost> traits
) {

    /**
     * Новая цена одной особенности.
     *
     * @param picks цена в очках расы; отрицательная — особенность очки возвращает
     */
    public record TraitCost(
            @NotBlank String code,
            @NotNull @Min(-20) @Max(20) Integer picks
    ) {
    }
}
