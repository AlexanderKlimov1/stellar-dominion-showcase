package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.BalanceRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Балансовые прогоны пульта администратора — этап 2 плана. */
public interface BalanceRunRepository extends JpaRepository<BalanceRunEntity, UUID> {

    /** Свежие сверху: пульт показывает последние прогоны. */
    List<BalanceRunEntity> findAllByOrderByCreatedAtDesc();

    List<BalanceRunEntity> findAllByStatus(String status);

    /** Прогоны этого экземпляра, которые ещё не кончились: им и нужен пульс. */
    List<BalanceRunEntity> findAllByOwnerInstanceAndStatusIn(String ownerInstance,
                                                             java.util.Collection<String> statuses);
}
