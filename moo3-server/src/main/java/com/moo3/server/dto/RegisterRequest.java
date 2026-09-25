package com.moo3.server.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Заявка на регистрацию — п. 3.1.
 * <p>
 * Логина здесь нет: логином служит сама почта. Имя — только для показа в интерфейсе игры,
 * на вход оно не влияет.
 * <p>
 * Номер задачки и ответ на неё проверками аннотаций не спрашиваются: задачка выключается
 * настройкой ({@code moo3.auth.registration-challenge}), и обязательными их делает сам
 * {@code RegistrationChallenge}, а не форма запроса.
 *
 * @param challengeId     номер вопроса, полученного у {@code GET /api/auth/challenge}
 * @param challengeAnswer ответ на него — числом или словом, как в вопросе
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(min = 8, max = 100) String password,
        @Size(max = 64) String challengeId,
        @Size(max = 32) String challengeAnswer
) {
}
