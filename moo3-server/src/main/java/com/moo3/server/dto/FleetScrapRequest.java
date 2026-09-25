package com.moo3.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Списание кораблей со службы — п. 8, кнопка SCRAP окна Fleet Operations MOO II.
 * <p>
 * Списывают выборочно: устаревший фрегат уходит, а стоящий рядом дредноут остаётся.
 * Поэтому в запросе не число кораблей, а список «проект — сколько»: во флоте корабли
 * лежат по проектам, и списать «три любых» значило бы решать за игрока, каких именно.
 * <p>
 * Пустого списка здесь быть не может, в отличие от перелёта: «отправить весь флот» —
 * обычный приказ, а «списать весь флот» одним нажатием слишком дорого стоит, чтобы
 * получаться по умолчанию.
 */
public record FleetScrapRequest(
        @NotBlank
        String accessToken,

        @NotEmpty
        @Valid
        List<FleetShipOrder> ships
) {
}
