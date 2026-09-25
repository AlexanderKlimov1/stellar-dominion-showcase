package com.moo3.server.repository;

import com.moo3.server.domain.entity.EmpireHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Летопись империй по ходам — п. 11.1, для графика окна «Инфо». */
public interface EmpireHistoryRepository extends JpaRepository<EmpireHistoryEntity, UUID> {

    /**
     * Вся летопись партии по возрастанию хода.
     * <p>
     * Экран строит линии по всем империям разом, и порядок ходов ему нужен готовым:
     * сортировать на клиенте значило бы повторять там правило, которое и так есть здесь.
     */
    List<EmpireHistoryEntity> findAllByGameIdOrderByTurnAsc(UUID gameId);
}
