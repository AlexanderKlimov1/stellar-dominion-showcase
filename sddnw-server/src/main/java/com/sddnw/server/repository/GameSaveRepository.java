package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.GameSaveEntity;
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

    /**
     * Автосохранение партии — оно одно и переписывается каждый ход (п. 3).
     * <p>
     * Возвращается списком, а не единственным значением, нарочно: строка в базе одна по
     * замыслу, а не по ограничению — межбазового уникального индекса здесь нет (см.
     * миграцию 077), и наткнуться на две строки лучше при чтении, чем на исключении.
     */
    List<GameSaveEntity> findByGameIdAndAutoIsTrue(UUID gameId);
}
