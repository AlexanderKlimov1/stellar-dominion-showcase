package com.moo3.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Прогнать подряд несколько ходов — этап 0 балансировки (`balance-metrics-works.txt`).
 * <p>
 * Нужен прогонам, а не игроку: балансировка играет тысячи партий по сотне ходов, и
 * отдельный запрос на каждый ход тратит время на дорогу, а не на игру. Партия при этом
 * считается ровно так же, как от кнопки «Закончить ход», — никакого второго пути у хода
 * нет, иначе прогоны мерили бы не ту игру, в которую играют люди.
 *
 * @param turns сколько ходов сыграть подряд; счёт прекращается раньше, если партия
 *              кончилась или ход не сдвинулся (партия ждёт другого человека)
 */
public record AdvanceTurnsRequest(
        @NotBlank
        String accessToken,

        @NotNull
        @Min(1)
        @Max(1000)
        Integer turns
) {
}
