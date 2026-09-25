package com.moo3.server.service;

import com.moo3.server.domain.ShipStats;
import com.moo3.server.domain.entity.EmpireHistoryEntity;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Всё, что нужно фазам конца хода, собранное один раз — п. 11.1.
 * <p>
 * Раньше каждая фаза заново вычитывала галактику и заново собирала контекст колоний:
 * на партии Huge это 81 система и 236 планет, прочитанных трижды за один ход. Теперь
 * выборка одна на весь ход, а фазы работают с уже загруженными сущностями — они
 * управляемые, и правки сохраняются сами при закрытии транзакции.
 * <p>
 * Здесь же лежит отчёт хода: фазы дописывают в него, что изменилось у каждого игрока.
 */
public final class TurnContext {

    private final GameEntity game;
    private final List<PlayerEntity> players;
    private final List<StarSystemEntity> systems;
    private final List<PlanetEntity> colonies;
    private final ColonyService.ColonyContext colonyContext;
    private final TurnReport report;
    private final Map<UUID, UUID> systemByPlanet;

    /**
     * Характеристики проектов кораблей этого хода — п. 8.
     * <p>
     * Памятка, а не выборка: цену проекта спрашивает фаза производства у КАЖДОЙ колонии,
     * которая строит корабль, а колоний у восьми империй под полторы сотни. Прежде каждый
     * такой вопрос уходил в базу отдельным запросом ({@code ShipDesignService.stats} ->
     * {@code items}), и изнутри посчитанного хода это дорого вдвойне: всякий запрос
     * заставляет Hibernate сбросить туда же всё, что ход успел изменить. Выборка стеков на
     * круге 7 показала `items` в 12,8 % всего хода уже ПОСЛЕ починки соседнего N+1.
     * <p>
     * Памятка живёт ровно ход и врать не может: состав проекта в игре не правится — лучшее
     * вооружение заводит НОВУЮ строку, а прежняя помечается вытесненной (п. 8). Исключение
     * одно — платформы обороны, им состав правится на месте; но платформу колония как
     * корабль не строит, и в эту памятку она не попадает.
     */
    private final Map<UUID, ShipStats> shipStatsByDesign = new HashMap<>();

    /**
     * Замер империй этого хода — п. 11.1: его просят двое, дипломатия ИИ (порядок 17) и
     * летопись (18), и обоим нужен один и тот же. Считается он не бесплатно — проход по
     * колониям, изученное и сила флотов всех игроков, — поэтому снимается один раз и
     * запоминается здесь, как и всё остальное, что нужно ходу больше одного раза.
     */
    private List<EmpireHistoryEntity> empireMeasure;

    public TurnContext(GameEntity game,
                       List<PlayerEntity> players,
                       List<StarSystemEntity> systems,
                       List<PlanetEntity> colonies,
                       ColonyService.ColonyContext colonyContext,
                       TurnReport report) {
        this.game = game;
        this.players = players;
        this.systems = systems;
        this.colonies = colonies;
        this.colonyContext = colonyContext;
        this.report = report;

        this.systemByPlanet = new HashMap<>();
        for (StarSystemEntity system : systems) {
            for (PlanetEntity planet : system.getPlanets()) {
                systemByPlanet.put(planet.getId(), system.getId());
            }
        }
    }

    public GameEntity game() {
        return game;
    }

    /** Ход, который сейчас считается: он же попадает в отчёт и в записи об изученном. */
    public Integer turn() {
        return game.getTurn();
    }

    public List<PlayerEntity> players() {
        return players;
    }

    public List<StarSystemEntity> systems() {
        return systems;
    }

    /** Заселённые планеты партии: с ними работают все фазы, кроме исследований. */
    public List<PlanetEntity> colonies() {
        return colonies;
    }

    public ColonyService.ColonyContext colonyContext() {
        return colonyContext;
    }

    public TurnReport report() {
        return report;
    }

    /** Система планеты — для отчёта: по ней клиент понимает, что обновлять. */
    public UUID systemOf(PlanetEntity planet) {
        return systemByPlanet.get(planet.getId());
    }

    /**
     * Замер империй этого хода: считается по требованию и один раз.
     *
     * @param measure чем считать, если ещё не считали, — сама выборка живёт в
     *                {@code EmpireInfoService}, а ход только помнит её итог
     */
    public List<EmpireHistoryEntity> empireMeasure(Supplier<List<EmpireHistoryEntity>> measure) {
        if (empireMeasure == null) {
            empireMeasure = measure.get();
        }
        return empireMeasure;
    }

    /**
     * Характеристики проекта корабля: считаются по требованию и один раз на ход — п. 8.
     *
     * @param designId проект, о котором спрашивают
     * @param stats    чем считать, если ещё не считали, — само правило живёт в
     *                 {@code ShipDesignService}, а ход только помнит его итог
     */
    public ShipStats shipStats(UUID designId, Supplier<ShipStats> stats) {
        return shipStatsByDesign.computeIfAbsent(designId, key -> stats.get());
    }

    /** Колонии одного игрока — нужны шпионажу: саботаж бьёт по чужой стройке. */
    public List<PlanetEntity> coloniesOf(UUID playerId) {
        return colonies.stream()
                .filter(planet -> playerId.equals(planet.getOwnerPlayerId()))
                .toList();
    }
}
