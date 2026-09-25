package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * Последний отчёт хода игрока — что изменилось, пока считалась галактика (п. 11.1).
 * <p>
 * Ход считается один раз на всех, когда его закончили все игроки партии. Значит, для
 * большинства пересчёт случается не по их запросу, и рассказать им о случившемся нужно
 * отдельно. Отчёт лежит строкой на игрока и переписывается каждый ход: он нужен, пока
 * игрок его не увидел — в том числе после перезагрузки страницы и после переподключения
 * к другому экземпляру сервера.
 * <p>
 * Само содержимое — готовый {@code TurnReportDto} в JSON: разбирать его на таблицы незачем,
 * искать по нему нечего.
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
@Table(name = "turn_report",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_turn_report_player", columnNames = {"player_id"})
    })
public class TurnReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", nullable = false)
    private String state;

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

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
