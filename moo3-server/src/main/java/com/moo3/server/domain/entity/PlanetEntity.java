package com.moo3.server.domain.entity;

import com.moo3.server.domain.BuildQueueConverter;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.enums.MineralRichness;
import com.moo3.server.domain.enums.PlanetClimate;
import com.moo3.server.domain.enums.PlanetFind;
import com.moo3.server.domain.enums.PlanetSize;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

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
@Table(name = "planet",
    indexes = {
        @Index(name = "ix_planet_owner", columnList = "owner_player_id"),
        @Index(name = "ix_planet_star_system", columnList = "star_system_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_planet_system_orbit", columnNames = {"star_system_id", "orbit"})
    })
public class PlanetEntity {

    /** Тысяч жителей в одном работнике колонии. */
    public static final int POPULATION_UNIT = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "star_system_id", nullable = false)
    private StarSystemEntity starSystem;

    /** Номер орбиты в системе, 1..5 — п. 3. */
    @Column(name = "orbit", nullable = false)
    private Integer orbit;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "planet_size", nullable = false)
    private PlanetSize planetSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "climate", nullable = false)
    private PlanetClimate climate;

    @Enumerated(EnumType.STRING)
    @Column(name = "minerals", nullable = false)
    private MineralRichness minerals;

    /** Максимальное население с учётом размера и климата — п. 4.1.1.1 и п. 4.1.2. */
    @Column(name = "max_population", nullable = false)
    private Integer maxPopulation;

    /**
     * Находка на планете — п. 4.1; пусто — планета обыкновенная.
     * <p>
     * Живёт полем самой планеты, а не своей таблицей: находка — такое же её свойство, как
     * климат и недра, и спрашивают её там же, где их, — в каждом расчёте колонии.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "find_code")
    private PlanetFind find;

    /**
     * Находка уже отдала то, что даётся ОДИН раз, — технологии артефактов (п. 4.1).
     * <p>
     * Без отметки каждая следующая империя, добравшаяся до системы, получала бы их снова,
     * а в оригинале это награда ПЕРВОМУ. Прибавки колонии отметка не касается: они вечные
     * и достаются тому, кто на планете живёт.
     */
    @Column(name = "find_claimed", nullable = false)
    private Boolean findClaimed = Boolean.FALSE;

    /**
     * Колониальная база достроена и ждёт, какую планету системы заселить — п. 4.1.
     * Флаг снимается, как только игрок выбрал планету.
     */
    @Column(name = "colony_base_ready", nullable = false)
    private Boolean colonyBaseReady = Boolean.FALSE;

    @Column(name = "owner_player_id")
    private UUID ownerPlayerId;

    /**
     * Подданные — жители захваченной колонии, ещё не ставшие своими (п. 7, п. 12).
     * <p>
     * Часть населения планеты, а не довесок к нему: они живут, работают и едят, но
     * работают по расовым правилам своей прежней империи, пока не ассимилируются.
     */
    @Column(name = "alien_population", nullable = false)
    private Integer alienPopulation = 0;

    /** Чьи это подданные: империя, у которой колонию отняли, — по ней берётся их раса. */
    @Column(name = "alien_owner_player_id")
    private UUID alienOwnerPlayerId;

    /** Накопленные ходы ассимиляции: набралось на жителя — одним подданным меньше. */
    @Column(name = "assimilation_points", nullable = false)
    private Integer assimilationPoints = 0;

    /**
     * Население в тысячах жителей — как в MOO II, где население считается тысячами,
     * а прирост за ход измеряется их десятками и сотнями.
     * <p>
     * Работать в колонии умеют только целые жители: их число — это {@link #getPopulation()},
     * то есть тысячи, делённые на тысячу. Поэтому колония растёт каждый ход, а нового
     * работника получает только когда накопит целую тысячу.
     */
    @Column(name = "population_k", nullable = false)
    private Integer populationK = 0;

    /**
     * Занятия жителей колонии: сумма трёх чисел равна населению планеты, незанятых нет.
     * У незаселённой планеты все три нуля.
     */
    @Column(name = "farmers", nullable = false)
    private Integer farmers = 0;

    @Column(name = "workers", nullable = false)
    private Integer workers = 0;

    @Column(name = "scientists", nullable = false)
    private Integer scientists = 0;

    /**
     * Что колония строит — п. 10: код здания либо особый проект (дома, товары).
     * Пусто — колония ничего не строит, и её производство пропадает.
     */
    @Column(name = "project_code")
    private String projectCode;

    /** Единиц производства, вложенных в текущий проект. */
    @Column(name = "project_points", nullable = false)
    private Integer projectPoints = 0;

    /**
     * Очередь стройки — п. 10: что колония заложит, когда достроит нынешнее.
     * <p>
     * Коды те же, что в списке доступного ({@code colony.available}), и по порядку.
     * Достроив проект, фаза производства берёт голову очереди, а остаток производства
     * переходит в неё — как в MOO II, где накопленное не пропадает.
     */
    @Convert(converter = BuildQueueConverter.class)
    @Column(name = "build_queue", length = 1000)
    private List<String> buildQueue = new ArrayList<>();

    /**
      * Какой проект корабля строит колония — п. 8. Пусто, пока строится не корабль.
      * <p>
      * Код стройки говорит «корабль», а какой именно — эта ссылка: проект живёт у
      * империи, а не у планеты, и переделка проекта не должна менять то, что уже стоит
      * на стапеле.
      */
    @Column(name = "project_design_id")
    private UUID projectDesignId;

    /**
     * Ход, на котором колония продала постройку в последний раз, — п. 10.
     * <p>
     * В MOO II за ход продаётся одно здание, и предел этот у каждой колонии свой: общего
     * счётчика у империи нет. Пусто — не продавала ни разу.
     */
    @Column(name = "sold_turn")
    private Integer soldTurn;

    /**
     * При скольких технологиях империи ИИ эта колония в последний раз смотрела список
     * стройки и не нашла в нём ничего — п. 15, п. 10.
     * <p>
     * Список стройки собирается тяжело, а меняется от изученного: новая технология
     * открывает здание или корпус. Колония, которой строить нечего, вечно стоит на
     * товарах, и пересматривать список каждый ход до конца партии — чистая трата: половину
     * фазы ИИ, то есть четверть всего конца хода.
     * <p>
     * <b>След нужен у колонии, а не у империи.</b> Пустой стройки в игре не остаётся —
     * достроив здание, колония переходит на товары, — а товары для ИИ значат «свободна».
     * Признак на уровне империи поэтому ловил как раз тех, кто только что достроил и ждёт
     * нового дела, и колония простаивала до следующей технологии: замер показал, что фаза
     * производства подешевела втрое, потому что строить стало почти нечего.
     * <p>
     * Пусто значит «смотреть надо»: так помечена и новая колония, и только что
     * освободившаяся. Человека это поле не касается — за него выбирает он сам.
     */
    @Column(name = "ai_idle_tech")
    private Integer aiIdleTech;

    /** Стартовая планета игрока. */
    @Column(name = "homeworld", nullable = false)
    private Boolean homeworld;

    /**
     * Версия строки для оптимистичной блокировки.
     * <p>
     * Игроки партии действуют одновременно, и без версии две правки одной строки просто
     * затирали друг друга: обе читали старое состояние и обе писали своё. Теперь второй
     * записи прилетает конфликт, и вызывающий её повторяет на свежих данных.
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

    public StarSystemEntity getStarSystem() {
        return starSystem;
    }

    public void setStarSystem(StarSystemEntity starSystem) {
        this.starSystem = starSystem;
    }

    public Integer getOrbit() {
        return orbit;
    }

    public void setOrbit(Integer orbit) {
        this.orbit = orbit;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PlanetSize getPlanetSize() {
        return planetSize;
    }

    public void setPlanetSize(PlanetSize planetSize) {
        this.planetSize = planetSize;
    }

    public PlanetClimate getClimate() {
        return climate;
    }

    public void setClimate(PlanetClimate climate) {
        this.climate = climate;
    }

    public MineralRichness getMinerals() {
        return minerals;
    }

    public void setMinerals(MineralRichness minerals) {
        this.minerals = minerals;
    }

    public PlanetFind getFind() {
        return find;
    }

    public void setFind(PlanetFind find) {
        this.find = find;
    }

    public Boolean getFindClaimed() {
        return findClaimed;
    }

    public void setFindClaimed(Boolean findClaimed) {
        this.findClaimed = findClaimed;
    }

    public Boolean getColonyBaseReady() {
        return colonyBaseReady;
    }

    public void setColonyBaseReady(Boolean colonyBaseReady) {
        this.colonyBaseReady = colonyBaseReady;
    }

    public Integer getAlienPopulation() {
        return alienPopulation;
    }

    public void setAlienPopulation(Integer alienPopulation) {
        this.alienPopulation = alienPopulation;
    }

    public UUID getAlienOwnerPlayerId() {
        return alienOwnerPlayerId;
    }

    public void setAlienOwnerPlayerId(UUID alienOwnerPlayerId) {
        this.alienOwnerPlayerId = alienOwnerPlayerId;
    }

    public Integer getAssimilationPoints() {
        return assimilationPoints;
    }

    public void setAssimilationPoints(Integer assimilationPoints) {
        this.assimilationPoints = assimilationPoints;
    }

    public Integer getMaxPopulation() {
        return maxPopulation;
    }

    public void setMaxPopulation(Integer maxPopulation) {
        this.maxPopulation = maxPopulation;
    }

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(UUID ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public Integer getPopulationK() {
        return populationK;
    }

    public void setPopulationK(Integer populationK) {
        this.populationK = populationK;
    }

    /** Жителей на планете — целые работники, которых можно распределить по занятиям. */
    public Integer getPopulation() {
        return populationK / POPULATION_UNIT;
    }

    /** Заселяет планету целым числом жителей: остатка тысяч у такой колонии нет. */
    public void setPopulation(Integer population) {
        this.populationK = population * POPULATION_UNIT;
    }

    public Integer getFarmers() {
        return farmers;
    }

    public void setFarmers(Integer farmers) {
        this.farmers = farmers;
    }

    public Integer getWorkers() {
        return workers;
    }

    public void setWorkers(Integer workers) {
        this.workers = workers;
    }

    public Integer getScientists() {
        return scientists;
    }

    public void setScientists(Integer scientists) {
        this.scientists = scientists;
    }

    /** Занятия жителей колонии одним значением. */
    public PopulationJobs getJobs() {
        return new PopulationJobs(farmers, workers, scientists);
    }

    public void setJobs(PopulationJobs jobs) {
        this.farmers = jobs.farmers();
        this.workers = jobs.workers();
        this.scientists = jobs.scientists();
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public Integer getProjectPoints() {
        return projectPoints;
    }

    public void setProjectPoints(Integer projectPoints) {
        this.projectPoints = projectPoints;
    }

    public UUID getProjectDesignId() {
        return projectDesignId;
    }

    public void setProjectDesignId(UUID projectDesignId) {
        this.projectDesignId = projectDesignId;
    }

    public Integer getSoldTurn() {
        return soldTurn;
    }

    public void setSoldTurn(Integer soldTurn) {
        this.soldTurn = soldTurn;
    }

    public Integer getAiIdleTech() {
        return aiIdleTech;
    }

    public void setAiIdleTech(Integer aiIdleTech) {
        this.aiIdleTech = aiIdleTech;
    }

    public List<String> getBuildQueue() {
        return buildQueue;
    }

    public void setBuildQueue(List<String> buildQueue) {
        this.buildQueue = buildQueue == null ? new ArrayList<>() : new ArrayList<>(buildQueue);
    }

    public Boolean getHomeworld() {
        return homeworld;
    }

    public void setHomeworld(Boolean homeworld) {
        this.homeworld = homeworld;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
