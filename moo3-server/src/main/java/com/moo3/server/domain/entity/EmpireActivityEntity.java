package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * Чем империя за партию пользовалась — этап 1 балансировки (`balance-metrics-works.txt`).
 * <p>
 * Строка на «империя — что случилось»: сколько раз она высаживала десант, брала колонии,
 * крала технологии, слала флоты, нанимала лидеров. Нужно это не игроку, а измерению:
 * сторона расы стоит ноль либо потому, что слаба, либо потому, что её механика за всю
 * партию не сработала ни разу, — и различить это можно только счётчиком.
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
@Table(name = "empire_activity",
    indexes = {
        @Index(name = "ix_empire_activity_game", columnList = "game_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_empire_activity_player_code", columnNames = {"player_id", "code"})
    })
public class EmpireActivityEntity {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    /** Что случилось: {@code INVASION}, {@code CAPTURE}, {@code ESPIONAGE} и прочие. */
    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "times", nullable = false)
    private Integer times = 0;

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

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Integer getTimes() {
        return times;
    }

    public void setTimes(Integer times) {
        this.times = times;
    }
}
