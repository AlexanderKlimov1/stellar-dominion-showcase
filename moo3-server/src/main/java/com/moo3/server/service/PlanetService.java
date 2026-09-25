package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.dto.ColonizeRequest;
import com.moo3.server.dto.FleetLandingRequest;
import com.moo3.server.dto.InvadeRequest;
import com.moo3.server.dto.InvasionResultDto;
import com.moo3.server.dto.MoveQueueRequest;
import com.moo3.server.dto.PlanetDto;
import com.moo3.server.dto.QueueProjectsRequest;
import com.moo3.server.dto.StarSystemDto;
import com.moo3.server.dto.SellBuildingRequest;
import com.moo3.server.dto.SetPopulationRequest;
import com.moo3.server.dto.SetProjectRequest;
import com.moo3.server.dto.TransferPopulationRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Действия игрока с планетой — п. 4.1 и п. 10.
 * <p>
 * Тонкая прослойка между контроллером и {@link ColonyService}: проверяет доступ к партии
 * и приводит изменённую планету к виду для клиента. Сама механика колонии живёт в
 * {@link ColonyService}, а собрать её ответ он не может — карту планеты строит
 * {@link GameMapper}, который на него же и опирается.
 * <p>
 * Возвращается всегда планета целиком: и перераспределение жителей, и смена стройки
 * меняют выработку, доход и рождаемость колонии, поэтому клиенту нужна свежая карточка,
 * а не подтверждение действия.
 */
@Service
public class PlanetService {

    private final GameAccess gameAccess;
    private final ColonyService colonyService;
    private final GroundCombatService groundCombatService;
    private final ExpeditionService expeditionService;
    private final PopulationTransferService transferService;
    private final GameMapper gameMapper;

    public PlanetService(GameAccess gameAccess,
                         ColonyService colonyService,
                         GroundCombatService groundCombatService,
                         ExpeditionService expeditionService,
                         PopulationTransferService transferService,
                         GameMapper gameMapper) {
        this.transferService = transferService;
        this.gameAccess = gameAccess;
        this.colonyService = colonyService;
        this.groundCombatService = groundCombatService;
        this.expeditionService = expeditionService;
        this.gameMapper = gameMapper;
    }

    /** Перераспределение жителей колонии между фермерами, рабочими и учёными — п. 4.1. */
    @Transactional
    public PlanetDto setPopulation(UUID gameId, UUID planetId, SetPopulationRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        return gameMapper.toPlanet(colonyService.setPopulation(player, planetId, request));
    }

    /**
     * Перевозка жителей в другую свою колонию — п. 4.1.1.
     * <p>
     * Грузовой флот резервируется по грузовику на единицу населения и освобождается,
     * когда рейс дойдёт. В ответ идёт колония-отправитель: жители сходят с неё сразу.
     */
    @Transactional
    public PlanetDto transferPopulation(UUID gameId, UUID planetId, TransferPopulationRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        return gameMapper.toPlanet(transferService.send(
                game, player, planetId, request.targetPlanetId(), request.population()));
    }

    /** Смена стройки колонии — п. 10: здание, дома, товары или колониальная база. */
    @Transactional
    public PlanetDto setProject(UUID gameId, UUID planetId, SetProjectRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        return gameMapper.toPlanet(colonyService.setProject(player, planetId, request));
    }

    /** Выкуп стройки колонии за кредиты — п. 10: достроится тем же ходом. */
    @Transactional
    public PlanetDto buyProject(UUID gameId, UUID planetId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return gameMapper.toPlanet(colonyService.buyProject(player, planetId));
    }

    /**
     * Продажа постройки колонии — п. 10.
     * <p>
     * Ход берётся у самой партии: им колония отмечает, что своё за этот ход уже продала,
     * — у игрока спрашивать нечего.
     */
    @Transactional
    public PlanetDto sellBuilding(UUID gameId, UUID planetId, SellBuildingRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        return gameMapper.toPlanet(colonyService.sellBuilding(
                player, planetId, request.buildingCode(), game.getTurn()));
    }

    /** Добавить проект в очередь стройки колонии — п. 10. */
    @Transactional
    public PlanetDto enqueue(UUID gameId, UUID planetId, SetProjectRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        return gameMapper.toPlanet(colonyService.enqueue(player, planetId, request));
    }

    /**
     * Добавить один проект в очередь сразу многим колониям — п. 10.
     * <p>
     * Приказ из списка колоний: меняются все колонии набора или ни одна — отказ хотя бы
     * одной откатывает всё. Возвращаются они все: у каждой изменилась очередь, а у иной
     * и сама стройка.
     */
    @Transactional
    public List<PlanetDto> enqueueAll(UUID gameId, QueueProjectsRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        SetProjectRequest one = new SetProjectRequest(
                request.accessToken(), request.projectCode(), request.top());
        return colonyService.enqueueAll(player, request.planetIds(), one).stream()
                .map(gameMapper::toPlanet)
                .toList();
    }

    /** Убрать проект из очереди стройки — п. 10. */
    @Transactional
    public PlanetDto removeFromQueue(UUID gameId, UUID planetId, Integer index, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return gameMapper.toPlanet(colonyService.removeFromQueue(player, planetId, index));
    }

    /** Переставить проект в очереди стройки — п. 10. */
    @Transactional
    public PlanetDto moveInQueue(UUID gameId, UUID planetId, Integer index, MoveQueueRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        return gameMapper.toPlanet(colonyService.moveInQueue(player, planetId, index, request.toIndex()));
    }

    /**
     * Высадка десанта на чужую колонию той же системы — п. 12.
     * <p>
     * Возвращается и сам исход боя, и система целиком: в ней изменились обе планеты —
     * та, что отправила десант, и та, за которую бились.
     */
    @Transactional
    public InvasionResultDto invade(UUID gameId, UUID planetId, InvadeRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        GroundCombatService.Outcome outcome =
                groundCombatService.invade(player, game.getTurn(), planetId, request);

        StarSystemEntity system = colonyService.planet(planetId).getStarSystem();
        return new InvasionResultDto(
                outcome.captured(),
                outcome.survivors(),
                outcome.attackPower(),
                outcome.defencePower(),
                gameMapper.toSystem(system, colonyService.context(system.getPlanets())));
    }

    /**
     * Заселение планеты колониальным кораблём — п. 4.1: расселение за пределы своей
     * системы, ради которого корабль и строили.
     * <p>
     * Возвращается система целиком: в ней появилась колония, а во флоте стало на корабль
     * меньше.
     */
    @Transactional
    public StarSystemDto colonizeFromFleet(UUID gameId, UUID fleetId, FleetLandingRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        StarSystemEntity system = expeditionService
                .colonize(player, fleetId, request.targetPlanetId())
                .getStarSystem();
        return gameMapper.toSystem(system, colonyService.context(system.getPlanets()));
    }

    /**
     * Застава с корабля-заставы — п. 8: жителей она не даёт, но раздвигает дальность
     * империи, а с ней и всё, куда та может долететь.
     */
    @Transactional
    public StarSystemDto outpostFromFleet(UUID gameId, UUID fleetId, FleetLandingRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        StarSystemEntity system = expeditionService
                .outpost(player, fleetId, request.targetPlanetId())
                .getStarSystem();
        return gameMapper.toSystem(system, colonyService.context(system.getPlanets()));
    }

    /**
     * Высадка десанта с транспортов — п. 12: захват чужой колонии в любой системе, куда
     * долетел флот.
     */
    @Transactional
    public InvasionResultDto invadeFromFleet(UUID gameId, UUID fleetId, FleetLandingRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        GroundCombatService.Outcome outcome = expeditionService.invade(
                player, fleetId, request.targetPlanetId(), game.getTurn(), game.getId());

        StarSystemEntity system = colonyService.planet(request.targetPlanetId()).getStarSystem();
        return new InvasionResultDto(
                outcome.captured(),
                outcome.survivors(),
                outcome.attackPower(),
                outcome.defencePower(),
                gameMapper.toSystem(system, colonyService.context(system.getPlanets())));
    }

    /**
     * Подчинение чужой колонии телепатами — п. 7, п. 12.
     * <p>
     * Возвращается система целиком: колония меняет хозяина вместе с жителями, и соседние
     * планеты на экране должны увидеть это сразу.
     */
    @Transactional
    public StarSystemDto mindControlFromFleet(UUID gameId, UUID fleetId,
                                              FleetLandingRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());
        PlanetEntity planet = expeditionService.mindControl(
                player, fleetId, request.targetPlanetId(), game.getTurn(), game.getId());

        StarSystemEntity system = planet.getStarSystem();
        return gameMapper.toSystem(system, colonyService.context(system.getPlanets()));
    }

    /**
     * Заселение планеты готовой колониальной базой — п. 4.1.
     * <p>
     * Возвращается система целиком, а не одна планета: заселение меняет сразу две —
     * новую колонию и ту, что сняла с себя готовую базу.
     */
    @Transactional
    public StarSystemDto colonize(UUID gameId, UUID planetId, ColonizeRequest request) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, request.accessToken());
        StarSystemEntity system = colonyService.colonize(player, planetId, request).getStarSystem();
        return gameMapper.toSystem(system, colonyService.context(system.getPlanets()));
    }
}
