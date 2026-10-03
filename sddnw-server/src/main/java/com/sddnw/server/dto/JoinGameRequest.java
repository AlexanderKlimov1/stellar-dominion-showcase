package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.BannerColor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Присоединение игрока к существующей игре — п. 3.2. */
public record JoinGameRequest(
        @NotBlank
        @Size(max = 128)
        String playerName,

        /** Название родной звезды игрока; пусто — имя выберет генератор (п. 11.3). */
        @Size(max = 64)
        String homeStarName,

        String raceCode,

        /** Название расы, собранной игроком в конструкторе — п. 7. */
        @Size(max = 128)
        String raceName,

        /** Коды выбранных особенностей расы — п. 7. */
        List<String> raceTraits,

        /** Знамя игрока — п. 3.2; пусто — знамя расы или первое свободное. */
        BannerColor banner
) {
}
