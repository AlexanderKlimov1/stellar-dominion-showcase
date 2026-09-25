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
 * Здание, построенное на планете — п. 10.
 * <p>
 * Одно здание возводится на планете один раз, поэтому пара «планета — здание» уникальна.
 * Дома и товары сюда не попадают: они зданиями не становятся.
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
@Table(name = "planet_building",
    indexes = {
        @Index(name = "ix_planet_building_planet", columnList = "planet_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_planet_building", columnNames = {"planet_id", "building_code"})
    })
public class PlanetBuildingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "planet_id", nullable = false)
    private UUID planetId;

    @Column(name = "building_code", nullable = false)
    private String buildingCode;

    /** Ход, на конец которого здание достроили. */
    @Column(name = "built_turn", nullable = false)
    private Integer builtTurn;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPlanetId() {
        return planetId;
    }

    public void setPlanetId(UUID planetId) {
        this.planetId = planetId;
    }

    public String getBuildingCode() {
        return buildingCode;
    }

    public void setBuildingCode(String buildingCode) {
        this.buildingCode = buildingCode;
    }

    public Integer getBuiltTurn() {
        return builtTurn;
    }

    public void setBuiltTurn(Integer builtTurn) {
        this.builtTurn = builtTurn;
    }
}
