package com.moo3.server.repository;

import com.moo3.server.domain.entity.FleetShipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Состав флотов по проектам кораблей — п. 8. */
public interface FleetShipRepository extends JpaRepository<FleetShipEntity, UUID> {

    /**
     * Порядок задан явно: ход читает эти строки и принимает по ним решения «первый
     * подходящий», а база отдаёт строки в том порядке, в каком они лежат, — и порядок
     * этот меняется от правок. Та же партия с тем же зерном обязана повторяться
     * (balance-metrics-works.txt, этап 0).
     */
    List<FleetShipEntity> findAllByFleetIdOrderByIdAsc(UUID fleetId);

    /** Состав сразу нескольких флотов: экран флота и фаза встреч читают их пачкой. */
    List<FleetShipEntity> findAllByFleetIdIn(Collection<UUID> fleetIds);

    Optional<FleetShipEntity> findByFleetIdAndDesignId(UUID fleetId, UUID designId);

    void deleteAllByFleetId(UUID fleetId);
}
