package com.moo3.server.service;

import com.moo3.server.domain.BuildingEffects;
import com.moo3.server.domain.RaceEffects;
import com.moo3.server.domain.enums.RaceEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Правила наземного боя — п. 12. */
class GroundCombatServiceTest {

    private final GroundCombatService service = new GroundCombatService(null, null, null, null, null, null, null, new AssimilationRules(), null, null, new OrbitalDefenceRules());

    private static RaceEffects race(int groundCombatPercent) {
        return new RaceEffects(
                BuildingEffects.NONE,
                Map.of(RaceEffectType.GROUND_COMBAT_PERCENT, groundCombatPercent));
    }

    @Test
    @DisplayName("Оружие, снаряжение и казармы прибавляются к силе одной строкой")
    void strengthCountsTechnologiesAndBarracks() {
        // Пять бойцов — база 50. Винтовка-плазма (+30 %) и великие бойцы (+20 %) дают
        // ровно полтора: надбавки складываются к одной базе, а не множатся друг на друга.
        assertThat(service.strength(5, race(20), 0, Boolean.FALSE, 30)).isEqualTo(75);
        // У обороняющегося к тому же прибавляются казармы, и приходят они той же строкой.
        assertThat(service.strength(5, RaceEffects.NONE, 0, Boolean.TRUE, 50)).isEqualTo(75);
    }

    @Test
    @DisplayName("Казармы решают бой, который без них проигран")
    void barracksTurnTheBattle() {
        // Четверо против четверых: без казарм колония падает (нападающий сильнее не будет,
        // но и обороняющийся не сильнее — при равенстве побеждает оборона), а с оружием
        // нападающего падает наверняка.
        GroundCombatService.Outcome without = service.resolve(
                5, RaceEffects.NONE, 0, 0, 4, RaceEffects.NONE, 0, 0);
        assertThat(without.captured()).isTrue();

        // Те же силы, но у колонии казармы морской пехоты (+50 %) — десант отбит.
        GroundCombatService.Outcome with = service.resolve(
                5, RaceEffects.NONE, 0, 0, 4, RaceEffects.NONE, 0, 50);
        assertThat(with.captured()).isFalse();
    }

    @Test
    @DisplayName("Сила десанта: десять за жителя, поднятые расовой подготовкой")
    void strengthCountsRaceBonus() {
        assertThat(service.strength(5, RaceEffects.NONE)).isEqualTo(50);
        assertThat(service.strength(5, race(20))).isEqualTo(60);
        assertThat(service.strength(5, race(-10))).isEqualTo(45);
    }

    @Test
    @DisplayName("Лидер прибавляет к десанту так же, как раса, и складывается с ней — п. 6")
    void leaderAddsToStrength() {
        assertThat(service.strength(5, RaceEffects.NONE, 20)).isEqualTo(60);
        assertThat(service.strength(5, race(20), 20)).isEqualTo(70);
    }

    @Test
    @DisplayName("Офицер обороны переламывает равный бой — п. 6")
    void defenderLeaderTurnsTheBattle() {
        GroundCombatService.Outcome outcome = service.resolve(
                4, RaceEffects.NONE, 0, 4, RaceEffects.NONE, 50);

        assertThat(outcome.captured()).isFalse();
        assertThat(outcome.defencePower()).isGreaterThan(outcome.attackPower());
    }

    @Test
    @DisplayName("Равные силы: колония отбивается, десант гибнет весь")
    void equalForcesDefend() {
        GroundCombatService.Outcome outcome = service.resolve(4, RaceEffects.NONE, 4, RaceEffects.NONE);

        assertThat(outcome.captured()).isFalse();
        assertThat(outcome.attackPower()).isEqualTo(outcome.defencePower());
        // Защитники потеряли всех, кроме одного: колония выстояла, но обезлюдела.
        assertThat(outcome.survivors()).isEqualTo(1);
    }

    @Test
    @DisplayName("Вдвое сильнейший десант берёт колонию и теряет половину")
    void twiceStrongerCaptures() {
        GroundCombatService.Outcome outcome = service.resolve(8, RaceEffects.NONE, 4, RaceEffects.NONE);

        assertThat(outcome.captured()).isTrue();
        assertThat(outcome.survivors()).isEqualTo(4);
    }

    @Test
    @DisplayName("Подготовка расы решает бой равных числом")
    void raceBonusDecidesEvenFight() {
        GroundCombatService.Outcome outcome = service.resolve(4, race(20), 4, RaceEffects.NONE);

        assertThat(outcome.captured()).isTrue();
        assertThat(outcome.attackPower()).isEqualTo(48);
        assertThat(outcome.defencePower()).isEqualTo(40);
        // Из четырёх высадившихся уцелел один: перевес небольшой, потери тяжёлые.
        assertThat(outcome.survivors()).isEqualTo(1);
    }

    @Test
    @DisplayName("Слабый десант гибнет, у колонии остаются жители")
    void weakInvasionRepelled() {
        GroundCombatService.Outcome outcome = service.resolve(2, RaceEffects.NONE, 8, RaceEffects.NONE);

        assertThat(outcome.captured()).isFalse();
        assertThat(outcome.survivors()).isEqualTo(6);
    }
}
