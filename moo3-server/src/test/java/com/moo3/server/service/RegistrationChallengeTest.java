package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.dto.ChallengeDto;
import com.moo3.server.web.error.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Задачка перед регистрацией — п. 3.1.
 * <p>
 * Проверяется то, на чём она держится: неверный ответ не проходит, верный проходит один
 * раз, а выключенная настройкой задачка никого не спрашивает. Ошибка здесь не видна на
 * экране — форма просто перестаёт что-либо значить.
 */
class RegistrationChallengeTest {

    /** Слова вопроса на языке по умолчанию — английском: тест не ставит языка запроса. */
    private static final List<String> WORDS = List.of(
            "zero", "one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten");

    private RegistrationChallenge challenge(Boolean enabled) {
        return new RegistrationChallenge(new AuthProperties("admin", "admin.txt", 30, 48,
                "moo3@example.test", "http://localhost:5173", "mail-outbox",
                5, 20, 15, 5, 10, enabled), Messages.standalone());
    }

    /** Решает вопрос так же, как это делает игрок: числа в нём названы словами. */
    private String solve(ChallengeDto task) {
        List<Integer> numbers = List.of(task.question().toLowerCase(Locale.ROOT)
                        .replace("?", "").split(" ")).stream()
                .filter(WORDS::contains)
                .map(WORDS::indexOf)
                .toList();
        return String.valueOf(task.question().contains("plus")
                ? numbers.get(0) + numbers.get(1)
                : numbers.get(0) - numbers.get(1));
    }

    @Test
    @DisplayName("Верный ответ проходит, и вопрос после этого потрачен")
    void rightAnswerPassesOnce() {
        RegistrationChallenge challenge = challenge(Boolean.TRUE);
        ChallengeDto task = challenge.issue();
        assertNotNull(task.id());
        assertTrue(task.question().contains("plus") || task.question().contains("minus"),
                "вопрос читается человеком: " + task.question());

        String answer = solve(task);
        assertDoesNotThrow(() -> challenge.require(task.id(), answer));
        assertThrows(BadRequestException.class, () -> challenge.require(task.id(), answer),
                "одна решённая задачка открывает дорогу одной заявке, а не тысяче");
    }

    @Test
    @DisplayName("Неверный ответ и пустой ответ не проходят")
    void wrongAnswerIsRefused() {
        RegistrationChallenge challenge = challenge(Boolean.TRUE);
        ChallengeDto task = challenge.issue();

        assertThrows(BadRequestException.class,
                () -> challenge.require(task.id(), String.valueOf(Integer.parseInt(solve(task)) + 1)));
        assertThrows(BadRequestException.class, () -> challenge.require(null, null));
        assertThrows(BadRequestException.class, () -> challenge.require("выдуманный", "5"));
    }

    @Test
    @DisplayName("Ответ принимается и словом, и числом")
    void answerInWordsIsAccepted() {
        RegistrationChallenge challenge = challenge(Boolean.TRUE);
        ChallengeDto task = challenge.issue();

        String word = WORDS.get(Integer.parseInt(solve(task)));
        assertDoesNotThrow(() -> challenge.require(task.id(), word),
                "игрок пишет то, что видит в вопросе, — спорить с ним из-за формы записи не за что");
    }

    @Test
    @DisplayName("Выключенная задачка никого не спрашивает")
    void disabledChallengeAsksNothing() {
        RegistrationChallenge challenge = challenge(Boolean.FALSE);

        assertDoesNotThrow(() -> challenge.require(null, null),
                "домашней игре в локальной сети задачка ни к чему");
    }
}
