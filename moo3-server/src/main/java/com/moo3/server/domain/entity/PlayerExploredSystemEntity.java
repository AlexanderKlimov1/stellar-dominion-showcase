package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * Разведанная игроком чужая система — п. 15.
 * <p>
 * Свои системы разведаны и так, поэтому здесь только те, куда игрок отправлял шпиона:
 * знание о системе остаётся навсегда, даже если колония в ней сменит хозяина.
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
@Table(name = "player_explored_system",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_explored_player_system", columnNames = {"player_id", "star_system_id"})
    })
public class PlayerExploredSystemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "star_system_id", nullable = false)
    private UUID starSystemId;

    /** Ход, на котором система разведана. */
    @Column(name = "explored_turn", nullable = false)
    private Integer exploredTurn;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public Integer getExploredTurn() {
        return exploredTurn;
    }

    public void setExploredTurn(Integer exploredTurn) {
        this.exploredTurn = exploredTurn;
    }
}
