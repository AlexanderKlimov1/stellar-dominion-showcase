package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.EncounterState;
import com.moo3.server.domain.EventArgsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.List;
import java.util.UUID;

/**
 * Встреча флотов двух империй в одной системе — п. 8.
 * <p>
 * Первым в записи стоит тот, чей флот быстрее: инициатива решает, кому предлагают выбор
 * раньше. Отказался от боя — очередь второго. Отказались оба — флоты расходятся.
 * <p>
 * Встреча заводится в конце хода и ждёт решения в начале следующего: игрок видит её в
 * итогах хода вместе с остальными событиями.
 */
/*
 * Индексы и уникальности объявлены ЗДЕСЬ, а не только в миграции.
 *
 * Объявленные в changelog'е, они существуют лишь там, где changelog прошёл. На H2, где
 * схему строит Hibernate по сущностям (режим балансового прогона), их не было вовсе — и
 * та же партия в пятьсот ходов шла 236 секунд вместо 159: каждая выборка внутри хода
 * перебирала таблицу целиком. Вторая причина проще: глядя на сущность, видно, по каким
 * полям её ищут, а лишний индекс заметен рядом с полем, а не в файле миграции
 * трёхмесячной давности.
 *
 * В Postgres они уже созданы, и Hibernate их не трогает: ddl-auto: validate сверяет
 * таблицы и колонки, но не индексы.
 */
@Entity
@Table(name = "fleet_encounter",
    indexes = {
        @Index(name = "ix_encounter_game_state", columnList = "game_id, state")
    })
public class FleetEncounterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "star_system_id", nullable = false)
    private UUID starSystemId;

    /** Ход, в конце которого флоты оказались в одной системе. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** Чья инициатива выше — тот и решает первым. */
    @Column(name = "first_player_id", nullable = false)
    private UUID firstPlayerId;

    @Column(name = "second_player_id", nullable = false)
    private UUID secondPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private EncounterState state = EncounterState.WAITING_FIRST;

    /** Кто напал, если дело дошло до боя. */
    @Column(name = "attacker_player_id")
    private UUID attackerPlayerId;

    /** Тактический бой, если игрок выбрал ручной, — п. 8. */
    @Column(name = "battle_id")
    private UUID battleId;

    /** Чем кончилось — готовая строка для итогов хода обеих сторон. */
    /**
      Итог боя ключом с подстановками — п. 3.5: он рождается внутри хода, а читают его
      оба участника, каждый на своём языке. Колонка та же, что была: запись ключом от
      старой строки отличается разделителем, и прежний текст читается как есть.
     */
    @Convert(converter = EventArgsConverter.class)
    @Column(name = "outcome", length = 512)
    private List<String> outcome;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public UUID getFirstPlayerId() {
        return firstPlayerId;
    }

    public void setFirstPlayerId(UUID firstPlayerId) {
        this.firstPlayerId = firstPlayerId;
    }

    public UUID getSecondPlayerId() {
        return secondPlayerId;
    }

    public void setSecondPlayerId(UUID secondPlayerId) {
        this.secondPlayerId = secondPlayerId;
    }

    public EncounterState getState() {
        return state;
    }

    public void setState(EncounterState state) {
        this.state = state;
    }

    public UUID getAttackerPlayerId() {
        return attackerPlayerId;
    }

    public void setAttackerPlayerId(UUID attackerPlayerId) {
        this.attackerPlayerId = attackerPlayerId;
    }

    public UUID getBattleId() {
        return battleId;
    }

    public void setBattleId(UUID battleId) {
        this.battleId = battleId;
    }

    public List<String> getOutcome() {
        return outcome;
    }

    public void setOutcome(List<String> outcome) {
        this.outcome = outcome;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    /** Тот из двоих, кто не он: удобно и для очереди решений, и для текстов событий. */
    public UUID opponentOf(UUID playerId) {
        return firstPlayerId.equals(playerId) ? secondPlayerId : firstPlayerId;
    }
}
