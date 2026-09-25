package com.moo3.server.repository;

import com.moo3.server.domain.entity.PopulationTransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Рейсы с жителями — п. 4.1.1: пока они в пути, их грузовики заняты. */
public interface PopulationTransferRepository extends JpaRepository<PopulationTransferEntity, UUID> {

    List<PopulationTransferEntity> findAllByGameId(UUID gameId);

    /**
     * Порядок задан явно: ход читает эти строки и решает по ним «первый подходящий», а
     * база отдаёт их в том порядке, в каком они лежат, и порядок этот меняется от правок.
     * Та же партия с тем же зерном обязана повторяться (balance-metrics-works.txt, этап 0).
     */
    List<PopulationTransferEntity> findAllByOwnerPlayerIdInOrderByIdAsc(Collection<UUID> ownerPlayerIds);
}
