package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlayerEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Очередь событий игрока до ближайших итогов хода — п. 11.1. */
public interface PlayerEventRepository extends JpaRepository<PlayerEventEntity, UUID> {

    /** Всё накопленное по партии: собирается в отчёт хода и тут же удаляется. */
    List<PlayerEventEntity> findAllByGameIdOrderByTurnAsc(UUID gameId);
}
