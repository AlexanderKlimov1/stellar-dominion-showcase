package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.PlayerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerEntity, UUID> {

    /**
     * Партии, где кто-то ждёт: идут, и хотя бы один человек уже закончил текущий ход, —
     * их и смотрят часы хода (backlog-promo, пункт 11). Одна выборка на обход, а не на
     * партию: партий без ожидающих большинство, и трогать их незачем.
     */
    @Query("select distinct p.game.id from PlayerEntity p "
            + "where p.playerType = com.sddnw.server.domain.enums.PlayerType.HUMAN "
            + "and p.game.status = com.sddnw.server.domain.enums.GameStatus.IN_PROGRESS "
            + "and p.endedTurn >= p.game.turn")
    List<UUID> findGameIdsWithSomebodyWaiting();

    /**
     * Места этой учётной записи во всех партиях — «вернуться в партию» (п. 3).
     * <p>
     * Партия тянется сразу (`JOIN FETCH`): список показывает её название и состояние, и
     * без этого выходил бы запрос на каждую строку.
     */
    @Query("select p from PlayerEntity p join fetch p.game where p.accountId = :accountId")
    List<PlayerEntity> findByAccountId(@Param("accountId") UUID accountId);

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
            + "AND p.playerType <> com.sddnw.server.domain.enums.PlayerType.MONSTER "
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
