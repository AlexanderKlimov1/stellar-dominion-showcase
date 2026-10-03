package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.BattleShipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Корабли на поле боя — п. 8. */
public interface BattleShipRepository extends JpaRepository<BattleShipEntity, UUID> {

    /** Весь бой одной выборкой: очередь хода строится по всем кораблям сразу. */
    List<BattleShipEntity> findAllByBattleIdOrderByInitiativeDescOrdinalAsc(UUID battleId);
}
