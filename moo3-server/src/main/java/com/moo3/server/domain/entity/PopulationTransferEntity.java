package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Рейс с жителями между колониями — п. 4.1.1.
 * <p>
 * Пока жители в пути, грузовики заняты именно ими: <b>по грузовику на единицу
 * населения</b>. Занятые грузовики не возят еду — у империи их столько, сколько она
 * построила, и делить их между рейсом и подвозом нельзя.
 * <p>
 * Рейс идёт один ход: жители садятся в этот ход и сходят на планету в конце следующего.
 * Расстояний игра пока не считает — время в пути одинаковое; когда появятся скорости,
 * они встанут сюда, а правило резерва не изменится.
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
@Table(name = "population_transfer",
    indexes = {
        @Index(name = "ix_transfer_game", columnList = "game_id"),
        @Index(name = "ix_transfer_owner", columnList = "owner_player_id")
    })
public class PopulationTransferEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    @Column(name = "from_planet_id", nullable = false)
    private UUID fromPlanetId;

    @Column(name = "to_planet_id", nullable = false)
    private UUID toPlanetId;

    /** Жителей на борту; столько же грузовиков и занято — по одному на единицу. */
    @Column(name = "population", nullable = false)
    private Integer population;

    @Column(name = "departed_turn", nullable = false)
    private Integer departedTurn;

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

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(UUID ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public UUID getFromPlanetId() {
        return fromPlanetId;
    }

    public void setFromPlanetId(UUID fromPlanetId) {
        this.fromPlanetId = fromPlanetId;
    }

    public UUID getToPlanetId() {
        return toPlanetId;
    }

    public void setToPlanetId(UUID toPlanetId) {
        this.toPlanetId = toPlanetId;
    }

    public Integer getPopulation() {
        return population;
    }

    public void setPopulation(Integer population) {
        this.population = population;
    }

    public Integer getDepartedTurn() {
        return departedTurn;
    }

    public void setDepartedTurn(Integer departedTurn) {
        this.departedTurn = departedTurn;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
