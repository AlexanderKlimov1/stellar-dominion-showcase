package com.moo3.server.repository;

import com.moo3.server.domain.entity.TurnReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Последний отчёт хода — строка на игрока, переписывается каждый ход. */
public interface TurnReportRepository extends JpaRepository<TurnReportEntity, UUID> {

    Optional<TurnReportEntity> findByPlayerId(UUID playerId);
}
