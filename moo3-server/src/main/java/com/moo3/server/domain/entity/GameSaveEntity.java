package com.moo3.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сохранённая партия: состояние игры на конец завершённого хода.
 * <p>
 * Само состояние лежит в {@code state} одним JSON-документом
 * ({@link com.moo3.server.domain.save.GameSnapshot}) — так сохранение не зависит от
 * живых строк игры и переживает её удаление. Остальные поля — витрина для списка
 * сохранений: их видно в диалоге загрузки без разбора документа.
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
@Table(name = "game_save",
    indexes = {
        @Index(name = "ix_game_save_game_id", columnList = "game_id"),
        @Index(name = "ix_game_save_saved_at", columnList = "saved_at")
    })
public class GameSaveEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Игра, с которой снят слепок. Сама игра может быть уже удалена. */
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "galaxy_size", nullable = false)
    private String galaxySize;

    @Column(name = "width_gre", nullable = false)
    private Integer widthParsecs;

    @Column(name = "height_gre", nullable = false)
    private Integer heightParsecs;

    @Column(name = "star_count", nullable = false)
    private Integer starCount;

    /** Номер завершённого хода, на конец которого снято состояние. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    @Column(name = "total_players", nullable = false)
    private Integer totalPlayers;

    @Column(name = "human_players", nullable = false)
    private Integer humanPlayers;

    @Column(name = "saved_at", nullable = false)
    private OffsetDateTime savedAt;

    /** Запись автосохранения: на партию она одна и переписывается каждый ход. */
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGalaxySize() {
        return galaxySize;
    }

    public void setGalaxySize(String galaxySize) {
        this.galaxySize = galaxySize;
    }

    public Integer getWidthParsecs() {
        return widthParsecs;
    }

    public void setWidthParsecs(Integer widthParsecs) {
        this.widthParsecs = widthParsecs;
    }

    public Integer getHeightParsecs() {
        return heightParsecs;
    }

    public void setHeightParsecs(Integer heightParsecs) {
        this.heightParsecs = heightParsecs;
    }

    public Integer getStarCount() {
        return starCount;
    }

    public void setStarCount(Integer starCount) {
        this.starCount = starCount;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public Integer getTotalPlayers() {
        return totalPlayers;
    }

    public void setTotalPlayers(Integer totalPlayers) {
        this.totalPlayers = totalPlayers;
    }

    public Integer getHumanPlayers() {
        return humanPlayers;
    }

    public void setHumanPlayers(Integer humanPlayers) {
        this.humanPlayers = humanPlayers;
    }

    public OffsetDateTime getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(OffsetDateTime savedAt) {
        this.savedAt = savedAt;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
