package com.moo3.server.repository;

import com.moo3.server.domain.entity.SpyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Шпионы империй — п. 13. */
public interface SpyRepository extends JpaRepository<SpyEntity, UUID> {

    List<SpyEntity> findAllByOwnerPlayerId(UUID ownerPlayerId);

    /**
     * Порядок задан явно: ход читает эти строки и решает по ним «первый подходящий», а
     * база отдаёт их в том порядке, в каком они лежат, и порядок этот меняется от правок.
     * Та же партия с тем же зерном обязана повторяться (balance-metrics-works.txt, этап 0).
     */
    /**
     * Порядок задан явно и НЕ ПО ИДЕНТИФИКАТОРУ — п. 13, этап 0 балансировки.
     * <p>
     * Идентификатор — случайный UUID, который Hibernate выдаёт при записи строки: порядок
     * по нему устойчив внутри прогона и РАЗНЫЙ между двумя прогонами одной партии, потому
     * что во второй раз выпадут другие идентификаторы. Агенты действуют по очереди, и от
     * очереди зависит, чью технологию украдут первой, — а значит и вся дальнейшая партия.
     * Ключ здесь игровой: ход найма, потом задание.
     */
    List<SpyEntity> findAllByOwnerPlayerIdIn(Collection<UUID> ownerPlayerIds);

    List<SpyEntity> findAllByTargetPlayerId(UUID targetPlayerId);
}
