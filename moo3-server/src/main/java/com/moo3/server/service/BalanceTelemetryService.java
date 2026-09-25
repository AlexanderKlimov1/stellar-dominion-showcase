package com.moo3.server.service;

import com.moo3.server.domain.entity.EmpireActivityEntity;
import com.moo3.server.domain.entity.EmpireHistoryEntity;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.dto.BalanceTelemetryDto;
import com.moo3.server.repository.EmpireActivityRepository;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.EmpireHistoryRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.StarSystemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Телеметрия партии для балансировки — этап 0 (`balance-metrics-works.txt`).
 * <p>
 * Отдаёт партию одним слепком: условия, империи с их расами и стартовым положением и
 * летопись каждой по ходам. Отсюда растут все замеры ценности особенностей расы, поэтому
 * здесь только чтение — ни одна цифра тут не считается заново, кроме мощи (её формула одна
 * на игру, {@link EmpireMightRules}) и ковариат стартового угла.
 * <p>
 * <b>Ковариаты положения считаются по нынешней карте, а не по стартовой.</b> Звёзды и их
 * расстояния не меняются за партию вовсе, а климат планеты меняется только
 * терраформированием — редко и поздно. Хранить стартовый снимок ради этого значило бы
 * завести вторую копию галактики; когда терраформирование станет частым, это место и
 * придётся поправить.
 */
@Service
public class BalanceTelemetryService {

    /** В каком радиусе от родной звезды считаются пригодные планеты — ковариата угла. */
    public static final double NEARBY_PARSECS = 5.0;

    private final GameAccess gameAccess;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository starSystemRepository;
    private final EmpireHistoryRepository historyRepository;
    private final EmpireActivityRepository activityRepository;
    private final RaceTraitCatalog raceTraits;
    private final EmpireMightRules mightRules;
    private final PlanetBuildingRepository buildingRepository;

    public BalanceTelemetryService(GameAccess gameAccess,
                                   PlayerRepository playerRepository,
                                   StarSystemRepository starSystemRepository,
                                   EmpireHistoryRepository historyRepository,
                                   EmpireActivityRepository activityRepository,
                                   RaceTraitCatalog raceTraits,
                                   EmpireMightRules mightRules,
                                   PlanetBuildingRepository buildingRepository) {
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
        this.starSystemRepository = starSystemRepository;
        this.historyRepository = historyRepository;
        this.activityRepository = activityRepository;
        this.raceTraits = raceTraits;
        this.mightRules = mightRules;
        this.buildingRepository = buildingRepository;
    }

    /**
     * Слепок партии для балансировки.
     * <p>
     * Спрашивается участником партии: телеметрия рассказывает про все империи разом, и
     * в чужой партии ей делать нечего.
     */
    @Transactional(readOnly = true)
    public BalanceTelemetryDto of(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameAccess.requirePlayer(game, accessToken);

        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(gameId).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        List<PlanetEntity> planets = systems.stream()
                .flatMap(system -> system.getPlanets().stream())
                .toList();

        Map<UUID, StarSystemEntity> systemById = new HashMap<>();
        systems.forEach(system -> systemById.put(system.getId(), system));

        // Чем империи пользовались — п. 6 плана, «ИИ не измеряет». Счётчик один на всё:
        // бои считаются там же, где происходят, и «авто» наравне с тактическим боем.
        Map<UUID, Map<String, Integer>> used = new HashMap<>();
        for (EmpireActivityEntity row : activityRepository
                .findAllByGameIdOrderByPlayerIdAscCodeAsc(gameId)) {
            used.computeIfAbsent(row.getPlayerId(), id -> new LinkedHashMap<>())
                    .put(row.getCode(), row.getTimes());
        }

        Map<UUID, List<EmpireHistoryEntity>> historyByPlayer = new HashMap<>();
        for (EmpireHistoryEntity row : historyRepository.findAllByGameIdOrderByTurnAsc(gameId)) {
            historyByPlayer.computeIfAbsent(row.getPlayerId(), id -> new ArrayList<>()).add(row);
        }

        List<BalanceTelemetryDto.EmpireTelemetryDto> empires = new ArrayList<>(players.size());
        for (PlayerEntity player : players) {
            empires.add(empire(player, players, planets, systemById,
                    used.getOrDefault(player.getId(), Map.of()),
                    historyByPlayer.getOrDefault(player.getId(), List.of())));
        }

        PlayerEntity winner = players.stream()
                .filter(player -> player.getId().equals(game.getWinnerPlayerId()))
                .findFirst()
                .orElse(null);

        return new BalanceTelemetryDto(
                game.getId(),
                game.getName(),
                game.getSeed(),
                game.getGalaxySize().name(),
                game.getStarCount(),
                game.getTurn(),
                game.getStatus().name(),
                winner == null ? null : winner.getSlot(),
                game.getVictoryKind() == null ? null : game.getVictoryKind().name(),
                empires,
                built(planets, players));
    }

    /**
     * Построенное за партию — п. 6 плана: этим проверяется холостой ход.
     * <p>
     * Ход постройки хранит сама постройка, поэтому партию не приходится опрашивать по
     * ходам: одного запроса в конце хватает на весь разбор. Порядок — по ходу постройки,
     * потом по названию планеты: разбор ходит по нему сверху вниз, и он обязан быть
     * одинаков в двух прогонах одной партии (этап 0).
     */
    private List<BalanceTelemetryDto.BuiltDto> built(List<PlanetEntity> planets,
                                                     List<PlayerEntity> players) {
        Map<UUID, Integer> slotByPlayer = new HashMap<>();
        players.forEach(player -> slotByPlayer.put(player.getId(), player.getSlot()));
        Map<UUID, PlanetEntity> planetById = new HashMap<>();
        planets.forEach(planet -> planetById.put(planet.getId(), planet));

        return buildingRepository.findAllByPlanetIdIn(planetById.keySet()).stream()
                .map(row -> {
                    PlanetEntity planet = planetById.get(row.getPlanetId());
                    if (planet == null) {
                        return null;
                    }
                    return new BalanceTelemetryDto.BuiltDto(
                            slotByPlayer.get(planet.getOwnerPlayerId()),
                            planet.getName(),
                            row.getBuildingCode(),
                            row.getBuiltTurn());
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(BalanceTelemetryDto.BuiltDto::builtTurn)
                        .thenComparing(BalanceTelemetryDto.BuiltDto::planet)
                        .thenComparing(BalanceTelemetryDto.BuiltDto::code))
                .toList();
    }

    private BalanceTelemetryDto.EmpireTelemetryDto empire(PlayerEntity player,
                                                          List<PlayerEntity> players,
                                                          List<PlanetEntity> planets,
                                                          Map<UUID, StarSystemEntity> systems,
                                                          Map<String, Integer> used,
                                                          List<EmpireHistoryEntity> history) {
        StarSystemEntity home = systems.get(player.getHomeSystemId());
        PlanetEntity homeworld = planets.stream()
                .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                .filter(planet -> player.getId().equals(planet.getOwnerPlayerId()))
                .findFirst()
                .orElse(null);

        List<BalanceTelemetryDto.TurnRowDto> rows = history.stream()
                .map(row -> new BalanceTelemetryDto.TurnRowDto(
                        row.getTurn(),
                        row.getPopulationK(),
                        row.getColonies(),
                        row.getBuildings(),
                        row.getProduction(),
                        row.getResearch(),
                        row.getFleetPower(),
                        row.getTechnologies(),
                        row.getCredits(),
                        row.getIncome(),
                        mightRules.might(row.getFleetPower(), row.getPopulationK(),
                                row.getProduction(), row.getResearch(), row.getTechnologies())))
                .toList();

        return new BalanceTelemetryDto.EmpireTelemetryDto(
                player.getId(),
                player.getSlot(),
                player.getName(),
                player.getRaceCode(),
                player.getRaceName(),
                // Купленный набор, а не нынешний: развитый строй заменяет собой прежний и
                // ничего не стоит, и бюджет расы падал бы на его цену прямо посреди партии.
                raceTraits.asPicked(player.getRaceTraitCodes()),
                raceTraits.government(player.getRaceTraitCodes()),
                player.getAiPersonality() == null ? null : player.getAiPersonality().name(),
                player.getAiObjective() == null ? null : player.getAiObjective().name(),
                player.getPlayerType() == PlayerType.AI,
                homeworld == null ? null : homeworld.getPlanetSize().name(),
                homeworld == null ? null : homeworld.getClimate().name(),
                homeworld == null ? null : homeworld.getMinerals().name(),
                nearbyPlanets(home, planets, systems),
                nearestRival(home, player, players, systems),
                used,
                rows);
    }

    /**
     * Сколько пригодных для колонизации планет лежит в {@link #NEARBY_PARSECS} парсеках от
     * родной звезды — главная ковариата стартового угла: с ней регрессия отделяет силу
     * расы от щедрости карты.
     */
    private Integer nearbyPlanets(StarSystemEntity home, List<PlanetEntity> planets,
                                  Map<UUID, StarSystemEntity> systems) {
        if (home == null) {
            return 0;
        }
        int count = 0;
        for (PlanetEntity planet : planets) {
            if (!Boolean.TRUE.equals(planet.getClimate().getColonizable())) {
                continue;
            }
            StarSystemEntity system = systems.get(planet.getStarSystem().getId());
            if (system != null && distance(home, system) <= NEARBY_PARSECS) {
                count++;
            }
        }
        return count;
    }

    /** Насколько близко стоит ближайший сосед: теснота угла решает не меньше, чем щедрость. */
    private Double nearestRival(StarSystemEntity home, PlayerEntity player,
                                List<PlayerEntity> players, Map<UUID, StarSystemEntity> systems) {
        if (home == null) {
            return null;
        }
        return players.stream()
                .filter(other -> !other.getId().equals(player.getId()))
                .map(other -> systems.get(other.getHomeSystemId()))
                .filter(system -> system != null)
                .map(system -> distance(home, system))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private Double distance(StarSystemEntity first, StarSystemEntity second) {
        double dx = first.getXParsec() - second.getXParsec();
        double dy = first.getYParsec() - second.getYParsec();
        return Math.round(Math.sqrt(dx * dx + dy * dy) * 10.0) / 10.0;
    }
}
