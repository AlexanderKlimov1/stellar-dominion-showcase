package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.ShipRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Проект корабля игрока — п. 8.
 * <p>
 * Как в MOO II, у империи шесть ячеек проектов ({@code slot}). Игрок переделывает ячейку
 * сколько угодно раз, но <b>уже построенные корабли остаются прежними</b>: старая запись
 * не правится, а помечается {@code obsolete} и живёт дальше — по ней летает флот. Новый
 * проект занимает ту же ячейку и с этого хода строится колониями. Иначе переделка проекта
 * задним числом меняла бы корабли, которые давно в строю.
 * <p>
 * Корпус и компоненты хранятся кодами — ссылками в {@code ship-components.json}. Значит,
 * правка баланса в файле меняет характеристики всех проектов сразу и миграции не требует.
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
@Table(name = "ship_design",
    indexes = {
        @Index(name = "ix_ship_design_owner", columnList = "owner_player_id, slot")
    })
public class ShipDesignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    /** Ячейка проекта, 1..6 — как шесть строк в окне дизайна MOO II. */
    @Column(name = "slot", nullable = false)
    private Integer slot;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "hull_code", nullable = false)
    private String hullCode;

    @Column(name = "created_turn", nullable = false)
    private Integer createdTurn;

    /**
     * Для чего корабль построен — п. 8. Боевой проект собирает игрок; колониальный
     * корабль и транспорт империя получает готовыми, как в списке стройки MOO II, и
     * стоят они в служебной ячейке 0 — в шести ячейках окна дизайна им не место.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private ShipRole role = ShipRole.WARSHIP;

    /**
     * Проект вытеснен из своей ячейки новым. Строить по нему уже нельзя, но корабли,
     * построенные раньше, остаются в строю и ссылаются сюда.
     */
    @Column(name = "obsolete", nullable = false)
    private Boolean obsolete = Boolean.FALSE;

    /**
     * Проект собран самой игрой, а не игроком, — п. 8.
     * <p>
     * Такие проекты стоят в ячейках, до которых у игрока ещё не дошли руки, и игра вправе
     * переписать их сама, когда изучены новые двигатель, броня или пушка: в MOO II шесть
     * ячеек не пустуют, и колонии всегда есть что строить. Собранный игроком проект не
     * трогается никогда — за него отвечает он.
     */
    @Column(name = "auto", nullable = false)
    private Boolean auto = Boolean.FALSE;

    /** Версия строки: проект правит игрок, а читает его стройка в конце хода. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Boolean getAuto() {
        return auto;
    }

    public void setAuto(Boolean auto) {
        this.auto = auto;
    }

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

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(Integer slot) {
        this.slot = slot;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHullCode() {
        return hullCode;
    }

    public void setHullCode(String hullCode) {
        this.hullCode = hullCode;
    }

    public Integer getCreatedTurn() {
        return createdTurn;
    }

    public void setCreatedTurn(Integer createdTurn) {
        this.createdTurn = createdTurn;
    }

    public Boolean getObsolete() {
        return obsolete;
    }

    public void setObsolete(Boolean obsolete) {
        this.obsolete = obsolete;
    }

    public ShipRole getRole() {
        return role;
    }

    public void setRole(ShipRole role) {
        this.role = role;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
