package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlayerLeaderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Служба лидеров у игроков — п. 6. */
public interface PlayerLeaderRepository extends JpaRepository<PlayerLeaderEntity, UUID> {

    List<PlayerLeaderEntity> findAllByPlayerId(UUID playerId);

    /**
     * Лидеры всех игроков партии — одной выборкой.
     * <p>
     * Фаза конца хода трогает лидеров каждого игрока, а запрос «на игрока» внутри
     * посчитанного хода стоит дороже, чем кажется: каждый сброс Hibernate тянет за собой
     * всё, что ход успел изменить (см. «Грабли» в CLAUDE.md).
     */
    List<PlayerLeaderEntity> findAllByPlayerIdIn(Collection<UUID> playerIds);
}
