package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.BattleState;
import com.moo3.server.domain.EventArgsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.List;
import java.util.UUID;

/**
 * Тактический бой в системе — п. 8, сцена боя MOO II.
 * <p>
 * Бой живёт на сервере, а не на клиенте: он списывает корабли и решает исход встречи, и
 * верить в этом клиенту нельзя. Экран боя только показывает поле и просит ходы.
 * <p>
 * <b>Ходят корабли, а не игроки.</b> Очередь задаёт инициатива корабля, и в одном круге
 * ходят все живые корабли обеих сторон по очереди — как в MOO II, где быстрый фрегат
 * успевает выстрелить раньше чужого дредноута. Поэтому у боя есть номер круга и
 * указатель на корабль, чей ход сейчас.
 * <p>
 * Зерно случайности хранится: попадания разыгрываются, и бой должен считаться одинаково
 * при повторной обработке той же записи.
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
@Table(name = "space_battle",
    indexes = {
        @Index(name = "ix_space_battle_game_state", columnList = "game_id, state")
    })
public class SpaceBattleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "star_system_id", nullable = false)
    private UUID starSystemId;

    /**
     * Встреча флотов, из которой вырос бой: ей же достаётся исход.
     * <p>
     * ПУСТО у демонстрационного боя из главного меню — он растёт не из встречи, а из
     * нажатия, и партии за ним нет вовсе (п. 8). Liquibase это знает и снимает с колонки
     * обязательность (`036-demo-battle.xml`), а здесь пометка `nullable = false` осталась
     * от первой версии. На Postgres расхождение не видно — схему строит миграция; но там,
     * где схему строит Hibernate (H2 прогонного режима, п. 3.90), демонстрация падала
     * пятисотым ответом. Те же грабли, что с индексами и каскадами: правило, записанное
     * только в миграции, действует только там, где миграция прошла.
     */
    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "attacker_player_id", nullable = false)
    private UUID attackerPlayerId;

    @Column(name = "defender_player_id", nullable = false)
    private UUID defenderPlayerId;

    /** Ход партии, в котором идёт бой. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** Круг боя: за круг ходят все живые корабли по одному разу. */
    @Column(name = "round", nullable = false)
    private Integer round = 1;

    /** Чей ход сейчас; пусто — бой кончился. */
    @Column(name = "current_ship_id")
    private UUID currentShipId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private BattleState state = BattleState.IN_PROGRESS;

    /** Кто ушёл с поля, если бой кончился отступлением. */
    @Column(name = "retreated_player_id")
    private UUID retreatedPlayerId;

    /** Чем кончилось — готовая строка для итогов хода обеих сторон. */
    /**
      Итог боя ключом с подстановками — п. 3.5: он рождается внутри хода, а читают его
      оба участника, каждый на своём языке. Колонка та же, что была: запись ключом от
      старой строки отличается разделителем, и прежний текст читается как есть.
     */
    @Convert(converter = EventArgsConverter.class)
    @Column(name = "outcome", length = 512)
    private List<String> outcome;

    /** Зерно случайности боя: попадания разыгрываются им. */
    @Column(name = "seed", nullable = false)
    private Long seed;

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

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(UUID encounterId) {
        this.encounterId = encounterId;
    }

    public UUID getAttackerPlayerId() {
        return attackerPlayerId;
    }

    public void setAttackerPlayerId(UUID attackerPlayerId) {
        this.attackerPlayerId = attackerPlayerId;
    }

    public UUID getDefenderPlayerId() {
        return defenderPlayerId;
    }

    public void setDefenderPlayerId(UUID defenderPlayerId) {
        this.defenderPlayerId = defenderPlayerId;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public Integer getRound() {
        return round;
    }

    public void setRound(Integer round) {
        this.round = round;
    }

    public UUID getCurrentShipId() {
        return currentShipId;
    }

    public void setCurrentShipId(UUID currentShipId) {
        this.currentShipId = currentShipId;
    }

    public BattleState getState() {
        return state;
    }

    public void setState(BattleState state) {
        this.state = state;
    }

    public UUID getRetreatedPlayerId() {
        return retreatedPlayerId;
    }

    public void setRetreatedPlayerId(UUID retreatedPlayerId) {
        this.retreatedPlayerId = retreatedPlayerId;
    }

    public List<String> getOutcome() {
        return outcome;
    }

    public void setOutcome(List<String> outcome) {
        this.outcome = outcome;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
