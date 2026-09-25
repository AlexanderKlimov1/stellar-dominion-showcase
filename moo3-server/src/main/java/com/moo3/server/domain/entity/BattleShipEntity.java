package com.moo3.server.domain.entity;

import com.moo3.server.domain.EventArgsConverter;
import com.moo3.server.domain.enums.BattleSide;
import jakarta.persistence.Convert;
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

import java.util.List;
import java.util.UUID;

/**
 * Корабль на поле боя — п. 8.
 * <p>
 * До боя корабли живут числом во флоте ({@code fleet_ship}: сколько единиц какого
 * проекта). В бою каждый становится отдельной единицей со своим местом на поле,
 * прочностью и очередью хода: иначе ни ходить, ни гибнуть по одному они не смогут.
 * Уцелевшие возвращаются во флот числом, погибшие списываются.
 * <p>
 * Прочность, броня и щит хранятся текущие: они убывают за бой. Всё остальное — залп,
 * скорость, дальность — берётся из проекта, потому что от боя не зависит и меняться
 * не должно.
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
@Table(name = "battle_ship",
    indexes = {
        @Index(name = "ix_battle_ship_battle", columnList = "battle_id")
    })
public class BattleShipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "battle_id", nullable = false)
    private UUID battleId;

    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    /** Проект корабля: из него берутся залп, скорость и живучесть. */
    @Column(name = "design_id", nullable = false)
    private UUID designId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private BattleSide side;

    /** Номер корабля в своём строю: по нему он и подписан на поле — «Охотник 2». */
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;

    /** Прочность корпуса сейчас; ноль — корабль уничтожен. */
    @Column(name = "structure", nullable = false)
    private Integer structure;

    /** Броня сейчас: её снимают раньше прочности. */
    @Column(name = "armour", nullable = false)
    private Integer armour;

    /** Щит: держит удар раньше брони и восстанавливается между кругами. */
    @Column(name = "shield", nullable = false)
    private Integer shield;

    /** Инициатива: по ней строится очередь хода кораблей — п. 8. */
    @Column(name = "initiative", nullable = false)
    private Integer initiative;

    /** Сколько клеток корабль уже прошёл в этом своём ходу. */
    @Column(name = "moved", nullable = false)
    private Integer moved = 0;

    /** Стрелял ли в этом своём ходу: залп у корабля один. */
    @Column(name = "fired", nullable = false)
    private Boolean fired = Boolean.FALSE;

    /** Уничтожен: остаётся в записи боя ради подсчёта потерь, но с поля уходит. */
    @Column(name = "destroyed", nullable = false)
    private Boolean destroyed = Boolean.FALSE;

    /**
     * Выбитые бортовые системы — п. 8: то, что разбито внутри корабля.
     * <p>
     * Попадание, прошедшее сквозь щит и броню, рвёт корабль изнутри: гаснет ствол,
     * разбивается двигатель, сгорает прицел. Гнездо здесь считается отдельно от вида —
     * четыре одинаковых ствола это четыре записи, и выбивает их по одной.
     * <p>
     * Список, а не колонка на систему: систем у разных корпусов разное число, а бой
     * живёт один ход. Хранится он тем же преобразователем, что и подстановки событий.
     * Поломка держится один бой: в следующий корабль выходит целым — чинить его в MOO II
     * не нужно, там тоже ломается снаряжение вылета, а не корабль навсегда.
     */
    @Convert(converter = EventArgsConverter.class)
    @Column(name = "damaged_systems", length = 512)
    private List<String> damagedSystems;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public List<String> getDamagedSystems() {
        return damagedSystems == null ? List.of() : damagedSystems;
    }

    public void setDamagedSystems(List<String> damagedSystems) {
        // КОПИЕЙ, а не тем же списком: у колонки свой преобразователь, и Hibernate
        // сравнивает поле с тем самым списком, который отдал при загрузке, — правку на
        // месте он просто не увидит (те же грабли, что у очереди стройки).
        this.damagedSystems = damagedSystems == null || damagedSystems.isEmpty()
                ? null
                : List.copyOf(damagedSystems);
    }

    /** Выбита ли эта система — по ней и решается, стреляет ли ствол и ходит ли корабль. */
    public Boolean systemDamaged(String system) {
        return getDamagedSystems().contains(system);
    }

    /** Отмечает систему выбитой; одно и то же гнездо дважды не ломается. */
    public void damageSystem(String system) {
        List<String> broken = new java.util.ArrayList<>(getDamagedSystems());
        broken.add(system);
        setDamagedSystems(broken);
    }

    /** Жив ли корабль — им и меряется, кончился ли бой. */
    public Boolean alive() {
        return !Boolean.TRUE.equals(destroyed);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBattleId() {
        return battleId;
    }

    public void setBattleId(UUID battleId) {
        this.battleId = battleId;
    }

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(UUID ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public UUID getDesignId() {
        return designId;
    }

    public void setDesignId(UUID designId) {
        this.designId = designId;
    }

    public BattleSide getSide() {
        return side;
    }

    public void setSide(BattleSide side) {
        this.side = side;
    }

    public Integer getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal) {
        this.ordinal = ordinal;
    }

    public Integer getX() {
        return x;
    }

    public void setX(Integer x) {
        this.x = x;
    }

    public Integer getY() {
        return y;
    }

    public void setY(Integer y) {
        this.y = y;
    }

    public Integer getStructure() {
        return structure;
    }

    public void setStructure(Integer structure) {
        this.structure = structure;
    }

    public Integer getArmour() {
        return armour;
    }

    public void setArmour(Integer armour) {
        this.armour = armour;
    }

    public Integer getShield() {
        return shield;
    }

    public void setShield(Integer shield) {
        this.shield = shield;
    }

    public Integer getInitiative() {
        return initiative;
    }

    public void setInitiative(Integer initiative) {
        this.initiative = initiative;
    }

    public Integer getMoved() {
        return moved;
    }

    public void setMoved(Integer moved) {
        this.moved = moved;
    }

    public Boolean getFired() {
        return fired;
    }

    public void setFired(Boolean fired) {
        this.fired = fired;
    }

    public Boolean getDestroyed() {
        return destroyed;
    }

    public void setDestroyed(Boolean destroyed) {
        this.destroyed = destroyed;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
