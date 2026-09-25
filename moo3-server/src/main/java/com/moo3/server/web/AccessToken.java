package com.moo3.server.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Пропуск игрока в параметре метода контроллера.
 * <p>
 * Значение берётся из заголовка {@code X-Access-Token}, а если его нет — из параметра
 * запроса {@code accessToken}. Заголовок предпочтительнее: адрес запроса оседает в
 * журналах сервера, прокси и истории браузера, и пропуску там не место. Параметр остаётся
 * ради подписки на события: {@code EventSource} браузера заголовки слать не умеет.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccessToken {
}
