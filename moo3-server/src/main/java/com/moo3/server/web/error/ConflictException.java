package com.moo3.server.web.error;

/** Действие невозможно в текущем состоянии игры. */
public class ConflictException extends ApiException {

    public ConflictException(String key, Object... args) {
        super(key, args);
    }
}
