package com.moo3.server.web.error;

/** Недостаточно прав: например, стартовать игру может только её создатель (п. 3.2). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String key, Object... args) {
        super(key, args);
    }
}
