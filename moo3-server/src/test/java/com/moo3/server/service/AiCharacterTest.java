package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.domain.enums.AiObjective;
import com.moo3.server.domain.enums.AiPersonality;
import com.moo3.server.dto.ResearchCategoryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Характер и устремление правителя ИИ — п. 15.
 * <p>
 * Таблицы решают всё поведение соседа: порог войны, чувствительность доверия, любимые
 * разделы науки. Опечатка в числе не падает и не видна на экране — она просто делает
 * миролюбивого агрессором, поэтому направления из описаний MOO II проверены здесь.
 */
class AiCharacterTest {

    @Test
    @DisplayName("Порог войны падает от миролюбивого к беспощадному")
    void warThresholdOrder() {
        assertTrue(AiPersonality.RUTHLESS.getWarAdvantagePercent() < 100,
                "беспощадный нападает, даже уступая в силе");
        assertTrue(AiPersonality.AGGRESSIVE.getWarAdvantagePercent()
                        < AiPersonality.ERRATIC.getWarAdvantagePercent(),
                "агрессивному довольно небольшого перевеса");
        assertTrue(AiPersonality.HONORABLE.getWarAdvantagePercent()
                        < AiPersonality.PACIFISTIC.getWarAdvantagePercent(),
                "миролюбивый воюет реже всех");
    }

    @Test
    @DisplayName("Ксенофоб вдвое слабее принимает хорошее и вдвое сильнее плохое")
    void xenophobeTakesEverythingBadly() {
        assertEquals(50, AiPersonality.XENOPHOBIC.getGainPercent());
        assertEquals(200, AiPersonality.XENOPHOBIC.getLossPercent());
    }

    @Test
    @DisplayName("Благородный вдвое сильнее отзывается на подлости, но хорошее принимает как все")
    void honourableRemembersOffences() {
        assertEquals(100, AiPersonality.HONORABLE.getGainPercent());
        assertEquals(200, AiPersonality.HONORABLE.getLossPercent());
    }

    @Test
    @DisplayName("Друзей щадит благородный, мира ищет миролюбивый — и только они")
    void singularHabits() {
        assertEquals(1, Arrays.stream(AiPersonality.values())
                .filter(p -> Boolean.TRUE.equals(p.sparesFriends())).count());
        assertTrue(AiPersonality.HONORABLE.sparesFriends());
        assertEquals(1, Arrays.stream(AiPersonality.values())
                .filter(p -> Boolean.TRUE.equals(p.seeksPeace())).count());
        assertTrue(AiPersonality.PACIFISTIC.seeksPeace());
    }

    @Test
    @DisplayName("Доверие и порог войны заданы у каждого характера, названия — по-русски")
    void everyPersonalityIsFilled() {
        for (AiPersonality personality : AiPersonality.values()) {
            assertNotNull(personality.getLabel(), personality.name());
            assertTrue(personality.getGainPercent() > 0, personality.name());
            assertTrue(personality.getLossPercent() > 0, personality.name());
            assertTrue(personality.getWarAdvantagePercent() > 0, personality.name());
        }
    }

    @Test
    @DisplayName("У каждого устремления три любимых раздела науки, и все они разные")
    void objectivesPickThreeCategories() {
        for (AiObjective objective : AiObjective.values()) {
            assertEquals(3, objective.getFavouriteCategories().size(), objective.name());
            assertEquals(3, objective.getFavouriteCategories().stream().distinct().count(),
                    objective.name() + ": раздел повторён");
            assertNotNull(objective.getLabel(), objective.name());
        }
    }

    @Test
    @DisplayName("Любимые разделы устремлений — настоящие разделы дерева технологий")
    void objectiveCategoriesExist() {
        ResearchCatalog catalog = new ResearchCatalog(
                new GameProperties(8, 4, 1.5, "star-names.txt",
                        "../resources/Technologies/tech.json",
                        "../resources/Buildings/buildings.json",
                        "../resources/Races/race-traits.json",
                        "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
                new ObjectMapper());
        Set<String> codes = catalog.tree().categories().stream()
                .map(ResearchCategoryDto::code)
                .collect(Collectors.toSet());

        for (AiObjective objective : AiObjective.values()) {
            assertTrue(codes.containsAll(objective.getFavouriteCategories()),
                    objective.name() + ": разделов " + objective.getFavouriteCategories()
                            + " в дереве нет, и устремление ничего не выбирает");
        }
    }

    @Test
    @DisplayName("Переговоры любит дипломат — и только он")
    void diplomatLovesTalking() {
        assertEquals(1, Arrays.stream(AiObjective.values())
                .filter(o -> Boolean.TRUE.equals(o.favoursDiplomacy())).count());
        assertTrue(AiObjective.DIPLOMAT.favoursDiplomacy());
    }
}
