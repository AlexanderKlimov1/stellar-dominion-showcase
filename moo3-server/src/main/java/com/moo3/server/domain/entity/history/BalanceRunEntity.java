package com.moo3.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Балансовый прогон, заказанный из пульта администратора — этап 2 плана
 * (`balance-metrics-works.txt`).
 * <p>
 * Прогон идёт десятками минут, поэтому он не «запрос, который ждут», а запись, которую
 * заводят и потом навещают: страницу можно закрыть и вернуться.
 * <p>
 * <b>Снимок цен ({@link #traitCosts}) — главное поле этой таблицы.</b> Цены правятся и с
 * соседнего экрана, и прямо в файле, а прогон длится полчаса; без снимка уже через день
 * нельзя сказать, какие именно цены он мерил. Журнал цен (п. 8 плана) держится именно на
 * этом: правка цены записывается вместе с тем, на чём она измерена.
 */
@Entity
@Table(name = "balance_run")
public class BalanceRunEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "galaxy_size", nullable = false, length = 16)
    private String galaxySize;

    @Column(name = "empires", nullable = false)
    private Integer empires;

    @Column(name = "games", nullable = false)
    private Integer games;

    @Column(name = "turns", nullable = false)
    private Integer turns;

    @Column(name = "seed", nullable = false)
    private Long seed;

    /** Сколько партий прогона уже сыграно: пульт показывает ход дела, а не «ждите». */
    @Column(name = "played", nullable = false)
    private Integer played;

    /** Чем играют империи: по записи на место — готовая раса или случайная сборка. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "race_designs", nullable = false)
    private String raceDesigns;

    /** Цены сторон расы на миг запуска — «код: очки». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trait_costs", nullable = false)
    private String traitCosts;

    /** Итог: сила каждой стороны и приговор её цене; пусто — прогон ещё идёт. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result")
    private String result;

    /**
     * Сырые замеры: строка на империю каждой партии.
     * <p>
     * Хранятся потому, что правила чтения замера меняются чаще самих замеров: за один
     * вечер порядок оценки правился трижды, и каждый раз переигрывать двести партий по
     * сорок минут значило бы чинить прибор вслепую. С сырыми замерами прогон переоценивается
     * мгновенно и нынешними правилами.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurements")
    private String measurements;

    /**
     * Проверяемая связка — две или три стороны, подсаженные в сборки нарочно (п. 2.14).
     * <p>
     * Лежит в самой записи прогона по той же причине, что и снимок цен: через день по одним
     * приговорам не отличить прогон с подсаженной связкой от обычного, а читаются они
     * по-разному — в первом у пары носители по построению, во втором по удаче.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "combination_traits")
    private String combinationTraits;

    /**
     * Род прогона: {@code MEASURE} — приговоры ценам (этап 2), {@code ORACLE} — поиск
     * сильнейших сборок (этап 3).
     * <p>
     * Оракул живёт в той же таблице не ради экономии: у него та же галактика, то же число
     * империй, то же зерно и тот же счётчик сыгранных партий — это один и тот же прогон
     * партий, просто с другим вопросом. Заводить ему свою таблицу значило бы завести и
     * вторую половину пульта.
     */
    @Column(name = "kind", length = 20, nullable = false)
    private String kind = "MEASURE";

    /** Сколько сборок в первом поколении поиска; у замера пусто. */
    @Column(name = "population")
    private Integer population;

    /** Ладдер сборок с ответом на оба условия плана; у замера пусто. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ladder")
    private String ladder;

    @Column(name = "failure", length = 500)
    private String failure;

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Integer getPopulation() {
        return population;
    }

    public void setPopulation(Integer population) {
        this.population = population;
    }

    public String getLadder() {
        return ladder;
    }

    public void setLadder(String ladder) {
        this.ladder = ladder;
    }

    public String getCombinationTraits() {
        return combinationTraits;
    }

    public void setCombinationTraits(String combinationTraits) {
        this.combinationTraits = combinationTraits;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGalaxySize() {
        return galaxySize;
    }

    public void setGalaxySize(String galaxySize) {
        this.galaxySize = galaxySize;
    }

    public Integer getEmpires() {
        return empires;
    }

    public void setEmpires(Integer empires) {
        this.empires = empires;
    }

    public Integer getGames() {
        return games;
    }

    public void setGames(Integer games) {
        this.games = games;
    }

    public Integer getTurns() {
        return turns;
    }

    public void setTurns(Integer turns) {
        this.turns = turns;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public Integer getPlayed() {
        return played;
    }

    public void setPlayed(Integer played) {
        this.played = played;
    }

    public String getRaceDesigns() {
        return raceDesigns;
    }

    public void setRaceDesigns(String raceDesigns) {
        this.raceDesigns = raceDesigns;
    }

    public String getTraitCosts() {
        return traitCosts;
    }

    public void setTraitCosts(String traitCosts) {
        this.traitCosts = traitCosts;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getMeasurements() {
        return measurements;
    }

    public void setMeasurements(String measurements) {
        this.measurements = measurements;
    }

    public String getFailure() {
        return failure;
    }

    public void setFailure(String failure) {
        this.failure = failure;
    }
}
