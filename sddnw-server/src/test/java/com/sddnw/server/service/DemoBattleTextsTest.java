package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Имена демонстрационного боя — на обоих языках (трек техдолга, пункт 18).
 * <p>
 * <b>Зачем.</b> Корабли демонстрации звались русскими строками прямо в
 * {@code DemoBattleService} («Крейсер «Гроза»»), строки ложились в базу именем проекта, и
 * английский игрок читал их в карточке и в журнале боя посреди английского текста. Теперь
 * имя — ключ словаря, а показывает его {@code Messages.known}. Проверка идёт по самим
 * рецептам ({@code DemoBattleService.textKeys}): корабль, добавленный без перевода, провалит
 * её сразу, а не покажется игроку голым ключом.
 */
class DemoBattleTextsTest {

    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    private static Properties read(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = DemoBattleTextsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "нет словаря " + resource);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    @DisplayName("Каждое имя демонстрации есть в обоих словарях и правда переведено")
    void everyNameIsTranslated() throws IOException {
        Properties en = read("messages.properties");
        Properties ru = read("messages_ru.properties");

        List<String> wrong = new ArrayList<>();
        for (String key : DemoBattleService.textKeys()) {
            String english = en.getProperty(key, "");
            String russian = ru.getProperty(key, "");
            if (english.isBlank() || CYRILLIC.matcher(english).find()) {
                wrong.add("en: " + key + " = " + english);
            }
            if (russian.isBlank() || !CYRILLIC.matcher(russian).find()) {
                wrong.add("ru: " + key + " = " + russian);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("Имена рецептов — ключи, а не слова: в базу не ложится язык")
    void recipeNamesAreKeys() {
        List<String> notKeys = DemoBattleService.textKeys().stream()
                .filter(key -> !key.startsWith("demo."))
                .toList();
        assertEquals(List.of(), notKeys);
        // Двенадцать кораблей и подпись партии: проверке есть что проверять.
        assertEquals(13, DemoBattleService.textKeys().size());
    }
}
