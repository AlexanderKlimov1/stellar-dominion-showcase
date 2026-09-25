package com.moo3.server.dto;

/**
 * Что вышло из регистрации — п. 3.1.
 *
 * @param mailSent ушло ли письмо по-настоящему; {@code false} — почтовый сервер не
 *                 настроен, и письмо лежит в исходящих сервера
 * @param notice   то же словами, для интерфейса: игрок должен понимать, где искать ссылку
 */
public record RegisterResultDto(
        String login,
        String email,
        Boolean mailSent,
        String notice
) {
}
