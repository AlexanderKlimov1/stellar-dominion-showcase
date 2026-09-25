package com.moo3.server.domain.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.config.GameProperties;
import com.moo3.server.dto.ResearchOptionDto;
import com.moo3.server.service.ResearchCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Технологии наземного боя — п. 12.
 * <p>
 * Проверяется не «числа те, что записаны» — это тавтология, — а три правила, в которых
 * легко ошибиться молча: винтовка и броня не складываются сами с собой, снаряжение
 * складывается, а батлоиды помогают нападению вдвое сильнее, чем обороне. И главное:
 * каждый код должен находиться в дереве технологий, иначе надбавка не придёт никогда и
 * никто этого не заметит.
 */
class GroundCombatTechTest {

    private static GameProperties properties() {
        return new GameProperties(8, 4, 1.5, "star-names.txt",
                "../resources/Technologies/tech.json",
                "../resources/Buildings/buildings.json",
                "../resources/Races/race-traits.json",
                "../resources/Ships/ship-components.json",
                "../resources/Leaders/leaders.json");
    }

    @Test
    @DisplayName("Каждая технология наземного боя есть в дереве")
    void everyTechIsInTheTree() {
        Set<String> tree = new ResearchCatalog(properties(), new ObjectMapper()).tree().categories()
                .stream()
                .flatMap(category -> category.levels().stream())
                .flatMap(level -> level.options().stream())
                .map(ResearchOptionDto::code)
                .collect(Collectors.toSet());

        assertThat(Arrays.stream(GroundCombatTech.values()).map(GroundCombatTech::getCode))
                .allSatisfy(code -> assertThat(tree)
                        .withFailMessage("технологии «%s» нет в дереве — надбавка не придёт никогда", code)
                        .contains(code));
    }

    @Test
    @DisplayName("Винтовка и броня берутся лучшие, а не складываются")
    void weaponAndArmourTakeTheBest() {
        // Две винтовки сразу у бойца не бывает: в оригинале новая ступень заменяет прежнюю.
        assertThat(GroundCombatTech.attackPercent(Set.of("laser-rifle", "plasma-rifle")))
                .isEqualTo(30);
        assertThat(GroundCombatTech.attackPercent(Set.of("tritanium-armor", "adamantium-armor")))
                .isEqualTo(25);
        // А винтовка с бронёй — складываются: это разные вещи.
        assertThat(GroundCombatTech.attackPercent(Set.of("plasma-rifle", "adamantium-armor")))
                .isEqualTo(55);
    }

    @Test
    @DisplayName("Лестницы винтовок и брони идут по возрастанию")
    void laddersAscend() {
        assertThat(GroundCombatTech.attackPercent(Set.of("laser-rifle"))).isEqualTo(5);
        assertThat(GroundCombatTech.attackPercent(Set.of("fusion-rifle"))).isEqualTo(10);
        assertThat(GroundCombatTech.attackPercent(Set.of("phasor-rifle"))).isEqualTo(20);
        assertThat(GroundCombatTech.attackPercent(Set.of("plasma-rifle"))).isEqualTo(30);

        assertThat(GroundCombatTech.attackPercent(Set.of("tritanium-armor"))).isEqualTo(10);
        assertThat(GroundCombatTech.attackPercent(Set.of("zortrium-armor"))).isEqualTo(15);
        assertThat(GroundCombatTech.attackPercent(Set.of("neutronium-armor"))).isEqualTo(20);
        assertThat(GroundCombatTech.attackPercent(Set.of("adamantium-armor"))).isEqualTo(25);
    }

    @Test
    @DisplayName("Снаряжение складывается, а батлоиды лучше нападают, чем обороняются")
    void gearAddsUpAndArmourFavoursTheAttacker() {
        assertThat(GroundCombatTech.attackPercent(Set.of("powered-armor", "personal-shield")))
                .isEqualTo(20);

        // Асимметрия оригинала: бронеединице при нападении прибавляют вдвое больше, чем
        // при обороне. Всё прочее в обе стороны работает одинаково.
        assertThat(GroundCombatTech.attackPercent(Set.of("battleoids"))).isEqualTo(100);
        assertThat(GroundCombatTech.defencePercent(Set.of("battleoids"))).isEqualTo(50);
        assertThat(GroundCombatTech.attackPercent(Set.of("plasma-rifle")))
                .isEqualTo(GroundCombatTech.defencePercent(Set.of("plasma-rifle")));
    }

    @Test
    @DisplayName("Без изученного надбавки нет")
    void nothingResearchedNothingAdded() {
        assertThat(GroundCombatTech.attackPercent(Set.of())).isZero();
        assertThat(GroundCombatTech.defencePercent(Set.of("automated-factory"))).isZero();
    }
}
