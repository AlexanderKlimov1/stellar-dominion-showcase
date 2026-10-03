package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.CouncilVoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Голоса Высшего совета — п. 3.
 * <p>
 * Порядок выборки задан явно: голоса идут по убыванию веса, а при равном весе — по
 * идентификатору. Сцена показывает их ПО ОДНОМУ, и порядок обязан быть один и тот же при
 * каждом открытии — иначе одна и та же партия рассказывала бы о выборах по-разному.
 */
public interface CouncilVoteRepository extends JpaRepository<CouncilVoteEntity, UUID> {

    List<CouncilVoteEntity> findAllByGameIdAndTurnOrderByWeightDescVoterPlayerIdAsc(
            UUID gameId, Integer turn);

    List<CouncilVoteEntity> findAllByGameId(UUID gameId);

    java.util.Optional<CouncilVoteEntity> findByGameIdAndTurnAndVoterPlayerId(
            UUID gameId, Integer turn, UUID voterPlayerId);

    void deleteAllByGameId(UUID gameId);
}
