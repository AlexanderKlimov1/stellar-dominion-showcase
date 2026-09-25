package com.moo3.server.repository;

import com.moo3.server.domain.entity.StarSystemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface StarSystemRepository extends JpaRepository<StarSystemEntity, UUID> {

    List<StarSystemEntity> findAllByGameIdOrderByNameAsc(UUID gameId);

    @Query("""
            SELECT DISTINCT s FROM StarSystemEntity s
            LEFT JOIN FETCH s.planets
            WHERE s.game.id = :gameId
            """)
    List<StarSystemEntity> findAllByGameIdWithPlanets(UUID gameId);

    Integer countByGameId(UUID gameId);
}
