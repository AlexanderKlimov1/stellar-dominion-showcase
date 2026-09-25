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
 * Замер империи за один ход — п. 11.1, для графика окна «Инфо».
 * <p>
 * В MOO II окно Information показывает «график истории, на котором видно, как империя
 * игрока смотрится рядом с соперниками». Прошлое нигде больше не хранится: население,
 * флот и казна живут только текущим значением, и восстановить их за прошлый ход не из
 * чего. Поэтому фаза конца хода оставляет здесь по строке на игрока.
 * <p>
 * Величины сняты у всех империй, включая незнакомые: летопись пишется одна на партию, а
 * кого игроку показывать, решает {@link com.moo3.server.service.EmpireInfoService} — по
 * знакомствам. Прятать при записи нельзя: знакомство случится позже, а прошлые ходы к
 * тому времени уже не пересчитать.
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
@Table(name = "empire_history",
    indexes = {
        @Index(name = "ix_empire_history_game_turn", columnList = "game_id, turn")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_empire_history_player_turn", columnNames = {"player_id", "turn"})
    })
public class EmpireHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    /** Ход, за который снят замер: тот, что только что посчитан. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** Население империи в тысячах — в тех же единицах, что и у колонии. */
    @Column(name = "population_k", nullable = false)
    private Integer populationK;

    @Column(name = "colonies", nullable = false)
    private Integer colonies;

    /** Производство и наука за ход — суммой по колониям. */
    /**
     * Постройки империи ценой — п. 11.1: величина графика окна Info MOO II, где «каждое
     * здание добавляет свою стоимость производства». Не число зданий: дешёвая ферма и
     * глубинная шахта в оригинале весят по-разному.
     */
    @Column(name = "buildings", nullable = false)
    private Integer buildings = 0;

    @Column(name = "production", nullable = false)
    private Integer production;

    @Column(name = "research", nullable = false)
    private Integer research;

    /** Сила всех флотов империи — та же, по которой сравнивают себя империи ИИ (п. 15). */
    @Column(name = "fleet_power", nullable = false)
    private Integer fleetPower;

    @Column(name = "technologies", nullable = false)
    private Integer technologies;

    @Column(name = "credits", nullable = false)
    private Integer credits;

    /**
     * Доход империи за этот ход — поток, в отличие от казны.
     * <p>
     * Заведён ради мерила денег в балансировке: казна — это запас, и империя, потратившая
     * всё на стройку, стоит с нулём в кармане, живя при этом лучше скопидома. Мерить деньги
     * запасом значит мерить бережливость, а не богатство.
     */
    @Column(name = "income")
    private Integer income = 0;

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

    public Integer getPopulationK() {
        return populationK;
    }

    public void setPopulationK(Integer populationK) {
        this.populationK = populationK;
    }

    public Integer getColonies() {
        return colonies;
    }

    public void setColonies(Integer colonies) {
        this.colonies = colonies;
    }

    public Integer getProduction() {
        return production;
    }

    public Integer getBuildings() {
        return buildings;
    }

    public void setBuildings(Integer buildings) {
        this.buildings = buildings;
    }

    public void setProduction(Integer production) {
        this.production = production;
    }

    public Integer getResearch() {
        return research;
    }

    public void setResearch(Integer research) {
        this.research = research;
    }

    public Integer getFleetPower() {
        return fleetPower;
    }

    public void setFleetPower(Integer fleetPower) {
        this.fleetPower = fleetPower;
    }

    public Integer getTechnologies() {
        return technologies;
    }

    public void setTechnologies(Integer technologies) {
        this.technologies = technologies;
    }

    public Integer getIncome() {
        return income;
    }

    public void setIncome(Integer income) {
        this.income = income;
    }

    public Integer getCredits() {
        return credits;
    }

    public void setCredits(Integer credits) {
        this.credits = credits;
    }
}
