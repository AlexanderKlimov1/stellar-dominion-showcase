package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.web.error.TooManyAttemptsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Предел регистраций с одного адреса — п. 3.1.
 * <p>
 * Проверяется то, ради чего он заведён: с одного адреса нельзя завести писем больше, чем
 * разрешено, а соседний адрес этим не задет. Ошибка здесь не видна на экране — сервер
 * просто рассылает письма чужими руками, пока его ящик не забанят.
 */
class RegistrationThrottleTest {

    private RegistrationThrottle throttle(Integer perHour) {
        return new RegistrationThrottle(new AuthProperties("admin", "admin.txt", 30, 48,
                "moo3@example.test", "http://localhost:5173", "mail-outbox",
                5, 20, 15, 5, perHour, Boolean.TRUE));
    }

    @Test
    @DisplayName("Больше разрешённого с одного адреса за час не завести")
    void addressRunsOutOfRegistrations() {
        RegistrationThrottle throttle = throttle(3);

        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> throttle.requireAllowed("10.0.0.1"));
            throttle.registered("10.0.0.1");
        }

        TooManyAttemptsException locked = assertThrows(TooManyAttemptsException.class,
                () -> throttle.requireAllowed("10.0.0.1"));
        assertTrue(locked.retryAfter().toMinutes() <= 60, "ждать не дольше самого окна");
    }

    @Test
    @DisplayName("Предел на один адрес не закрывает регистрацию соседнему")
    void limitIsPerAddress() {
        RegistrationThrottle throttle = throttle(2);

        throttle.registered("10.0.0.2");
        throttle.registered("10.0.0.2");

        assertThrows(TooManyAttemptsException.class, () -> throttle.requireAllowed("10.0.0.2"));
        assertDoesNotThrow(() -> throttle.requireAllowed("10.0.0.3"));
    }

    @Test
    @DisplayName("Считаются заведённые записи, а не попытки")
    void refusalsAreNotCounted() {
        RegistrationThrottle throttle = throttle(2);

        // Отказ (занятая почта, короткий пароль) до registered не доходит вовсе —
        // счётчик остаётся пустым, сколько бы таких попыток ни было.
        for (int i = 0; i < 20; i++) {
            assertDoesNotThrow(() -> throttle.requireAllowed("10.0.0.4"));
        }
        throttle.registered("10.0.0.4");
        assertDoesNotThrow(() -> throttle.requireAllowed("10.0.0.4"));
    }
}
