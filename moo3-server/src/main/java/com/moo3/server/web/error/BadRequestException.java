package com.moo3.server.web.error;

/**
 * Заявка в порядке по форме, но не по сути: например, ответ на задачку регистрации не
 * сошёлся (п. 3.1).
 * <p>
 * Отдельно от проверок аннотациями: те ловят пустое поле и длину, а это — правило, которое
 * знает только сервер. Код тот же, 400: чинить нужно запрос, а не сервер.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String key, Object... args) {
        super(key, args);
    }
}
