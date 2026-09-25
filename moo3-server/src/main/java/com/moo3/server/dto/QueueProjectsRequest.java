package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Один проект сразу многим колониям — п. 10.
 * <p>
 * Так распоряжаются империей из списка колоний: изучив здание, его закладывают всем разом.
 * Проект должен быть доступен каждой колонии набора — иначе приказ не исполняется вовсе.
 *
 * @param planetIds   колонии, которым ставится проект; пустой набор смысла не имеет
 * @param projectCode код здания из справочника либо особый проект
 * @param top         поставить в голову очереди, сдвинув её вниз; из списка колоний —
 *                    всегда так, потому и поле есть
 */
public record QueueProjectsRequest(
        @NotBlank
        String accessToken,

        @NotEmpty
        List<UUID> planetIds,

        @NotBlank
        String projectCode,

        Boolean top
) {
}
