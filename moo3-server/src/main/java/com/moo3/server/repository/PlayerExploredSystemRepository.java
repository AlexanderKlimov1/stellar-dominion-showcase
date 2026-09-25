package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlayerExploredSystemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Разведанные игроками системы — п. 15. */
public interface PlayerExploredSystemRepository extends JpaRepository<PlayerExploredSystemEntity, UUID> {

    List<PlayerExploredSystemEntity> findAllByPlayerId(UUID playerId);

    /**
     * Разведанное сразу многими игроками — одной выборкой.
     * <p>
     * Нужна фазе ИИ: она спрашивает разведку каждой империи каждый ход, а запрос «на
     * игрока» изнутри посчитанного хода заставляет Hibernate сбрасывать в базу всё, что
     * ход успел изменить, — это записано в граблях проекта отдельным правилом.
     */
    List<PlayerExploredSystemEntity> findAllByPlayerIdIn(java.util.Collection<UUID> playerIds);

    Boolean existsByPlayerIdAndStarSystemId(UUID playerId, UUID starSystemId);
}
