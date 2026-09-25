package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.Leader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Справочник лидеров — п. 6: имена и звания на обоих языках.
 * <p>
 * Проверка сторожит ровно то, что однажды и разъехалось: звания лидерам перевели, а имена
 * оставили одной строкой, и по-русски выходила латиница посреди русской фразы («Quorrin,
 * Непредсказуемый»), хотя у рас транслитерация есть (Cerebri — Церебри). Нашлось это не
 * тестом, а глазами, через месяц после перевода.
 * <p>
 * Правило держится на языке, а не на списке имён: перечислять тут шесть десятков
 * написаний значило бы заводить вторую правду о справочнике, и первое же переименование
 * уронило бы проверку, не найдя поломки.
 */
class LeaderCatalogTest {

    private final LeaderCatalog catalog = new LeaderCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    /** Есть ли в строке латиница — ею и опознаётся непереведённое имя. */
    private static boolean hasLatin(String text) {
        return text.chars().anyMatch(symbol -> (symbol >= 'A' && symbol <= 'Z')
                || (symbol >= 'a' && symbol <= 'z'));
    }

    @Test
    @DisplayName("У каждого лидера есть имя и звание на обоих языках")
    void everyLeaderSpeaksBothLanguages() {
        List<Leader> leaders = catalog.all();
        assertThat(leaders).isNotEmpty();

        // Язык ставится явно: вне запроса его задаёт сосед по прогону тестов, и проверка,
        // которой язык важен, обязана называть его сама (те же грабли, что у строёв).
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        for (Leader leader : leaders) {
            assertThat(leader.name()).as("имя %s по-английски", leader.code()).isNotBlank();
            assertThat(leader.title()).as("звание %s по-английски", leader.code()).isNotBlank();
        }

        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        for (Leader leader : leaders) {
            assertThat(leader.name()).as("имя %s по-русски", leader.code()).isNotBlank();
            assertThat(hasLatin(leader.name()))
                    .as("имя %s по-русски осталось латиницей: %s", leader.code(), leader.name())
                    .isFalse();
            assertThat(hasLatin(leader.title()))
                    .as("звание %s по-русски осталось латиницей: %s", leader.code(), leader.title())
                    .isFalse();
        }
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("Журнал сервера зовёт лидера по-английски, каким бы ни был язык запроса")
    void logNameIgnoresRequestLanguage() {
        Leader leader = catalog.all().getFirst();
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        // Имя на экране — на языке читателя, имя в журнале — всегда английское: у журнала
        // читателя с языком нет вовсе, и запись, меняющая язык от запроса, не ищется грепом.
        assertThat(leader.name()).isNotEqualTo(leader.logName());
        assertThat(hasLatin(leader.logName())).isTrue();
        LocaleContextHolder.resetLocaleContext();
    }
}
