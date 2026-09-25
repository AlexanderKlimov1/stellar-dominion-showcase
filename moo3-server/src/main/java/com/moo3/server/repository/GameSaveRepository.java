package com.moo3.server.repository;

import com.moo3.server.domain.entity.GameSaveEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Сохранённые партии: свежие вперёд. */
public interface GameSaveRepository extends JpaRepository<GameSaveEntity, UUID> {

    /**
     * Список для диалога загрузки — проекцией и страницей.
     * <p>
     * Проекция потому, что слепок партии весит сотню килобайт и списку не нужен;
     * страница — потому что сохранений со временем становятся сотни, а показать нужно
     * последние.
     */
    List<GameSaveSummary> findAllByOrderBySavedAtDesc(Pageable pageable);

    /** Сохранения удалённой партии: без партии они никому не нужны. */
    void deleteByGameId(UUID gameId);
}
