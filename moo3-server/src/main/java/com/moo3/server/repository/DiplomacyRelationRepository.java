package com.moo3.server.repository;

import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Отношения империй — п. 15. */
public interface DiplomacyRelationRepository extends JpaRepository<DiplomacyRelationEntity, UUID> {

    List<DiplomacyRelationEntity> findAllByPlayerId(UUID playerId);

    /**
     * Отношения всех участников партии одной выборкой — п. 11.1.
     * <p>
     * Конец хода спрашивает их дважды: знакомство по дальности проверяет каждую пару, а
     * дипломатия ИИ — каждого соседа. Запросом на пару это двадцать восемь походов в базу
     * за ход на партии в восемь империй, и каждый из них ещё и сбрасывал в базу всё,
     * что ход успел изменить. Теперь строки читаются разом и разбираются в памяти.
     */
    List<DiplomacyRelationEntity> findAllByPlayerIdIn(Collection<UUID> playerIds);

    /**
     * Строки соседей, обращённые к этому игроку, — п. 15.
     * <p>
     * Отношения хранятся двумя строками, и решения принимает та, что принадлежит другой
     * стороне: соглашается на договор тот, кто доверяет. Экрану нужна именно она — иначе
     * игрок видел бы своё отношение к соседу и не понимал, почему сосед отказывает.
     */
    List<DiplomacyRelationEntity> findAllByOtherPlayerId(UUID otherPlayerId);

    Optional<DiplomacyRelationEntity> findByPlayerIdAndOtherPlayerId(UUID playerId, UUID otherPlayerId);
}
