package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.PopulationTransferEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Рейсы с жителями — п. 4.1.1: пока они в пути, их грузовики заняты. */
public interface PopulationTransferRepository extends JpaRepository<PopulationTransferEntity, UUID> {

    List<PopulationTransferEntity> findAllByGameId(UUID gameId);

    /**
     * Рейсы игроков одной выборкой: их спрашивают счётчик занятых грузовиков и экран.
     * <p>
     * {@code ORDER BY id} здесь — устойчивость выборки, а не порядок правил: идентификатор
     * строки это случайный UUID. Решениям хода нужен порядок, выведенный из зерна партии, —
     * см. {@link com.sddnw.server.service.GameOrder#transfers}, им сортирует прибытие
     * {@code PopulationTransferService.arrive}.
     */
    List<PopulationTransferEntity> findAllByOwnerPlayerIdInOrderByIdAsc(Collection<UUID> ownerPlayerIds);
}
