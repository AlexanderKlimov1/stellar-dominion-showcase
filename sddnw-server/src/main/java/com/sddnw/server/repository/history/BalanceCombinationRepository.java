package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.BalanceCombinationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BalanceCombinationRepository extends JpaRepository<BalanceCombinationEntity, UUID> {

    /** Свежие сверху — так их и показывает пульт. */
    List<BalanceCombinationEntity> findAllByOrderByCreatedAtDesc();
}
