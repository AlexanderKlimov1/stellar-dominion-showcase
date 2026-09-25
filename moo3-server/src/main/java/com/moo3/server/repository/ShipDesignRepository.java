package com.moo3.server.repository;

import com.moo3.server.domain.entity.ShipDesignEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Проекты кораблей игроков — п. 8. */
public interface ShipDesignRepository extends JpaRepository<ShipDesignEntity, UUID> {

    /** Действующие проекты игрока по ячейкам: по ним и строят колонии. */
    List<ShipDesignEntity> findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(UUID ownerPlayerId);

    /** Действующие проекты сразу нескольких игроков — их спрашивает стройка колоний. */
    List<ShipDesignEntity> findAllByOwnerPlayerIdInAndObsoleteFalseOrderBySlotAsc(Collection<UUID> ownerPlayerIds);

    /** Все проекты игрока, включая вытесненные: по ним летают старые корабли. */
    /**
     * Порядок задан явно: ход читает эти строки и принимает по ним решения «первый
     * подходящий», а база отдаёт строки в том порядке, в каком они лежат, — и порядок
     * этот меняется от правок. Та же партия с тем же зерном обязана повторяться
     * (balance-metrics-works.txt, этап 0).
     */
    List<ShipDesignEntity> findAllByOwnerPlayerIdOrderBySlotAsc(UUID ownerPlayerId);

    /**
     * Порядок задан явно: ход читает эти строки и принимает по ним решения «первый
     * подходящий», а база отдаёт строки в том порядке, в каком они лежат, — и порядок
     * этот меняется от правок. Та же партия с тем же зерном обязана повторяться
     * (balance-metrics-works.txt, этап 0).
     */
    /**
     * Порядок задан явно и НЕ ПО ИДЕНТИФИКАТОРУ — п. 8, этап 0 балансировки.
     * <p>
     * Идентификатор проекта это случайный UUID, и порядок по нему между двумя прогонами
     * одной партии разный. Список проектов читает ход (стройка колонии, сила флота,
     * гарнизон), и решения в нём сплошь «первый подходящий». Ключ игровой: ячейка, потом
     * ход создания — вытесненные проекты остаются в списке и делят ячейку с новыми (см.
     * {@code ShipDesignService.replaceAutoDesign}).
     */
    List<ShipDesignEntity> findAllByGameId(UUID gameId);

    Optional<ShipDesignEntity> findByOwnerPlayerIdAndSlotAndObsoleteFalse(UUID ownerPlayerId, Integer slot);
}
