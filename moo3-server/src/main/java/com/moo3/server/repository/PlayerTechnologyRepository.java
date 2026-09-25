package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlayerTechnologyRepository extends JpaRepository<PlayerTechnologyEntity, UUID> {

    List<PlayerTechnologyEntity> findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(UUID playerId);

    List<PlayerTechnologyEntity> findAllByPlayerIdIn(Collection<UUID> playerIds);
}
