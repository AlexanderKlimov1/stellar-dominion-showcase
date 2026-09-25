package com.moo3.server.dto;

import jakarta.validation.constraints.NotBlank;

/** Вход — п. 3.1: логин или почта плюс пароль. */
public record LoginRequest(
        @NotBlank String login,
        @NotBlank String password
) {
}
