package com.moo3.server.repository;

import com.moo3.server.domain.entity.SpaceBattleEntity;
import com.moo3.server.domain.enums.BattleState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Тактические бои партии — п. 8. */
public interface SpaceBattleRepository extends JpaRepository<SpaceBattleEntity, UUID> {

    List<SpaceBattleEntity> findAllByGameIdAndState(UUID gameId, BattleState state);

    /** Все бои партии — этап 1 балансировки: по ним видно, воевала ли империя вообще. */
    List<SpaceBattleEntity> findAllByGameIdOrderByTurnAsc(UUID gameId);

    Optional<SpaceBattleEntity> findByEncounterId(UUID encounterId);
}
