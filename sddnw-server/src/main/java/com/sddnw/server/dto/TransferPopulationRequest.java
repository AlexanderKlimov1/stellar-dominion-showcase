package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.ColonistJob;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Перевозка жителей в другую колонию — п. 4.1.1.
 * <p>
 * Грузовой флот резервируется из расчёта <b>один грузовик на единицу населения</b>:
 * пока рейс в пути, эти грузовики не возят еду.
 *
 * @param targetPlanetId колония назначения — своя же, в любой системе
 * @param population     сколько жителей отправить; столько же грузовиков и займётся
 * @param job            с какого дела взяты жители (01.10.2026): уезжают именно они, и на
 *                       новом месте встают на то же дело. Пусто — как раньше: колония-источник
 *                       решает сама, кого отпустить, а колония назначения — куда поставить
 * @param targetJob      на какое дело встать по прибытии, если игрок положил группу на полосу
 *                       другого занятия; пусто — то же, что {@code job}
 */
public record TransferPopulationRequest(
        @NotBlank
        String accessToken,

        @NotNull
        UUID targetPlanetId,

        @NotNull
        @Min(1)
        Integer population,

        ColonistJob job,

        ColonistJob targetJob
) {
}
