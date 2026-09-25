package com.moo3.server.repository;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.enums.GameStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    /**
     * Партии с таким названием — их спрашивает только демонстрационный бой (п. 8): свою
     * прошлую партию он находит по имени и убирает, чтобы они не копились.
     */
    List<GameEntity> findAllByName(String name);

    /**
     * Список партий страницей: их в базе накапливаются сотни, а показать нужно последние.
     * Игроки к ним берутся одним запросом отдельно — иначе выходил запрос на партию.
     */
    List<GameEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<GameEntity> findAllByStatusOrderByCreatedAtDesc(GameStatus status, Pageable pageable);

    @Query("""
            SELECT g FROM GameEntity g
            LEFT JOIN FETCH g.players
            WHERE g.id = :id
            """)
    Optional<GameEntity> findByIdWithPlayers(UUID id);

    /**
     * Партия под замком на запись — для конца хода (п. 11.1).
     * <p>
     * Ход пересчитывает всю галактику партии, и делать это одновременно нельзя: без
     * замка два игрока, закончивших ход в одну секунду, считали каждый свой пересчёт и
     * затирали друг друга. Замок на строке партии выстраивает их в очередь, а игроки
     * разных партий друг другу не мешают — блокируется партия, а не сервер.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GameEntity g WHERE g.id = :id")
    Optional<GameEntity> lockById(UUID id);
}
