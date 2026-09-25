package com.moo3.server.repository;

import com.moo3.server.domain.entity.EmpireActivityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Счётчики действий империй — этап 1 балансировки. */
public interface EmpireActivityRepository extends JpaRepository<EmpireActivityEntity, UUID> {

    Optional<EmpireActivityEntity> findByPlayerIdAndCode(UUID playerId, String code);

    /** Все счётчики партии, по империям и кодам — порядок задан ради повторимости. */
    List<EmpireActivityEntity> findAllByGameIdOrderByPlayerIdAscCodeAsc(UUID gameId);
}
