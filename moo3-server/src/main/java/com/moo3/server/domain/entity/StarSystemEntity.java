package com.moo3.server.domain.entity;

import com.moo3.server.domain.enums.SpaceMonster;
import com.moo3.server.domain.enums.StarColor;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
@Table(name = "star_system",
    indexes = {
        @Index(name = "ix_star_system_game", columnList = "game_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_star_system_game_name", columnNames = {"game_id", "name"})
    })
public class StarSystemEntity {

    /** Планета зовётся по своей звезде и номеру орбиты: «Sol III» — п. 4.1. */
    private static final String[] ORBIT_NUMERALS = {"I", "II", "III", "IV", "V"};

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private GameEntity game;

    @Column(name = "name", nullable = false)
    private String name;

    /** Координата X в ГРЕ. */
    @Column(name = "x_parsec", nullable = false)
    private Double xParsec;

    /** Координата Y в ГРЕ. */
    @Column(name = "y_parsec", nullable = false)
    private Double yParsec;

    @Enumerated(EnumType.STRING)
    @Column(name = "star_color", nullable = false)
    private StarColor starColor;

    /** Специальная звезда Wardenhold — п. 4.2.1. */
    @Column(name = "special", nullable = false)
    private Boolean special;

    /**
     * Клад особой звезды уже взят — п. 4.2.1.
     * <p>
     * Наследие Стражей достаётся ОДИН раз, первой империи, которая заселила Wardenhold:
     * иначе отбитая и заселённая заново колония выдавала бы три технологии всякий раз.
     * Признак живёт у самой системы: планета в ней не одна, а клад один.
     */
    @Column(name = "special_claimed", nullable = false)
    private Boolean specialClaimed = Boolean.FALSE;

    /**
     * Космическое чудище, сторожащее систему, — п. 11.1; пусто — система чиста.
     * <p>
     * Чудище живёт в самой системе: у него нет ни флота, ни хозяина, только место и
     * здоровье. Оно нападает на всякий вошедший чужой флот и закрывает систему для
     * расселения, пока живо.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "monster_code")
    private SpaceMonster monster;

    /**
     * Сколько в чудище осталось силы — в той же мере, что и сила флота.
     * <p>
     * Отбив нападение, чудище слабеет: большой флот убивает его с первого раза, малый —
     * гибнет сам, но оставляет рану. Так система с драконом однажды всё же открывается.
     */
    @Column(name = "monster_strength")
    private Integer monsterStrength;

    @OneToMany(mappedBy = "starSystem", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("orbit ASC")
    private List<PlanetEntity> planets = new ArrayList<>();

    /** Чудище системы — п. 11.1; {@code null} — система чиста. */
    public SpaceMonster getMonster() {
        return monster;
    }

    public void setMonster(SpaceMonster monster) {
        this.monster = monster;
    }

    public Integer getMonsterStrength() {
        return monsterStrength;
    }

    public void setMonsterStrength(Integer monsterStrength) {
        this.monsterStrength = monsterStrength;
    }

    /** Жив ли сторож: по нему решается и бой на подлёте, и запрет расселения. */
    public Boolean hasLiveMonster() {
        return monster != null && monsterStrength != null && monsterStrength > 0;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public GameEntity getGame() {
        return game;
    }

    public void setGame(GameEntity game) {
        this.game = game;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getXParsec() {
        return xParsec;
    }

    public void setXParsec(Double xParsec) {
        this.xParsec = xParsec;
    }

    public Double getYParsec() {
        return yParsec;
    }

    public void setYParsec(Double yParsec) {
        this.yParsec = yParsec;
    }

    public StarColor getStarColor() {
        return starColor;
    }

    public void setStarColor(StarColor starColor) {
        this.starColor = starColor;
    }

    public Boolean getSpecial() {
        return special;
    }

    public Boolean getSpecialClaimed() {
        return specialClaimed;
    }

    public void setSpecialClaimed(Boolean specialClaimed) {
        this.specialClaimed = specialClaimed;
    }

    public void setSpecial(Boolean special) {
        this.special = special;
    }

    public List<PlanetEntity> getPlanets() {
        return planets;
    }

    public void setPlanets(List<PlanetEntity> planets) {
        this.planets = planets;
    }

    public void addPlanet(PlanetEntity planet) {
        planet.setStarSystem(this);
        planets.add(planet);
    }

    /** Имя планеты на орбите системы с таким названием. */
    public static String planetName(String systemName, Integer orbit) {
        return systemName + " " + ORBIT_NUMERALS[orbit - 1];
    }

    /**
     * Переименование системы вместе с планетами: их названия построены от названия
     * звезды, и без переименования планеты остались бы у чужого имени.
     */
    public void rename(String name) {
        this.name = name;
        planets.forEach(planet -> planet.setName(planetName(name, planet.getOrbit())));
    }
}
