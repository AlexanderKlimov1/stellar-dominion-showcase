package com.sddnw.server.service;

import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Знакомство по дальности, как в MOO II, — п. 15, backlog-promo, пункт 5.
 * <p>
 * Три правила оригинала, и каждое здесь: знакомит и колония, и ЗАСТАВА; знакомство
 * ТЕРЯЕТСЯ, когда пала последняя занятая система в пределах дальности; потерянное
 * ВОЗВРАЩАЕТСЯ тем же путём — а война, договоры и доверие переживают паузу.
 * Дальность без топливных технологий — четыре парсека (стандартные элементы).
 */
class ContactByRangeTest {

    private final List<DiplomacyRelationEntity> stored = new ArrayList<>();
    private final DiplomacyRelationRepository relations = Mockito.mock(DiplomacyRelationRepository.class);
    private final PlayerTechnologyRepository technologies = Mockito.mock(PlayerTechnologyRepository.class);
    private final DiplomacyService diplomacy = new DiplomacyService(null, relations, null, null, null,
            null, technologies, null, null, null);

    private final PlayerEntity first = player(1);
    private final PlayerEntity second = player(2);
    private final GameEntity game = new GameEntity();

    @SuppressWarnings("unchecked")
    ContactByRangeTest() {
        game.setId(UUID.randomUUID());
        game.setTurn(5);
        when(technologies.findAllByPlayerIdIn(anyCollection())).thenReturn(List.of());
        when(relations.findAllByPlayerIdIn(anyCollection())).thenAnswer(call ->
                stored.stream().filter(one -> ((Collection<UUID>) call.getArgument(0))
                        .contains(one.getPlayerId())).toList());
        when(relations.findByPlayerIdAndOtherPlayerId(any(), any())).thenAnswer(call ->
                stored.stream().filter(one -> one.getPlayerId().equals(call.getArgument(0))
                        && one.getOtherPlayerId().equals(call.getArgument(1))).findFirst());
        when(relations.saveAll(any())).thenAnswer(call -> {
            for (DiplomacyRelationEntity one : (Iterable<DiplomacyRelationEntity>) call.getArgument(0)) {
                if (!stored.contains(one)) {
                    stored.add(one);
                }
            }
            return call.getArgument(0);
        });
    }

    private static PlayerEntity player(int slot) {
        PlayerEntity player = new PlayerEntity();
        player.setId(UUID.randomUUID());
        player.setSlot(slot);
        player.setName("Империя " + slot);
        return player;
    }

    private static StarSystemEntity system(double x, PlayerEntity owner, int population) {
        StarSystemEntity system = new StarSystemEntity();
        system.setId(UUID.randomUUID());
        system.setName("Звезда " + x);
        system.setXParsec(x);
        system.setYParsec(0.0);
        PlanetEntity planet = new PlanetEntity();
        planet.setId(UUID.randomUUID());
        planet.setOwnerPlayerId(owner == null ? null : owner.getId());
        planet.setPopulation(population);
        system.addPlanet(planet);
        return system;
    }

    private void turn(StarSystemEntity... systems) {
        diplomacy.contactByRange(new TurnContext(game, List.of(first, second), List.of(systems),
                List.of(), null, new TurnReport(game.getTurn())));
    }

    private Optional<DiplomacyRelationEntity> relation() {
        return stored.stream().filter(one -> one.getPlayerId().equals(first.getId())).findFirst();
    }

    @Test
    @DisplayName("Колонии далеко друг от друга — знакомства нет")
    void farApartNoContact() {
        turn(system(0, first, 5), system(10, second, 5));
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("Застава в пределах дальности знакомит, как и колония")
    void outpostMakesContact() {
        turn(system(0, first, 5), system(10, second, 5), system(3, second, 0));
        assertThat(relation()).isPresent();
        assertThat(relation().get().getInContact()).isTrue();
    }

    @Test
    @DisplayName("Пала застава — знакомство на паузе, война остаётся; вернулась — знакомство тоже")
    void contactLostAndRegained() {
        StarSystemEntity home = system(0, first, 5);
        StarSystemEntity far = system(10, second, 5);
        StarSystemEntity outpost = system(3, second, 0);
        turn(home, far, outpost);
        stored.forEach(one -> one.setStance(DiplomacyStance.WAR));

        outpost.getPlanets().get(0).setOwnerPlayerId(null);
        turn(home, far, outpost);
        assertThat(stored).hasSize(2).allMatch(one -> Boolean.FALSE.equals(one.getInContact()));
        assertThat(stored).allMatch(one -> one.getStance() == DiplomacyStance.WAR);

        outpost.getPlanets().get(0).setOwnerPlayerId(second.getId());
        turn(home, far, outpost);
        assertThat(stored).hasSize(2).allMatch(one -> Boolean.TRUE.equals(one.getInContact()));
        assertThat(stored).allMatch(one -> one.getStance() == DiplomacyStance.WAR);
    }
}
