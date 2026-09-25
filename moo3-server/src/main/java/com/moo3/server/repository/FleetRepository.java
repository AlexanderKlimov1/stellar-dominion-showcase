package com.moo3.server.repository;

import com.moo3.server.domain.entity.FleetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Флоты партии — п. 8. */
public interface FleetRepository extends JpaRepository<FleetEntity, UUID> {

    List<FleetEntity> findAllByGameId(UUID gameId);

    List<FleetEntity> findAllByOwnerPlayerId(UUID ownerPlayerId);

    /**
     * Стоящий флот игрока в системе: он один — корабли встают в общий строй.
     * <p>
     * Летящие в выборку не попадают: у флота в пути {@code star_system_id} остаётся системой
     * вылета, и без этого условия «флот игрока в системе» перестал бы быть одним — п. 8.
     */
    Optional<FleetEntity> findByOwnerPlayerIdAndStarSystemIdAndTargetSystemIdIsNull(UUID ownerPlayerId,
                                                                                    UUID starSystemId);

    /** Флоты партии, которые сейчас в пути, — их ведёт фаза прибытия (п. 8). */
    List<FleetEntity> findAllByGameIdAndTargetSystemIdIsNotNull(UUID gameId);
}
