package com.moo3.server.domain.entity;

import com.moo3.server.domain.EventArgsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.List;
import java.util.UUID;

/**
 * Событие, случившееся с игроком внутри хода — п. 11.1.
 * <p>
 * Знакомство с чужой расой, объявленная соседом война, заключённый договор, украденная
 * технология, захваченная колония — всё это происходит не по нажатию самого игрока и
 * часто вообще без его участия. Показать это нужно в итогах хода, а до конца хода —
 * где-то держать: подписка могла быть закрыта, страница перезагружена, игрок отошёл.
 * <p>
 * Строка живёт до ближайших итогов хода: {@code TurnService} собирает события в отчёт
 * и удаляет их. Хранить историю партии отдельной таблицей не нужно — на это есть журнал.
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
@Table(name = "player_event",
    indexes = {
        @Index(name = "ix_player_event_player_turn", columnList = "player_id, turn")
    })
public class PlayerEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    /** Ход, на котором событие случилось: в итоги оно попадёт вместе с этим ходом. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** Вид события — тот же словарь, что у отчёта хода: DIPLOMACY, ESPIONAGE, INVASION… */
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    /**
      Ключ словаря, а не готовая строка — п. 3.5: событие ждёт конца хода, а язык известен
      только тому запросу, который придёт его читать.
     */
    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    @Convert(converter = EventArgsConverter.class)
    @Column(name = "args", length = 1024)
    private List<String> args;

    @Column(name = "star_system_id")
    private UUID starSystemId;

    @Column(name = "planet_id")
    private UUID planetId;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public UUID getPlanetId() {
        return planetId;
    }

    public void setPlanetId(UUID planetId) {
        this.planetId = planetId;
    }
}
