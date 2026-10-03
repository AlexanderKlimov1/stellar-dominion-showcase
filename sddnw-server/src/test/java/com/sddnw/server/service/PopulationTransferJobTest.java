package com.sddnw.server.service;

import com.sddnw.server.domain.PopulationJobs;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.ColonistJob;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PopulationTransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Перевозка помнит, КОГО везут — п. 4.1.1 (01.10.2026).
 * <p>
 * Игрок берёт жителей с полосы занятия, и правило из двух половин: с колонии уходят именно
 * взятые, а на новом месте они встают на то же дело — кроме фермеров там, где еду не растят:
 * те встают на производство. В живой партии вторую половину увидеть — дело случая (нужна
 * колония на безжизненном климате рядом с донором), поэтому она держится здесь.
 */
class PopulationTransferJobTest {

    private final PopulationTransferRepository transfers = Mockito.mock(PopulationTransferRepository.class);
    private final PlanetRepository planets = Mockito.mock(PlanetRepository.class);
    private final ColonyService colonyService = Mockito.mock(ColonyService.class);
    private final ColonyService.ColonyContext context = Mockito.mock(ColonyService.ColonyContext.class);
    private final PopulationTransferService service = new PopulationTransferService(
            transfers, planets, colonyService, new PopulationCalculator(), Mockito.mock(PlayerEventService.class));

    private final GameEntity game = new GameEntity();
    private final PlayerEntity player = new PlayerEntity();
    private final PlanetEntity from = new PlanetEntity();
    private final PlanetEntity to = new PlanetEntity();

    PopulationTransferJobTest() {
        game.setId(UUID.randomUUID());
        game.setTurn(5);
        player.setId(UUID.randomUUID());
        player.setName("Перевозчик");
        StarSystemEntity system = new StarSystemEntity();
        system.setId(UUID.randomUUID());
        colony(from, "Донор", system, new PopulationJobs(3, 2, 1));
        colony(to, "Пустошь", system, new PopulationJobs(0, 2, 0));

        when(planets.findById(from.getId())).thenReturn(Optional.of(from));
        when(planets.findById(to.getId())).thenReturn(Optional.of(to));
        // Распределение колонии — как записано: население в проверке всегда ему равно.
        when(colonyService.jobs(any())).thenAnswer(call -> ((PlanetEntity) call.getArgument(0)).getJobs());
        when(colonyService.context(to)).thenReturn(context);
        when(colonyService.maxPopulation(eq(to), any(), any())).thenReturn(20);
    }

    private void colony(PlanetEntity planet, String name, StarSystemEntity system, PopulationJobs jobs) {
        planet.setId(UUID.randomUUID());
        planet.setName(name);
        planet.setOwnerPlayerId(player.getId());
        planet.setStarSystem(system);
        planet.setPopulation(jobs.total());
        planet.setJobs(jobs);
    }

    @Test
    @DisplayName("Фермеры туда, где еду не растят, встают на производство; с донора уходят именно фермеры")
    void farmersBecomeWorkersWhereNothingGrows() {
        when(colonyService.canFarm(eq(to), any())).thenReturn(Boolean.FALSE);

        service.send(game, player, from.getId(), to.getId(), 2, ColonistJob.FARMERS, ColonistJob.FARMERS);

        assertThat(from.getJobs()).isEqualTo(new PopulationJobs(1, 2, 1));
        assertThat(to.getJobs()).isEqualTo(new PopulationJobs(0, 4, 0));
    }

    @Test
    @DisplayName("Где еду растят, прибывшие фермеры остаются фермерами")
    void farmersStayFarmersWhereFoodGrows() {
        when(colonyService.canFarm(eq(to), any())).thenReturn(Boolean.TRUE);

        service.send(game, player, from.getId(), to.getId(), 2, ColonistJob.FARMERS, ColonistJob.FARMERS);

        assertThat(to.getJobs()).isEqualTo(new PopulationJobs(2, 2, 0));
    }

    @Test
    @DisplayName("Учёные уезжают учёными и встают учёными")
    void scientistsKeepTheirJob() {
        service.send(game, player, from.getId(), to.getId(), 1, ColonistJob.SCIENTISTS, ColonistJob.SCIENTISTS);

        assertThat(from.getJobs()).isEqualTo(new PopulationJobs(3, 2, 0));
        assertThat(to.getJobs()).isEqualTo(new PopulationJobs(0, 2, 1));
    }
}
