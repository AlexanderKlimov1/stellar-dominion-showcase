package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * Изученная игроком технология — п. 9.
 * <p>
 * Строка появляется в момент прорыва: одна на выбранную технологию, а на общих уровнях
 * дерева (Nuclear Fission, Cold Fusion, Chemistry, Physics) — по одной на каждую
 * технологию уровня, потому что они выдаются все сразу.
 * <p>
 * Раздел и уровень хранятся вместе с кодом технологии: по ним видно, какие уровни
 * раздела уже пройдены, а значит какой уровень исследуется следующим.
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
@Table(name = "player_technology",
    indexes = {
        @Index(name = "ix_player_technology_player", columnList = "player_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_player_technology_option", columnNames = {"player_id", "option_code"})
    })
public class PlayerTechnologyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "category_code", nullable = false)
    private String categoryCode;

    @Column(name = "level_order", nullable = false)
    private Integer levelOrder;

    @Column(name = "option_code", nullable = false)
    private String optionCode;

    /** Ход, на конец которого случился прорыв. */
    @Column(name = "acquired_turn", nullable = false)
    private Integer acquiredTurn;

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

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public Integer getLevelOrder() {
        return levelOrder;
    }

    public void setLevelOrder(Integer levelOrder) {
        this.levelOrder = levelOrder;
    }

    public String getOptionCode() {
        return optionCode;
    }

    public void setOptionCode(String optionCode) {
        this.optionCode = optionCode;
    }

    public Integer getAcquiredTurn() {
        return acquiredTurn;
    }

    public void setAcquiredTurn(Integer acquiredTurn) {
        this.acquiredTurn = acquiredTurn;
    }
}
