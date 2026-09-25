package com.moo3.server.web.error;

/** Запрошенный объект не найден. Ключ сообщения и подстановки — см. {@link ApiException}. */
public class NotFoundException extends ApiException {

    public NotFoundException(String key, Object... args) {
        super(key, args);
    }
}
