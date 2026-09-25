package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Голос империи на выборах Высшего совета — п. 3.
 * <p>
 * Строка на каждую голосующую империю: сколько у неё голосов и за кого она их отдала.
 * Пустой выбор значит «воздержалась» — «The Alkaris abstain» оригинала.
 * <p>
 * <b>Зачем хранить, а не пересчитывать.</b> Выборы случаются ВНУТРИ посчитанного хода, а
 * смотрит на них игрок потом, открыв сцену совета: голоса идут по одному, и он видит, кто
 * его поддержал. Пересчитать это позже нельзя — доверие меняется каждый ход, и к моменту
 * показа оно уже другое. Поэтому голосование записывается, как записывается летопись
 * империй.
 * <p>
 * Строки живут до конца партии и удаляются вместе с ней ({@code GameService.deleteBelongings}).
 */
/*
 * Индекс объявлен ЗДЕСЬ, а не только в миграции: на H2, где схему строит Hibernate по
 * сущностям (режим балансового прогона), объявленного в changelog'е не существует вовсе.
 */
@Entity
@Table(name = "council_vote",
    indexes = {
        @Index(name = "ix_council_vote_game_turn", columnList = "game_id, turn")
    })
public class CouncilVoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    /** Ход, на котором собрался совет. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    @Column(name = "voter_player_id", nullable = false)
    private UUID voterPlayerId;

    /** Голосов у этой империи — столько же, сколько у неё населения (п. 3). */
    @Column(name = "weight", nullable = false)
    private Integer weight;

    /** За кого отдан голос; пусто — империя воздержалась. */
    @Column(name = "choice_player_id")
    private UUID choicePlayerId;

    /** Эта империя — кандидат: голосуют за неё, и сама она голосует за себя. */
    @Column(name = "candidate", nullable = false)
    private Boolean candidate = Boolean.FALSE;

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public UUID getVoterPlayerId() {
        return voterPlayerId;
    }

    public void setVoterPlayerId(UUID voterPlayerId) {
        this.voterPlayerId = voterPlayerId;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public UUID getChoicePlayerId() {
        return choicePlayerId;
    }

    public void setChoicePlayerId(UUID choicePlayerId) {
        this.choicePlayerId = choicePlayerId;
    }

    public Boolean getCandidate() {
        return candidate;
    }

    public void setCandidate(Boolean candidate) {
        this.candidate = candidate;
    }
}
