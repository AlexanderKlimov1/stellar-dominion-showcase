package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.enums.PlanetGravity;
import com.moo3.server.dto.GalaxyMapDto;
import com.moo3.server.dto.GameDetailsDto;
import com.moo3.server.dto.GameSummaryDto;
import com.moo3.server.dto.PlanetDto;
import com.moo3.server.dto.PlayerDto;
import com.moo3.server.dto.StarSystemDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Преобразование сущностей игры в DTO REST API. */
@Component
public class GameMapper {

    private final ColonyService colonyService;
    private final GovernmentService governmentService;
    private final PopulationTransferService transferService;
    private final Messages messages;

    public GameMapper(ColonyService colonyService,
                      GovernmentService governmentService,
                      PopulationTransferService transferService,
                      Messages messages) {
        this.colonyService = colonyService;
        this.governmentService = governmentService;
        this.transferService = transferService;
        this.messages = messages;
    }

    public GameSummaryDto toSummary(GameEntity game, List<PlayerEntity> players) {
        long humans = players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .count();
        return new GameSummaryDto(
                game.getId(),
                game.getName(),
                game.getStatus().name(),
                game.getGalaxySize().name(),
                game.getWidthParsecs(),
                game.getHeightParsecs(),
                game.getStarCount(),
                (int) humans,
                game.getMaxHumanPlayers(),
                game.getTotalPlayers(),
                game.getTurn(),
                game.getHostPlayerId(),
                game.getGalacticEvents(),
                game.getWinnerPlayerId(),
                game.getVictoryKind() == null ? null : game.getVictoryKind().name(),
                game.getCreatedAt());
    }

    public GameDetailsDto toDetails(GameEntity game, List<PlayerEntity> players, Map<String, String> raceNames) {
        // Занятые рейсами грузовики считаются на весь состав разом: иначе на каждого
        // игрока уходил бы свой запрос — п. 4.1.1.
        Map<UUID, Integer> reserved = transferService.reservedByPlayers(
                players.stream().map(PlayerEntity::getId).toList());
        return new GameDetailsDto(
                toSummary(game, players),
                players.stream().map(player -> toPlayer(player, raceNames, reserved)).toList());
    }

    public PlayerDto toPlayer(PlayerEntity player, Map<String, String> raceNames) {
        return toPlayer(player, raceNames, transferService.reservedByPlayers(List.of(player.getId())));
    }

    private PlayerDto toPlayer(PlayerEntity player, Map<String, String> raceNames,
                               Map<UUID, Integer> reservedFreighters) {
        return new PlayerDto(
                player.getId(),
                player.getSlot(),
                player.getName(),
                player.getPlayerType().name(),
                player.getRaceCode(),
                // Своя раса называется так, как назвал её игрок; справочная — как в каталоге.
                player.getRaceName() == null ? raceNames.get(player.getRaceCode()) : player.getRaceName(),
                player.getColor(),
                player.getHomeSystemId(),
                player.getCredits(),
                governmentService.government(player),
                player.getEspionagePoints(),
                player.getFreighters(),
                reservedFreighters.getOrDefault(player.getId(), 0),
                turnEnded(player));
    }

    /**
     * Закончил ли игрок текущий ход — п. 11.1. ИИ считается закончившим всегда: своих
     * решений он не принимает и ждать себя не заставляет.
     */
    private Boolean turnEnded(PlayerEntity player) {
        if (player.getPlayerType() != PlayerType.HUMAN) {
            return Boolean.TRUE;
        }
        return player.getEndedTurn() >= player.getGame().getTurn();
    }

    /**
     * Карта галактики: звёзды отдаются все, подробности — только по разведанным системам.
     *
     * @param exploredIds системы, о которых игрок знает больше, чем положение звезды
     * @param scannedIds  системы в дальности сканеров — п. 15
     * @param occupiedIds системы, где сканер заметил чужое присутствие — п. 15
     */
    public GalaxyMapDto toMap(GameEntity game,
                              List<StarSystemEntity> systems,
                              Set<UUID> exploredIds,
                              Set<UUID> scannedIds,
                              Set<UUID> occupiedIds,
                              Double minStarDistanceParsecs,
                              Boolean omniscient) {
        // Колониям нужны изученное владельцем — п. 9 — и построенные здания — п. 10;
        // всё это берётся на планеты карты разом, иначе на каждую колонию уходили бы
        // свои запросы.
        ColonyService.ColonyContext context = colonyService.context(
                systems.stream().flatMap(system -> system.getPlanets().stream()).toList());

        return new GalaxyMapDto(
                game.getId(),
                game.getGalaxySize().name(),
                game.getWidthParsecs(),
                game.getHeightParsecs(),
                minStarDistanceParsecs,
                systems.stream()
                        .map(system -> exploredIds.contains(system.getId())
                                ? toSystem(system, context)
                                : toUnexploredSystem(system,
                                        scannedIds.contains(system.getId()),
                                        occupiedIds.contains(system.getId()),
                                        omniscient))
                        .toList());
    }

    /**
     * Неразведанная система: на карте это безымянная звезда.
     * <p>
     * Название, планеты и владельца не отдаём вовсе — клиент не может показать того,
     * чего ему не прислали. Метку Orion скрываем вместе с названием: она опознаёт звезду
     * не хуже него.
     * <p>
     * Сканеры добавляют к звезде один факт: накрыта ли она ими и видно ли там чужое
     * присутствие. Состав системы это не раскрывает — за ним нужен флот (п. 15).
     */
    public StarSystemDto toUnexploredSystem(StarSystemEntity system, Boolean scanned,
                                            Boolean occupied, Boolean omniscient) {
        return new StarSystemDto(
                system.getId(),
                null,
                system.getXParsec(),
                system.getYParsec(),
                system.getStarColor().name(),
                system.getStarColor().getHexColor(),
                Boolean.FALSE,
                null,
                List.of(),
                Boolean.FALSE,
                scanned,
                occupied,
                // Сторожа неразведанной системы видит только всевидящая раса — п. 7, п. 11.1.
                Boolean.TRUE.equals(omniscient) && Boolean.TRUE.equals(system.hasLiveMonster())
                        ? messages.label(system.getMonster())
                        : null);
    }

    public StarSystemDto toSystem(StarSystemEntity system, ColonyService.ColonyContext context) {
        List<PlanetDto> planets = system.getPlanets().stream()
                .map(planet -> toPlanet(planet, context))
                .toList();
        UUID owner = system.getPlanets().stream()
                .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                .map(PlanetEntity::getOwnerPlayerId)
                .findFirst()
                .orElse(null);
        return new StarSystemDto(
                system.getId(),
                system.getName(),
                system.getXParsec(),
                system.getYParsec(),
                system.getStarColor().name(),
                system.getStarColor().getHexColor(),
                system.getSpecial(),
                owner,
                planets,
                Boolean.TRUE,
                // Разведанной системе сканеры ничего не добавляют: о ней и так известно всё.
                Boolean.TRUE,
                Boolean.FALSE,
                Boolean.TRUE.equals(system.hasLiveMonster()) ? messages.label(system.getMonster()) : null);
    }

    /** Одна планета — для ответа на действие игрока с её колонией. */
    public PlanetDto toPlanet(PlanetEntity planet) {
        return toPlanet(planet, colonyService.context(planet));
    }

    public PlanetDto toPlanet(PlanetEntity planet, ColonyService.ColonyContext context) {
        return new PlanetDto(
                planet.getId(),
                planet.getOrbit(),
                planet.getName(),
                planet.getPlanetSize().name(),
                planet.getPlanetSize().getLabel(),
                planet.getClimate().name(),
                planet.getClimate().getLabel(),
                planet.getClimate().getPopulationMultiplierPercent(),
                planet.getClimate().getColonizable(),
                planet.getClimate().getFoodPerFarmer(),
                PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld()).name(),
                messages.label(PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())),
                PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())
                        .productionPercent(Boolean.FALSE, Boolean.FALSE),
                planet.getMinerals().name(),
                planet.getMinerals().getLabel(),
                planet.getMinerals().getProductionPerWorker(),
                planet.getMaxPopulation(),
                planet.getPopulation(),
                planet.getOwnerPlayerId(),
                planet.getHomeworld(),
                colonyService.colony(planet, context),
                planet.getColonyBaseReady(),
                // Находка — п. 4.1. Имя и описание собираются на языке запроса: ключи
                // лежат в messages, потому что о находке рассказывает и отчёт хода.
                planet.getFind() == null ? null : planet.getFind().name(),
                planet.getFind() == null ? null : messages.label(planet.getFind()),
                planet.getFind() == null ? null
                        : messages.get("planetFind." + planet.getFind().name() + ".description"));
    }
}
