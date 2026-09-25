package com.moo3.server.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Пропуск учётной записи в параметре метода контроллера — п. 3.1.
 * <p>
 * Значение берётся из заголовка {@code X-Account-Token}. Пропуск игрока
 * ({@link AccessToken}) — про одну партию, этот — про человека: им игра узнаёт, что
 * пришедший вообще авторизован.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AccountToken {
}
