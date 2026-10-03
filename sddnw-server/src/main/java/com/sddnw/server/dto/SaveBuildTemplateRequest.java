package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Сохранение шаблона стройки — п. 10.
 *
 * @param id       пусто — новый шаблон; иначе правится этот, и он обязан быть своим
 * @param tier     стадия развития: 1…9, назначает игрок
 * @param projects коды проектов по порядку; длиннее очереди шаблон заводить можно —
 *                 закладывается он настолько, насколько в очередь влезет
 */
public record SaveBuildTemplateRequest(
        UUID id,

        @NotBlank
        @Size(max = 80)
        String name,

        @Min(1)
        @Max(9)
        Integer tier,

        @Size(max = 20)
        List<String> projects
) {
}
