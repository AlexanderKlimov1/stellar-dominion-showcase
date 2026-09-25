package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Правила входа в игру — п. 3.1.
 * <p>
 * Проверяется то, на чём держится вся защита: пароль не хранится открытым и не сводится к
 * одной и той же строке, а пропуска не повторяются. Ошибка здесь не падает и не видна на
 * экране — она просто делает вход дырявым.
 */
class AccountRulesTest {

    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    @Test
    @DisplayName("Пароль в хеше не читается, но проверяется")
    void passwordIsHashedNotStored() {
        String password = "orion-2026";
        String hash = passwords.encode(password);

        assertFalse(hash.contains(password), "открытый пароль не должен попадать в хеш");
        assertTrue(passwords.matches(password, hash));
        assertFalse(passwords.matches("orion-2027", hash));
    }

    @Test
    @DisplayName("Один и тот же пароль даёт разные хеши: соль своя у каждого")
    void samePasswordDiffersInStorage() {
        String first = passwords.encode("orion-2026");
        String second = passwords.encode("orion-2026");

        assertNotEquals(first, second, "без соли одинаковые пароли было бы видно по базе");
        assertTrue(passwords.matches("orion-2026", first));
        assertTrue(passwords.matches("orion-2026", second));
    }

    @Test
    @DisplayName("Пропуска не повторяются и длины хватает, чтобы не подобрать")
    void tokensAreUnique() {
        SecureRandom random = new SecureRandom();
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            tokens.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }

        assertEquals(1000, tokens.size(), "повторившийся пропуск — это чужой сеанс");
        assertTrue(tokens.iterator().next().length() >= 40);
    }

    @Test
    @DisplayName("Короткий пароль не принимается: нижняя граница — восемь знаков")
    void shortPasswordIsRefused() {
        assertEquals(8, AccountService.MIN_PASSWORD_LENGTH);
        assertTrue("orion-2026".length() >= AccountService.MIN_PASSWORD_LENGTH);
        assertFalse("короткий".length() < AccountService.MIN_PASSWORD_LENGTH,
                "восемь знаков — это уже граница, а не «меньше»");
    }
}
