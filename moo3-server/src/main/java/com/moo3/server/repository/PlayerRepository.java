package com.moo3.server.repository;

import com.moo3.server.domain.entity.PlayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerEntity, UUID> {

    /**
     * ВСЕ строки игроков партии, включая чудище (п. 11.1).
     * <p>
     * Нужен этот список ровно одному делу — бою: у стороны на поле есть владелец, и
     * владельцем чудища стоит его собственная строка. Всем остальным — империи:
     * {@link #findEmpiresByGameIdOrderBySlotAsc}. Спросив здесь, легко получить чудище
     * в списке участников, в летописи или в слепке партии.
     */
    List<PlayerEntity> findAllByGameIdOrderBySlotAsc(UUID gameId);

    /**
     * Империи партии — люди и ИИ, без чудищ (п. 11.1).
     * <p>
     * Это и есть «участники партии» для всего, что их считает: лобби, летописи, совета,
     * телеметрии, слепка и конца хода. Чудище империей не бывает ни в одном из них.
     */
    @Query("SELECT p FROM PlayerEntity p WHERE p.game.id = :gameId "
            + "AND p.playerType <> com.moo3.server.domain.enums.PlayerType.MONSTER "
            + "ORDER BY p.slot ASC")
    List<PlayerEntity> findEmpiresByGameIdOrderBySlotAsc(UUID gameId);

    /** Игроки сразу нескольких партий — для списка игр: один запрос вместо запроса на партию. */
    List<PlayerEntity> findAllByGameIdInOrderBySlotAsc(Collection<UUID> gameIds);

    Optional<PlayerEntity> findByGameIdAndAccessToken(UUID gameId, String accessToken);

    /**
     * Только идентификатор игрока по пропуску — для действий, которые дальше берут партию
     * под замок. Сущность бы легла в контекст со своей тогдашней версией, и запись под
     * замком падала бы из-за соседа, успевшего раньше; число не кэшируется никак.
     */
    @Query("SELECT p.id FROM PlayerEntity p WHERE p.game.id = :gameId AND p.accessToken = :accessToken")
    Optional<UUID> findIdByGameIdAndAccessToken(UUID gameId, String accessToken);

    Integer countByGameId(UUID gameId);
}
