package com.moo3.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Что колония выставляет в бой — п. 8, п. 11.
 * <p>
 * Проверяются правила, которые легко сломать молча: лестница замены на орбите, набор на
 * поверхности, запрет десанта барьером и — главное — то, что у КАЖДОГО корпуса платформы
 * есть своя служебная ячейка. Последнее уже ломалось: добавив планетарный щит, я забыл
 * дать ему ячейку, и первая же партия падала пятисотым ответом на старте. Юнит-тест это
 * ловит за секунду, живой прогон — за минуту.
 */
class OrbitalDefenceRulesTest {

    private final OrbitalDefenceRules rules = new OrbitalDefenceRules();

    @Test
    @DisplayName("У каждого корпуса платформы своя ячейка, и все они служебные")
    void everyPlatformHasItsOwnSlot() {
        List<Integer> slots = OrbitalDefenceRules.HULLS.stream().map(rules::slotOf).toList();
        assertThat(slots).doesNotHaveDuplicates();
        // Отрицательные: шесть ячеек заняты игроком, нулевая — гражданскими кораблями,
        // а окно дизайна показывает ячейки с первой.
        assertThat(slots).allMatch(slot -> slot < 0);
    }

    @Test
    @DisplayName("На орбите одна платформа — лучшая из построенных")
    void orbitIsALadder() {
        assertThat(rules.platformsOf(Set.of("star-base"))).containsExactly("star-base");
        // Станция заменяет базу, крепость — обе: «Battlestations replace Star Bases and
        // Star Fortresses replace Star Bases and Battlestations».
        assertThat(rules.platformsOf(Set.of("star-base", "battle-station")))
                .containsExactly("battle-station");
        assertThat(rules.platformsOf(Set.of("star-base", "battle-station", "star-fortress")))
                .containsExactly("star-fortress");
    }

    @Test
    @DisplayName("На поверхности набор: каждое здание выставляет свою батарею")
    void surfaceIsASet() {
        assertThat(rules.platformsOf(Set.of("missile-base", "ground-batteries", "fighter-garrison")))
                .containsExactly("planetary-battery", "planetary-battery", "planetary-battery");
    }

    @Test
    @DisplayName("Щит выходит в бой один, каким бы ни был")
    void shieldIsOne() {
        assertThat(rules.platformsOf(Set.of("planetary-flux-shield")))
                .containsExactly("planetary-shield");
        assertThat(rules.platformsOf(Set.of("planetary-flux-shield", "planetary-barrier-shield")))
                .containsExactly("planetary-shield");
    }

    @Test
    @DisplayName("Барьер не пускает десант, простое поле — пускает")
    void onlyTheBarrierBlocksLanding() {
        // Правило оригинала прямое: «As long as the barrier shield is in place, neither
        // ground Marines nor biological weapons can enter the planet's atmosphere».
        assertThat(rules.blocksLanding(Set.of("planetary-barrier-shield"))).isTrue();
        assertThat(rules.blocksLanding(Set.of("planetary-flux-shield"))).isFalse();
        assertThat(rules.blocksLanding(Set.of("star-fortress", "missile-base"))).isFalse();
    }

    @Test
    @DisplayName("Беззащитная колония в бой не выставляет ничего")
    void nothingToDefendWith() {
        assertThat(rules.platformsOf(Set.of("automated-factory", "marine-barracks"))).isEmpty();
        assertThat(rules.platformsOf(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("Сеть Артемиды платформой не бывает: она бьёт на подлёте")
    void theNetIsNotAPlatform() {
        assertThat(rules.platformsOf(Set.of(OrbitalDefenceRules.ARTEMIS_NET))).isEmpty();
        assertThat(OrbitalDefenceRules.HULLS).doesNotContain(OrbitalDefenceRules.ARTEMIS_NET);
    }
}
