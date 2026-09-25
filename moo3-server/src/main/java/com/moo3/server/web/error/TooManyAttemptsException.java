package com.moo3.server.web.error;

import java.time.Duration;

/**
 * Слишком много неудачных попыток входа — п. 3.1: подбор пароля остановлен.
 * <p>
 * Отдельное исключение, а не {@code ForbiddenException}: отказ здесь временный и
 * относится не к паролю, а к самим попыткам. Клиенту он приходит кодом 429 и заголовком
 * {@code Retry-After}, чтобы отличать «пароль не тот» от «подождите» было можно и без
 * разбора текста.
 *
 * @see com.moo3.server.service.LoginThrottle
 */
public class TooManyAttemptsException extends ApiException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter, String key, Object... args) {
        super(key, args);
        this.retryAfter = retryAfter;
    }

    /** Сколько ждать до следующей попытки. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
