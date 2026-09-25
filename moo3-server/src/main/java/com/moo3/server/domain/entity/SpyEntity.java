package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.SpyMission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Шпион империи — п. 13.
 * <p>
 * Агент, построенный колонией. Пока он дома, он работает на общий запас очков разведки;
 * отправленный к сопернику — копит очки на свою операцию и повторяет её снова и снова,
 * как в MOO II.
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
@Table(name = "spy",
    indexes = {
        @Index(name = "ix_spy_owner", columnList = "owner_player_id")
    })
public class SpyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    /** К кому отправлен; {@code null} — шпион дома. */
    @Column(name = "target_player_id")
    private UUID targetPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mission", nullable = false)
    private SpyMission mission = SpyMission.HOME;

    /** Накоплено очков на текущую операцию. */
    @Column(name = "points", nullable = false)
    private Integer points = 0;

    /** Ход, на котором агент подготовлен. */
    @Column(name = "created_turn", nullable = false)
    private Integer createdTurn;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(UUID ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public UUID getTargetPlayerId() {
        return targetPlayerId;
    }

    public void setTargetPlayerId(UUID targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }

    public SpyMission getMission() {
        return mission;
    }

    public void setMission(SpyMission mission) {
        this.mission = mission;
    }

    public Integer getPoints() {
        return points;
    }

    public void setPoints(Integer points) {
        this.points = points;
    }

    public Integer getCreatedTurn() {
        return createdTurn;
    }

    public void setCreatedTurn(Integer createdTurn) {
        this.createdTurn = createdTurn;
    }
}
