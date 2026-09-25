package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.web.error.TooManyAttemptsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Защита от подбора пароля — п. 3.1.
 * <p>
 * Проверяется то, ради чего она заведена: словарь против одной записи и распыление
 * одного пароля по многим записям упираются в замок, а честный игрок с одной опечаткой —
 * нет. Ошибка здесь не видна на экране: вход продолжает работать, просто перестаёт
 * держать перебор.
 */
class LoginThrottleTest {

    /** Пороги те же, что в `application.yml`: пять на запись, двадцать на адрес. */
    private LoginThrottle throttle(Integer perAccount, Integer perAddress) {
        return new LoginThrottle(new AuthProperties("admin", "admin.txt", 30, 48,
                "moo3@example.test", "http://localhost:5173", "mail-outbox",
                perAccount, perAddress, 15, 5, 10, Boolean.TRUE));
    }

    @Test
    @DisplayName("Словарь против одной записи упирается в замок")
    void dictionaryAgainstOneAccountIsLocked() {
        LoginThrottle throttle = throttle(5, 100);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertDoesNotThrow(() -> throttle.requireAllowed("igrok@example.test", "10.0.0.1"));
            throttle.failed("igrok@example.test", "10.0.0.1");
        }
        assertDoesNotThrow(() -> throttle.requireAllowed("igrok@example.test", "10.0.0.1"),
                "до порога вход спрашивать можно: игрок ошибается тоже");
        throttle.failed("igrok@example.test", "10.0.0.1");

        TooManyAttemptsException locked = assertThrows(TooManyAttemptsException.class,
                () -> throttle.requireAllowed("igrok@example.test", "10.0.0.1"));
        assertTrue(locked.retryAfter().toSeconds() > 0, "клиенту говорят, сколько ждать");
    }

    @Test
    @DisplayName("Один пароль по многим записям ловится счётчиком адреса")
    void passwordSprayingIsCaughtByAddress() {
        LoginThrottle throttle = throttle(5, 10);

        for (int attempt = 0; attempt < 10; attempt++) {
            String login = "igrok" + attempt + "@example.test";
            throttle.failed(login, "10.0.0.2");
        }

        // Запись нетронутая: по ней всего одна неудача. Ловит именно адрес.
        assertThrows(TooManyAttemptsException.class,
                () -> throttle.requireAllowed("kto-to-esche@example.test", "10.0.0.2"));
    }

    @Test
    @DisplayName("Замок на одну запись не запирает соседнюю и чужой адрес")
    void lockIsNarrow() {
        LoginThrottle throttle = throttle(3, 100);

        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.failed("igrok@example.test", "10.0.0.3");
        }

        assertThrows(TooManyAttemptsException.class,
                () -> throttle.requireAllowed("igrok@example.test", "10.0.0.3"));
        assertDoesNotThrow(() -> throttle.requireAllowed("sosed@example.test", "10.0.0.4"),
                "чужая запись с чужого адреса к этому перебору отношения не имеет");
    }

    @Test
    @DisplayName("Удачный вход обнуляет счётчик записи, но не счётчик адреса")
    void successClearsAccountButNotAddress() {
        LoginThrottle throttle = throttle(3, 4);

        throttle.failed("igrok@example.test", "10.0.0.5");
        throttle.failed("igrok@example.test", "10.0.0.5");
        throttle.succeeded("igrok@example.test");

        // Записи прощены обе неудачи: две новые её ещё не запирают.
        throttle.failed("igrok@example.test", "10.0.0.5");
        assertDoesNotThrow(() -> throttle.requireAllowed("igrok@example.test", "10.0.0.6"));

        // А адресу — нет: иначе перебор сбрасывал бы себе лимит входом в свою запись.
        throttle.failed("igrok@example.test", "10.0.0.5");
        assertThrows(TooManyAttemptsException.class,
                () -> throttle.requireAllowed("kto-to-esche@example.test", "10.0.0.5"));
    }
}
