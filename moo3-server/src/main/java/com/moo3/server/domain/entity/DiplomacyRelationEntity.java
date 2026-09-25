package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.DiplomacyStance;
import com.moo3.server.domain.enums.DiplomacyTreaty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Отношения одной империи к другой — п. 15.
 * <p>
 * Строка на направление, а не на пару: доверие у сторон своё, и в MOO II одна империя
 * может считать другую другом, пока та готовит удар. Состояние войны и мира стороны
 * меняют вместе — его сервис держит одинаковым в обеих строках.
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
@Table(name = "diplomacy_relation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_relation_pair", columnNames = {"player_id", "other_player_id"})
    })
public class DiplomacyRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    /** Империя, о которой эта строка. */
    @Column(name = "other_player_id", nullable = false)
    private UUID otherPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stance", nullable = false)
    private DiplomacyStance stance = DiplomacyStance.NEUTRAL;

    /** Доверие к другой империи, 0..100. */
    @Column(name = "trust", nullable = false)
    private Integer trust = 50;

    /** Ход, на котором империи познакомились. */
    @Column(name = "met_turn", nullable = false)
    private Integer metTurn;

    /**
     * Договоры с этой империей — п. 15, кодами через запятую.
     * <p>
     * Набор небольшой и всегда читается вместе с самими отношениями, поэтому лежит
     * колонкой, а не отдельной таблицей: искать по нему нечего.
     */
    @Column(name = "treaties", length = 256)
    private String treaties;

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

    public UUID getOtherPlayerId() {
        return otherPlayerId;
    }

    public void setOtherPlayerId(UUID otherPlayerId) {
        this.otherPlayerId = otherPlayerId;
    }

    public DiplomacyStance getStance() {
        return stance;
    }

    public void setStance(DiplomacyStance stance) {
        this.stance = stance;
    }

    public Integer getTrust() {
        return trust;
    }

    public void setTrust(Integer trust) {
        this.trust = trust;
    }

    /** Действующие договоры; пустой набор — договоров нет. */
    public Set<DiplomacyTreaty> getTreaties() {
        if (treaties == null || treaties.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(treaties.split(","))
                .map(DiplomacyTreaty::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setTreaties(Set<DiplomacyTreaty> value) {
        this.treaties = value == null || value.isEmpty()
                ? null
                : value.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    public Integer getMetTurn() {
        return metTurn;
    }

    public void setMetTurn(Integer metTurn) {
        this.metTurn = metTurn;
    }
}
