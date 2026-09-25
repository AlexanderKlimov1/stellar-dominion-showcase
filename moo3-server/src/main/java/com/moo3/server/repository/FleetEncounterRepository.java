package com.moo3.server.repository;

import com.moo3.server.domain.entity.FleetEncounterEntity;
import com.moo3.server.domain.enums.EncounterState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Встречи флотов — п. 8. */
public interface FleetEncounterRepository extends JpaRepository<FleetEncounterEntity, UUID> {

    /**
     * Встречи партии в этих состояниях, по времени появления.
     * <p>
     * Порядок задан явно: решения по встречам разбираются подряд, и база, отдающая строки
     * как ей удобно, делала бы ту же партию с тем же зерном непохожей на саму себя —
     * парные прогоны балансировки на этом и держатся.
     */
    List<FleetEncounterEntity> findAllByGameIdAndStateInOrderByTurnAscIdAsc(
            UUID gameId, Collection<EncounterState> states);

    /** Незакрытая встреча тех же двоих в той же системе: второй раз её заводить незачем. */
    List<FleetEncounterEntity> findAllByGameIdAndStarSystemIdAndStateIn(
            UUID gameId, UUID starSystemId, Collection<EncounterState> states);
}
