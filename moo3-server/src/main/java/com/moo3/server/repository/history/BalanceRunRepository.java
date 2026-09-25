package com.moo3.server.repository.history;

import com.moo3.server.domain.entity.history.BalanceRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Балансовые прогоны пульта администратора — этап 2 плана. */
public interface BalanceRunRepository extends JpaRepository<BalanceRunEntity, UUID> {

    /** Свежие сверху: пульт показывает последние прогоны. */
    List<BalanceRunEntity> findAllByOrderByCreatedAtDesc();

    List<BalanceRunEntity> findAllByStatus(String status);
}
