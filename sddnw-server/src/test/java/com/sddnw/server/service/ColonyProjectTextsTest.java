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
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тексты особых проектов колонии — на обоих языках (п. 3.5).
 * <p>
 * <b>Зачем.</b> Имена и описания домов, товаров, шпиона, грузовика, колониальной базы и
 * гражданских кораблей были русскими строками прямо в {@code ColonyService}, и на
 * английском экране колонии стояло «Товары». Ни один тест этого не видел: ключей не было
 * вовсе, а строка есть строка. Нашлось это только глазами, проходом по пути новичка.
 * <p>
 * Теперь тексты лежат в словарях, и проверка держится на правиле: у КАЖДОГО проекта из
 * таблицы {@code ColonyService.SPECIALS} есть имя и описание на обоих языках, в английском
 * нет кириллицы, а в русском она есть — то есть он и правда переведён, а не скопирован.
 * Проверка идёт по самой таблице: новый проект, заведённый без строк, провалит её сразу.
 */
class ColonyProjectTextsTest {

    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");

    /** Тексты корабельных строк стройки — у них ключи свои, вне таблицы особых проектов. */
    private static final List<String> SHIP_KEYS = List.of(
            "colony.project.ship.name",
            "colony.project.ship.description",
            "colony.project.ship.withdrawn");

    private static Properties read(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ColonyProjectTextsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "нет словаря " + resource);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    /** Все ключи, которые обязаны быть в обоих словарях. */
    private static Set<String> requiredKeys() {
        Set<String> keys = new TreeSet<>(SHIP_KEYS);
        for (String key : ColonyService.specialTextKeys()) {
            keys.add("colony.project." + key + ".name");
            keys.add("colony.project." + key + ".description");
        }
        return keys;
    }

    @Test
    @DisplayName("У каждого особого проекта имя и описание есть на обоих языках")
    void everyProjectIsInBothDictionaries() throws IOException {
        Properties en = read("messages.properties");
        Properties ru = read("messages_ru.properties");

        List<String> missing = new ArrayList<>();
        for (String key : requiredKeys()) {
            if (en.getProperty(key, "").isBlank()) {
                missing.add("en: " + key);
            }
            if (ru.getProperty(key, "").isBlank()) {
                missing.add("ru: " + key);
            }
        }
        assertEquals(List.of(), missing, "у проектов нет текстов");
    }

    @Test
    @DisplayName("Английский текст без кириллицы, а русский и правда русский")
    void textsAreActuallyTranslated() throws IOException {
        Properties en = read("messages.properties");
        Properties ru = read("messages_ru.properties");

        List<String> wrong = new ArrayList<>();
        for (String key : requiredKeys()) {
            if (CYRILLIC.matcher(en.getProperty(key, "")).find()) {
                // Ровно та поломка, ради которой эта проверка заведена.
                wrong.add("кириллица в английском: " + key);
            }
            if (!CYRILLIC.matcher(ru.getProperty(key, "")).find()) {
                wrong.add("русский не переведён: " + key);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("Таблица особых проектов не пуста — иначе проверке нечего проверять")
    void tableIsNotEmpty() {
        // Все восемь: дома, товары, шпион, грузовик, база и три гражданских корабля.
        assertEquals(8, ColonyService.specialTextKeys().size());
    }
}
