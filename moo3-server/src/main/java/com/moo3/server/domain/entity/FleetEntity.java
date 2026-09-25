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
 * Флот игрока в звёздной системе — п. 8.
 *
 * <p>Флот один на систему: корабли, построенные колониями этой системы, встают в общий
 * строй, а перелёт переносит весь флот целиком. Отдельных кораблей с их проектами игра
 * пока не различает — в бою считается число кораблей и раса владельца. Когда появятся
 * проекты кораблей у построенных единиц, сюда встанет их состав, а перелёт и встречи
 * останутся прежними.
 *
 * <p>Перелёт занимает ходы — п. 8. Пока флот в пути, у него заполнены
 * {@link #targetSystemId} и {@link #arrivalTurn}, а {@link #starSystemId} остаётся системой
 * вылета: летящий флот не стоит нигде — он не держит систему вылета, не разведывает
 * систему назначения и не встречается с чужими флотами, пока не придёт.
 *
 * <p>Флот один на систему — правило только для стоящих флотов: рядом с летящим в той же
 * системе стоит свой же флот из кораблей, которые никуда не полетели.
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
@Table(name = "fleet",
    indexes = {
        @Index(name = "ix_fleet_game_arrival", columnList = "game_id, arrival_turn"),
        @Index(name = "ix_fleet_game_system", columnList = "game_id, star_system_id")
    })
public class FleetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    @Column(name = "star_system_id", nullable = false)
    private UUID starSystemId;

    @Column(name = "ships", nullable = false)
    private Integer ships = 0;

    @Column(name = "created_turn", nullable = false)
    private Integer createdTurn;

    /**
     * Откуда флот вылетел — п. 8. Совпадает с {@link #starSystemId}; хранится отдельно,
     * потому что по нему на карте рисуется линия полёта, а система вылета у флота, который
     * уже прибыл, никакого смысла не имеет и обнуляется вместе с остальным путём.
     */
    @Column(name = "origin_system_id")
    private UUID originSystemId;

    /** Куда флот летит; {@code null} — флот стоит в своей системе. */
    @Column(name = "target_system_id")
    private UUID targetSystemId;

    /** Ход, на котором флот вылетел: по нему считается, какую часть пути он прошёл. */
    @Column(name = "departure_turn")
    private Integer departureTurn;

    /** Ход, в конце которого флот придёт на место, — п. 8. */
    @Column(name = "arrival_turn")
    private Integer arrivalTurn;

    /**
     * Версия строки: флот трогают и постройка корабля в конце хода, и перелёт игрока,
     * и бой — одновременная правка не должна теряться.
     */
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

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public Integer getShips() {
        return ships;
    }

    public void setShips(Integer ships) {
        this.ships = ships;
    }

    public Integer getCreatedTurn() {
        return createdTurn;
    }

    public void setCreatedTurn(Integer createdTurn) {
        this.createdTurn = createdTurn;
    }

    public UUID getOriginSystemId() {
        return originSystemId;
    }

    public void setOriginSystemId(UUID originSystemId) {
        this.originSystemId = originSystemId;
    }

    public UUID getTargetSystemId() {
        return targetSystemId;
    }

    public void setTargetSystemId(UUID targetSystemId) {
        this.targetSystemId = targetSystemId;
    }

    public Integer getDepartureTurn() {
        return departureTurn;
    }

    public void setDepartureTurn(Integer departureTurn) {
        this.departureTurn = departureTurn;
    }

    public Integer getArrivalTurn() {
        return arrivalTurn;
    }

    public void setArrivalTurn(Integer arrivalTurn) {
        this.arrivalTurn = arrivalTurn;
    }

    /** Флот в пути: приказ отдан, но до места он ещё не дошёл — п. 8. */
    public Boolean isInFlight() {
        return targetSystemId != null;
    }

    /** Флот прилетел: путь стирается, и флот снова стоит в системе. */
    public void land(UUID starSystemId) {
        this.starSystemId = starSystemId;
        this.originSystemId = null;
        this.targetSystemId = null;
        this.departureTurn = null;
        this.arrivalTurn = null;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
