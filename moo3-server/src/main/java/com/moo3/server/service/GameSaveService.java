package com.moo3.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.FleetEntity;
import com.moo3.server.domain.entity.FleetShipEntity;
import com.moo3.server.domain.entity.ShipDesignComponentEntity;
import com.moo3.server.domain.entity.ShipDesignEntity;
import com.moo3.server.domain.entity.GameSaveEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.PopulationJobs;
import com.moo3.server.domain.entity.EmpireHistoryEntity;
import com.moo3.server.domain.entity.PlayerTechnologyEntity;
import com.moo3.server.domain.entity.StarSystemEntity;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.domain.enums.PlayerType;
import com.moo3.server.domain.save.GameSnapshot;
import com.moo3.server.domain.entity.PlanetBuildingEntity;
import com.moo3.server.domain.save.PlanetBuildingSnapshot;
import com.moo3.server.domain.save.PlanetSnapshot;
import com.moo3.server.domain.save.PlayerSnapshot;
import com.moo3.server.domain.save.DiplomacyRelationSnapshot;
import com.moo3.server.domain.save.FleetShipSnapshot;
import com.moo3.server.domain.save.ShipDesignComponentSnapshot;
import com.moo3.server.domain.save.ShipDesignSnapshot;
import com.moo3.server.domain.enums.ShipRole;
import com.moo3.server.domain.save.FleetSnapshot;
import com.moo3.server.domain.save.PopulationTransferSnapshot;
import com.moo3.server.domain.save.EmpireHistorySnapshot;
import com.moo3.server.domain.save.PlayerTechnologySnapshot;
import com.moo3.server.domain.save.SpySnapshot;
import java.util.LinkedHashSet;
import java.util.Set;
import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.entity.SpyEntity;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.repository.SpyRepository;
import com.moo3.server.domain.save.StarSystemSnapshot;
import com.moo3.server.dto.GameSaveDto;
import com.moo3.server.repository.GameRepository;
import com.moo3.server.domain.entity.PopulationTransferEntity;
import com.moo3.server.repository.FleetRepository;
import com.moo3.server.repository.FleetShipRepository;
import com.moo3.server.repository.ShipDesignComponentRepository;
import com.moo3.server.repository.ShipDesignRepository;
import com.moo3.server.repository.PopulationTransferRepository;
import com.moo3.server.repository.GameSaveRepository;
import com.moo3.server.repository.GameSaveSummary;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.repository.PlanetBuildingRepository;
import com.moo3.server.repository.EmpireHistoryRepository;
import com.moo3.server.repository.PlayerTechnologyRepository;
import com.moo3.server.repository.StarSystemRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Сохранение и загрузка партий — состояние игры целиком лежит в таблице {@code game_save}.
 * <p>
 * Слепок снимается на конец завершённого хода: автоматически, когда игрок заканчивает ход,
 * и по команде «Игра» → «Сохранить» — тогда в слепок идёт состояние последнего
 * завершённого хода, потому что внутри текущего хода состояние ещё не менялось.
 * <p>
 * Автосохранение на партию одно: запись переписывается каждый ход, поэтому список загрузки
 * не растёт от игры, а в самом списке автосохранения идут первыми.
 * <p>
 * Загрузка не трогает исходную партию: из слепка создаётся новая игра со своими
 * идентификаторами и пропусками игроков, поэтому одно сохранение можно загружать
 * сколько угодно раз, и удаление исходной игры сохранению не мешает.
 */
@Service
public class GameSaveService {

    /**
     * Сколько сохранений отдаётся списком: диалог загрузки показывает последние, а всего
     * их в базе со временем накапливаются сотни.
     */
    private static final int SAVES_PAGE = 50;

    private static final Logger log = LoggerFactory.getLogger(GameSaveService.class);

    private final GameSaveRepository gameSaveRepository;
    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository starSystemRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final EmpireHistoryRepository empireHistoryRepository;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final SpyRepository spyRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final FleetRepository fleetRepository;
    private final FleetShipRepository fleetShipRepository;
    private final ShipDesignRepository shipDesignRepository;
    private final ShipDesignComponentRepository shipDesignComponentRepository;
    private final ShipDesignService shipDesignService;
    private final PopulationTransferRepository transferRepository;
    private final PlayerRoster playerRoster;
    private final ObjectMapper objectMapper;

    public GameSaveService(GameSaveRepository gameSaveRepository,
                           GameRepository gameRepository,
                           PlayerRepository playerRepository,
                           StarSystemRepository starSystemRepository,
                           PlayerTechnologyRepository playerTechnologyRepository,
                           EmpireHistoryRepository empireHistoryRepository,
                           PlanetBuildingRepository planetBuildingRepository,
                           SpyRepository spyRepository,
                           DiplomacyRelationRepository relationRepository,
                           FleetRepository fleetRepository,
                           FleetShipRepository fleetShipRepository,
                           ShipDesignRepository shipDesignRepository,
                           ShipDesignComponentRepository shipDesignComponentRepository,
                           ShipDesignService shipDesignService,
                           PopulationTransferRepository transferRepository,
                           PlayerRoster playerRoster,
                           ObjectMapper objectMapper) {
        this.gameSaveRepository = gameSaveRepository;
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.starSystemRepository = starSystemRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.empireHistoryRepository = empireHistoryRepository;
        this.planetBuildingRepository = planetBuildingRepository;
        this.spyRepository = spyRepository;
        this.relationRepository = relationRepository;
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.shipDesignRepository = shipDesignRepository;
        this.shipDesignComponentRepository = shipDesignComponentRepository;
        this.shipDesignService = shipDesignService;
        this.transferRepository = transferRepository;
        this.playerRoster = playerRoster;
        this.objectMapper = objectMapper;
    }

    /**
     * Список сохранений для диалога загрузки: свежие сверху.
     * <p>
     * Читается проекцией и страницей: сам слепок партии весит сотню килобайт, а списку
     * нужны только название, ход и время. Раньше на один клик по «Загрузить» поднимались
     * в память все слепки разом.
     */
    @Transactional(readOnly = true)
    public List<GameSaveDto> list() {
        return gameSaveRepository.findAllByOrderBySavedAtDesc(PageRequest.of(0, SAVES_PAGE)).stream()
                .map(this::toDto)
                .toList();
    }

    /** Сохранения удалённой партии: без партии слепок никому не нужен. */
    @Transactional
    public void deleteByGame(UUID gameId) {
        gameSaveRepository.deleteByGameId(gameId);
    }

    /**
     * Снимает слепок партии на конец завершённого хода.
     *
     * @param completedTurn номер завершённого хода; 0 — партия ещё не сыграла ни одного
     */
    @Transactional
    public GameSaveDto save(GameEntity game, Integer completedTurn) {
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(game.getId()).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        if (systems.isEmpty()) {
            throw new ConflictException("save.nothingToSave");
        }

        GameSnapshot snapshot = toSnapshot(game, players, systems, completedTurn);

        GameSaveEntity save = new GameSaveEntity();
        save.setGameId(game.getId());
        save.setName(game.getName());
        save.setGalaxySize(game.getGalaxySize().name());
        save.setWidthParsecs(game.getWidthParsecs());
        save.setHeightParsecs(game.getHeightParsecs());
        save.setStarCount(systems.size());
        save.setTurn(completedTurn);
        save.setTotalPlayers(game.getTotalPlayers());
        save.setHumanPlayers((int) players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .count());
        save.setSavedAt(OffsetDateTime.now());
        save.setState(serialize(snapshot));

        GameSaveEntity saved = gameSaveRepository.saveAndFlush(save);
        log.info("Сохранена партия {} на конец хода {}: {} систем, {} игроков",
                game.getName(), completedTurn, systems.size(), players.size());
        return toDto(saved);
    }

    /** Удаление сохранения: слепок самодостаточен, на партию его удаление не влияет. */
    @Transactional
    public void delete(UUID saveId) {
        GameSaveEntity save = gameSaveRepository.findById(saveId)
                .orElseThrow(() -> new NotFoundException("save.notFound", saveId));
        gameSaveRepository.delete(save);
        log.info("Удалено сохранение {}: партия {}, ход {}", saveId, save.getName(), save.getTurn());
    }

    /**
     * Восстанавливает партию из сохранения новой игрой.
     *
     * @return созданная игра вместе с игроками и свежими пропусками
     */
    @Transactional
    public GameEntity restore(UUID saveId) {
        GameSaveEntity save = gameSaveRepository.findById(saveId)
                .orElseThrow(() -> new NotFoundException("save.notFound", saveId));
        GameSnapshot snapshot = deserialize(save.getState());

        GameEntity game = new GameEntity();
        game.setName(snapshot.name());
        game.setGalaxySize(snapshot.galaxySize());
        game.setWidthParsecs(snapshot.widthParsecs());
        game.setHeightParsecs(snapshot.heightParsecs());
        game.setStarCount(snapshot.starCount());
        game.setStatus(GameStatus.IN_PROGRESS);
        // Партия продолжается с хода, следующего за завершённым.
        game.setTurn(snapshot.completedTurn() + 1);
        game.setTotalPlayers(snapshot.totalPlayers());
        game.setMaxHumanPlayers(snapshot.maxHumanPlayers());
        game.setSeed(snapshot.seed());
        // Слепки, снятые до случайных событий, о них не знают: такая партия загружается
        // с событиями — в MOO II они включены, пока их не выключили (п. 11.1).
        game.setGalacticEvents(snapshot.galacticEvents() == null
                || Boolean.TRUE.equals(snapshot.galacticEvents()));
        // То же и с советом: слепок прошлой версии о нём не знает, и партия поднимается
        // с советом — как в оригинале (п. 3).
        game.setCouncil(snapshot.council() == null || Boolean.TRUE.equals(snapshot.council()));
        game.setCouncilRefused(Boolean.TRUE.equals(snapshot.councilRefused()));
        game.setCreatedAt(OffsetDateTime.now());
        game.setStartedAt(OffsetDateTime.now());
        gameRepository.saveAndFlush(game);

        // Игроков и системы сохраняем своими репозиториями, а не через коллекции игры:
        // сохранение уже существующей игры идёт через merge, а он раздаёт идентификаторы
        // копиям объектов, и ссылки на родные миры собрать было бы не из чего.
        List<PlayerEntity> players = new ArrayList<>(snapshot.players().size());
        for (PlayerSnapshot snapshotPlayer : snapshot.players()) {
            PlayerEntity player = restorePlayer(snapshotPlayer);
            player.setGame(game);
            // Слепок мог быть снят до появления характеров: тогда соседу выдаётся свой,
            // тем же жребием, что и в начале партии, — иначе он не вёл бы дипломатии.
            if (player.getPlayerType() == PlayerType.AI && player.getAiPersonality() == null) {
                playerRoster.giveCharacter(player, game, player.getSlot());
            }
            players.add(player);
        }
        playerRepository.saveAllAndFlush(players);
        restoreTechnologies(players, snapshot);
        restoreHistory(game, players, snapshot);

        Map<Integer, UUID> playerIdBySlot = new HashMap<>();
        for (PlayerEntity player : players) {
            playerIdBySlot.put(player.getSlot(), player.getId());
        }

        List<StarSystemEntity> systems = new ArrayList<>(snapshot.systems().size());
        for (StarSystemSnapshot snapshotSystem : snapshot.systems()) {
            StarSystemEntity system = restoreSystem(snapshotSystem, playerIdBySlot);
            system.setGame(game);
            systems.add(system);
        }
        starSystemRepository.saveAllAndFlush(systems);

        restoreBuildings(snapshot, systems);
        restoreSpies(snapshot, playerIdBySlot);
        restoreRelations(snapshot, playerIdBySlot);
        List<ShipDesignEntity> designs = restoreShipDesigns(snapshot, game, players, playerIdBySlot);
        restoreFleets(snapshot, playerIdBySlot, systems, designs);
        restoreTransfers(snapshot, playerIdBySlot, systems);
        linkHomeSystems(players, snapshot, systems);
        playerRepository.saveAllAndFlush(players);

        game.setHostPlayerId(playerIdBySlot.get(snapshot.hostSlot()));
        gameRepository.saveAndFlush(game);

        log.info("Загружено сохранение {}: партия {} продолжается с хода {}",
                saveId, game.getName(), game.getTurn());
        return game;
    }

    /**
     * Шпионы игроков — п. 13. Заводятся после того, как игроки получили идентификаторы:
     * агент ссылается и на владельца, и на империю, к которой отправлен.
     */
    private void restoreSpies(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot) {
        List<SpyEntity> spies = new ArrayList<>();
        for (PlayerSnapshot player : snapshot.players()) {
            // Слепки, снятые до появления заданий, шпионов поимённо не содержат.
            if (player.agents() == null) {
                continue;
            }
            for (SpySnapshot agent : player.agents()) {
                SpyEntity spy = new SpyEntity();
                spy.setOwnerPlayerId(playerIdBySlot.get(player.slot()));
                spy.setTargetPlayerId(agent.targetSlot() == null ? null : playerIdBySlot.get(agent.targetSlot()));
                spy.setMission(agent.mission());
                spy.setPoints(agent.points() == null ? 0 : agent.points());
                spy.setCreatedTurn(agent.createdTurn() == null ? 0 : agent.createdTurn());
                spies.add(spy);
            }
        }
        spyRepository.saveAll(spies);
    }

    /**
     * Проекты кораблей — п. 8: владелец назван слотом, порядок в списке слепка и есть
     * ссылка, по которой состав флота находит свой проект.
     * <p>
     * Слепок, снятый до появления дизайна, проектов не содержит: каждой империи заводится
     * стартовый проект, и загруженные корабли достаются ему. Иначе флот остался бы без
     * состава, а безымянных кораблей игра больше не знает.
     *
     * @return проекты в том же порядке, в каком они лежат в слепке
     */
    private List<ShipDesignEntity> restoreShipDesigns(GameSnapshot snapshot,
                                                      GameEntity game,
                                                      List<PlayerEntity> players,
                                                      Map<Integer, UUID> playerIdBySlot) {
        if (snapshot.shipDesigns() == null) {
            players.forEach(player ->
                    shipDesignService.ensureAutoDesigns(game.getId(), player, game.getTurn()));
            return List.of();
        }

        List<ShipDesignEntity> restored = new ArrayList<>(snapshot.shipDesigns().size());
        for (ShipDesignSnapshot source : snapshot.shipDesigns()) {
            UUID ownerId = playerIdBySlot.get(source.ownerSlot());
            if (ownerId == null) {
                continue;
            }
            ShipDesignEntity design = new ShipDesignEntity();
            design.setGameId(game.getId());
            design.setOwnerPlayerId(ownerId);
            design.setSlot(source.slot());
            design.setName(source.name());
            design.setHullCode(source.hullCode());
            design.setCreatedTurn(source.createdTurn() == null ? 0 : source.createdTurn());
            design.setObsolete(Boolean.TRUE.equals(source.obsolete()));
            // Слепок мог быть снят до появления гражданских кораблей: тогда роли нет,
            // и проект боевой — других в той партии и не было.
            design.setRole(source.role() == null
                    ? ShipRole.WARSHIP
                    : ShipRole.valueOf(source.role()));
            restored.add(design);
        }
        shipDesignRepository.saveAllAndFlush(restored);

        List<ShipDesignComponentEntity> components = new ArrayList<>();
        for (int index = 0; index < restored.size(); index++) {
            ShipDesignSnapshot source = snapshot.shipDesigns().get(index);
            if (source.components() == null) {
                continue;
            }
            int order = 0;
            for (ShipDesignComponentSnapshot part : source.components()) {
                ShipDesignComponentEntity component = new ShipDesignComponentEntity();
                component.setDesignId(restored.get(index).getId());
                component.setComponentCode(part.componentCode());
                component.setCount(part.count() == null ? 1 : part.count());
                component.setSortOrder(order++);
                components.add(component);
            }
        }
        shipDesignComponentRepository.saveAll(components);
        return restored;
    }

    /**
     * Флоты — п. 8: владелец назван слотом, система — номером в списке слепка.
     * <p>
     * Встречи флотов в слепок не идут: встреча живёт один ход и ждёт решения игрока.
     * Загруженная партия начинается с начала хода, и флоты в ней встретятся заново.
     */
    private void restoreFleets(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot,
                               List<StarSystemEntity> systems, List<ShipDesignEntity> designs) {
        // Слепки, снятые до флотов, их не содержат.
        if (snapshot.fleets() == null) {
            return;
        }
        List<FleetEntity> fleets = new ArrayList<>();
        List<FleetSnapshot> sources = new ArrayList<>();
        for (FleetSnapshot source : snapshot.fleets()) {
            UUID ownerId = playerIdBySlot.get(source.ownerSlot());
            if (ownerId == null || source.systemIndex() >= systems.size()) {
                continue;
            }
            FleetEntity fleet = new FleetEntity();
            fleet.setGameId(systems.get(source.systemIndex()).getGame().getId());
            fleet.setOwnerPlayerId(ownerId);
            fleet.setStarSystemId(systems.get(source.systemIndex()).getId());
            fleet.setShips(source.ships());
            fleet.setCreatedTurn(source.createdTurn() == null ? 0 : source.createdTurn());
            // Флот, застигнутый сохранением в пути, продолжает лететь: маршрут в слепке
            // назван номерами систем, потому что звёзды при загрузке заводятся заново.
            // У слепков, снятых до того, как перелёт стал занимать ходы, маршрута нет.
            if (source.targetIndex() != null && source.arrivalTurn() != null
                    && source.targetIndex() < systems.size()) {
                fleet.setOriginSystemId(systems.get(source.systemIndex()).getId());
                fleet.setTargetSystemId(systems.get(source.targetIndex()).getId());
                fleet.setDepartureTurn(source.departureTurn());
                fleet.setArrivalTurn(source.arrivalTurn());
            }
            fleets.add(fleet);
            sources.add(source);
        }
        fleetRepository.saveAllAndFlush(fleets);
        restoreComposition(fleets, sources, designs);
    }

    /**
     * Состав флотов — п. 8. У слепка, снятого до дизайна кораблей, состава нет: все
     * корабли флота приписываются стартовому проекту его империи, и флот перестаёт быть
     * безымянным счётчиком.
     */
    private void restoreComposition(List<FleetEntity> fleets, List<FleetSnapshot> sources,
                                    List<ShipDesignEntity> designs) {
        Map<UUID, ShipDesignEntity> firstDesignByOwner = new HashMap<>();
        for (ShipDesignEntity design : designs) {
            if (!Boolean.TRUE.equals(design.getObsolete())) {
                firstDesignByOwner.putIfAbsent(design.getOwnerPlayerId(), design);
            }
        }

        List<FleetShipEntity> rows = new ArrayList<>();
        for (int index = 0; index < fleets.size(); index++) {
            FleetEntity fleet = fleets.get(index);
            List<FleetShipSnapshot> composition = sources.get(index).composition();
            if (composition == null || composition.isEmpty()) {
                ShipDesignEntity design = firstDesignByOwner.get(fleet.getOwnerPlayerId());
                if (design == null) {
                    continue;
                }
                rows.add(fleetShip(fleet.getId(), design.getId(), fleet.getShips(), 0));
                continue;
            }
            for (FleetShipSnapshot part : composition) {
                if (part.designIndex() == null || part.designIndex() >= designs.size()) {
                    continue;
                }
                rows.add(fleetShip(fleet.getId(), designs.get(part.designIndex()).getId(), part.ships(),
                        part.colonists() == null ? 0 : part.colonists()));
            }
        }
        fleetShipRepository.saveAll(rows);
    }

    private FleetShipEntity fleetShip(UUID fleetId, UUID designId, Integer ships, Integer colonists) {
        FleetShipEntity row = new FleetShipEntity();
        row.setFleetId(fleetId);
        row.setDesignId(designId);
        row.setShips(ships);
        row.setColonists(colonists);
        return row;
    }

    /**
     * Рейсы с жителями — п. 4.1.1: планеты названы номерами системы и орбиты в списках
     * слепка. Загруженная партия продолжает возить их дальше, а грузовики остаются
     * занятыми — правило резерва от загрузки не меняется.
     */
    private void restoreTransfers(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot,
                                  List<StarSystemEntity> systems) {
        // Слепки, снятые до перевозок, рейсов не содержат.
        if (snapshot.transfers() == null) {
            return;
        }
        List<PopulationTransferEntity> transfers = new ArrayList<>();
        for (PopulationTransferSnapshot source : snapshot.transfers()) {
            UUID owner = playerIdBySlot.get(source.ownerSlot());
            UUID from = planetAt(systems, source.fromSystemIndex(), source.fromPlanetIndex());
            UUID to = planetAt(systems, source.toSystemIndex(), source.toPlanetIndex());
            if (owner == null || from == null || to == null) {
                continue;
            }
            PopulationTransferEntity transfer = new PopulationTransferEntity();
            transfer.setGameId(systems.getFirst().getGame().getId());
            transfer.setOwnerPlayerId(owner);
            transfer.setFromPlanetId(from);
            transfer.setToPlanetId(to);
            transfer.setPopulation(source.population());
            transfer.setDepartedTurn(source.departedTurn() == null ? 0 : source.departedTurn());
            transfers.add(transfer);
        }
        transferRepository.saveAll(transfers);
    }

    /** Планета по номерам системы и орбиты в списках слепка; {@code null} — номера негодные. */
    private UUID planetAt(List<StarSystemEntity> systems, Integer systemIndex, Integer planetIndex) {
        if (systemIndex == null || planetIndex == null
                || systemIndex < 0 || systemIndex >= systems.size()) {
            return null;
        }
        List<PlanetEntity> planets = systems.get(systemIndex).getPlanets();
        return planetIndex < 0 || planetIndex >= planets.size() ? null : planets.get(planetIndex).getId();
    }

    /** Отношения империй — п. 15: стороны названы слотами, поэтому находятся по ним. */
    private void restoreRelations(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot) {
        // Слепки, снятые до дипломатии, отношений не содержат.
        if (snapshot.relations() == null) {
            return;
        }
        List<DiplomacyRelationEntity> relations = new ArrayList<>();
        for (DiplomacyRelationSnapshot source : snapshot.relations()) {
            DiplomacyRelationEntity relation = new DiplomacyRelationEntity();
            relation.setPlayerId(playerIdBySlot.get(source.playerSlot()));
            relation.setOtherPlayerId(playerIdBySlot.get(source.otherSlot()));
            relation.setStance(source.stance());
            relation.setTrust(source.trust());
            relation.setTreaties(source.treaties() == null ? Set.of() : new LinkedHashSet<>(source.treaties()));
            relation.setMetTurn(source.metTurn());
            relations.add(relation);
        }
        relationRepository.saveAll(relations);
    }

    private GameSnapshot toSnapshot(GameEntity game,
                                    List<PlayerEntity> players,
                                    List<StarSystemEntity> systems,
                                    Integer completedTurn) {
        Map<UUID, Integer> slotByPlayerId = new HashMap<>();
        for (PlayerEntity player : players) {
            slotByPlayerId.put(player.getId(), player.getSlot());
        }
        Map<UUID, Integer> indexBySystemId = new HashMap<>();
        for (int index = 0; index < systems.size(); index++) {
            indexBySystemId.put(systems.get(index).getId(), index);
        }

        // Проекты кораблей нумеруются один раз на слепок: состав флота ссылается на них
        // номером, идентификаторов слепок не хранит — п. 8.
        List<ShipDesignEntity> designs = shipDesignRepository.findAllByGameId(game.getId()).stream()
                .sorted(GameOrder.DESIGNS).toList();
        Map<UUID, Integer> designIndexes = new HashMap<>();
        for (int index = 0; index < designs.size(); index++) {
            designIndexes.put(designs.get(index).getId(), index);
        }

        Map<UUID, List<PlayerTechnologySnapshot>> technologiesByPlayerId = technologySnapshots(players);
        Map<UUID, List<EmpireHistorySnapshot>> historyByPlayerId = historySnapshots(game);
        Map<UUID, List<SpySnapshot>> spiesByPlayerId = spySnapshots(players, slotByPlayerId);
        Map<UUID, List<PlanetBuildingSnapshot>> buildingsByPlanetId = buildingSnapshots(systems);
        List<PlayerSnapshot> playerSnapshots = players.stream()
                .map(player -> new PlayerSnapshot(
                        player.getSlot(),
                        player.getName(),
                        player.getPlayerType(),
                        player.getRaceCode(),
                        player.getColor(),
                        indexBySystemId.get(player.getHomeSystemId()),
                        player.getResearchCategoryCode(),
                        player.getResearchLevelOrder(),
                        player.getResearchOptionCode(),
                        player.getResearchPoints(),
                        technologiesByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getCredits(),
                        player.getRaceName(),
                        player.getRaceTraitCodes(),
                        player.getEspionagePoints(),
                        player.getSpies(),
                        player.getFreighters(),
                        spiesByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getAiPersonality(),
                        player.getAiObjective(),
                        historyByPlayerId.getOrDefault(player.getId(), List.of())))
                .toList();

        List<StarSystemSnapshot> systemSnapshots = systems.stream()
                .map(system -> new StarSystemSnapshot(
                        system.getName(),
                        system.getXParsec(),
                        system.getYParsec(),
                        system.getStarColor(),
                        system.getSpecial(),
                        system.getMonster(),
                        system.getMonsterStrength(),
                        system.getSpecialClaimed(),
                        planetSnapshots(system, slotByPlayerId, buildingsByPlanetId)))
                .toList();

        return new GameSnapshot(
                game.getName(),
                game.getGalaxySize(),
                game.getWidthParsecs(),
                game.getHeightParsecs(),
                game.getStarCount(),
                completedTurn,
                game.getTotalPlayers(),
                game.getMaxHumanPlayers(),
                game.getSeed(),
                slotByPlayerId.get(game.getHostPlayerId()),
                playerSnapshots,
                systemSnapshots,
                game.getGalacticEvents(),
                game.getCouncil(),
                game.getCouncilRefused(),
                relationSnapshots(players, slotByPlayerId),
                fleetSnapshots(game.getId(), slotByPlayerId, indexBySystemId, designIndexes),
                shipDesignSnapshots(designs, slotByPlayerId),
                transferSnapshots(game.getId(), slotByPlayerId, systems, indexBySystemId));
    }

    /** Рейсы с жителями — п. 4.1.1: планеты названы номерами системы и орбиты. */
    private List<PopulationTransferSnapshot> transferSnapshots(UUID gameId,
                                                               Map<UUID, Integer> slotByPlayerId,
                                                               List<StarSystemEntity> systems,
                                                               Map<UUID, Integer> indexBySystemId) {
        Map<UUID, int[]> placeByPlanetId = new HashMap<>();
        for (StarSystemEntity system : systems) {
            Integer systemIndex = indexBySystemId.get(system.getId());
            List<PlanetEntity> planets = system.getPlanets();
            for (int index = 0; index < planets.size(); index++) {
                placeByPlanetId.put(planets.get(index).getId(), new int[]{systemIndex, index});
            }
        }

        List<PopulationTransferSnapshot> snapshots = new ArrayList<>();
        for (PopulationTransferEntity transfer : transferRepository.findAllByGameId(gameId)) {
            Integer slot = slotByPlayerId.get(transfer.getOwnerPlayerId());
            int[] from = placeByPlanetId.get(transfer.getFromPlanetId());
            int[] to = placeByPlanetId.get(transfer.getToPlanetId());
            if (slot == null || from == null || to == null) {
                continue;
            }
            snapshots.add(new PopulationTransferSnapshot(slot, from[0], from[1], to[0], to[1],
                    transfer.getPopulation(), transfer.getDepartedTurn()));
        }
        return snapshots;
    }

    /**
     * Флоты партии — п. 8: владелец слотом, система номером в списке слепка, состав —
     * номерами проектов в списке проектов слепка.
     */
    private List<FleetSnapshot> fleetSnapshots(UUID gameId,
                                               Map<UUID, Integer> slotByPlayerId,
                                               Map<UUID, Integer> indexBySystemId,
                                               Map<UUID, Integer> designIndexes) {
        List<FleetEntity> fleets = fleetRepository.findAllByGameId(gameId);
        Map<UUID, List<FleetShipEntity>> composition = new HashMap<>();
        if (!fleets.isEmpty()) {
            for (FleetShipEntity row : fleetShipRepository
                    .findAllByFleetIdIn(fleets.stream().map(FleetEntity::getId).toList())) {
                composition.computeIfAbsent(row.getFleetId(), key -> new ArrayList<>()).add(row);
            }
        }

        List<FleetSnapshot> snapshots = new ArrayList<>();
        for (FleetEntity fleet : fleets) {
            Integer slot = slotByPlayerId.get(fleet.getOwnerPlayerId());
            Integer index = indexBySystemId.get(fleet.getStarSystemId());
            if (slot == null || index == null || fleet.getShips() <= 0) {
                continue;
            }
            List<FleetShipSnapshot> ships = new ArrayList<>();
            for (FleetShipEntity row : composition.getOrDefault(fleet.getId(), List.of())) {
                Integer designIndex = designIndexes.get(row.getDesignId());
                if (designIndex != null) {
                    ships.add(new FleetShipSnapshot(designIndex, row.getShips(), row.getColonists()));
                }
            }
            Integer targetIndex = fleet.getTargetSystemId() == null
                    ? null
                    : indexBySystemId.get(fleet.getTargetSystemId());
            snapshots.add(new FleetSnapshot(slot, index, fleet.getShips(), fleet.getCreatedTurn(), ships,
                    targetIndex, fleet.getDepartureTurn(), fleet.getArrivalTurn()));
        }
        return snapshots;
    }

    /**
     * Проекты кораблей партии — п. 8. Сохраняются и вытесненные: по ним летают корабли,
     * и без них состав загруженного флота остался бы безымянным.
     */
    private List<ShipDesignSnapshot> shipDesignSnapshots(List<ShipDesignEntity> designs,
                                                         Map<UUID, Integer> slotByPlayerId) {
        Map<UUID, List<ShipDesignComponentEntity>> parts = new HashMap<>();
        if (!designs.isEmpty()) {
            for (ShipDesignComponentEntity component : shipDesignComponentRepository
                    .findAllByDesignIdInOrderBySortOrderAsc(designs.stream().map(ShipDesignEntity::getId).toList())) {
                parts.computeIfAbsent(component.getDesignId(), key -> new ArrayList<>()).add(component);
            }
        }

        List<ShipDesignSnapshot> snapshots = new ArrayList<>(designs.size());
        for (ShipDesignEntity design : designs) {
            List<ShipDesignComponentSnapshot> components = parts.getOrDefault(design.getId(), List.of())
                    .stream()
                    .map(part -> new ShipDesignComponentSnapshot(part.getComponentCode(), part.getCount()))
                    .toList();
            snapshots.add(new ShipDesignSnapshot(
                    slotByPlayerId.get(design.getOwnerPlayerId()),
                    design.getSlot(),
                    design.getName(),
                    design.getHullCode(),
                    design.getCreatedTurn(),
                    design.getObsolete(),
                    design.getRole().name(),
                    components));
        }
        return snapshots;
    }

    /**
     * Отношения империй — п. 15. Стороны названы слотами: при загрузке игроки заводятся
     * заново, и старые идентификаторы ни на что не указывают.
     */
    private List<DiplomacyRelationSnapshot> relationSnapshots(List<PlayerEntity> players,
                                                              Map<UUID, Integer> slotByPlayerId) {
        List<DiplomacyRelationSnapshot> snapshots = new ArrayList<>();
        for (PlayerEntity player : players) {
            for (DiplomacyRelationEntity relation : relationRepository.findAllByPlayerId(player.getId())) {
                Integer otherSlot = slotByPlayerId.get(relation.getOtherPlayerId());
                if (otherSlot == null) {
                    continue;
                }
                snapshots.add(new DiplomacyRelationSnapshot(
                        slotByPlayerId.get(player.getId()),
                        otherSlot,
                        relation.getStance(),
                        relation.getTrust(),
                        List.copyOf(relation.getTreaties()),
                        relation.getMetTurn()));
            }
        }
        return snapshots;
    }

    /** Шпионы игрока с их заданиями — п. 13: в слепке они лежат внутри своего игрока. */
    private Map<UUID, List<SpySnapshot>> spySnapshots(List<PlayerEntity> players,
                                                      Map<UUID, Integer> slotByPlayerId) {
        Map<UUID, List<SpySnapshot>> byPlayer = new HashMap<>();
        for (SpyEntity spy : spyRepository.findAllByOwnerPlayerIdIn(slotByPlayerId.keySet()).stream()
                .sorted(GameOrder.SPIES).toList()) {
            byPlayer.computeIfAbsent(spy.getOwnerPlayerId(), key -> new ArrayList<>())
                    .add(new SpySnapshot(
                            spy.getMission(),
                            spy.getPoints(),
                            slotByPlayerId.get(spy.getTargetPlayerId()),
                            spy.getCreatedTurn()));
        }
        return byPlayer;
    }

    /** Изученные технологии игроков — п. 9: в слепке они лежат внутри своего игрока. */
    private Map<UUID, List<PlayerTechnologySnapshot>> technologySnapshots(List<PlayerEntity> players) {
        List<UUID> playerIds = players.stream().map(PlayerEntity::getId).toList();
        return playerTechnologyRepository.findAllByPlayerIdIn(playerIds).stream()
                .collect(Collectors.groupingBy(
                        PlayerTechnologyEntity::getPlayerId,
                        Collectors.mapping(technology -> new PlayerTechnologySnapshot(
                                technology.getCategoryCode(),
                                technology.getLevelOrder(),
                                technology.getOptionCode(),
                                technology.getAcquiredTurn()), Collectors.toList())));
    }

    /**
     * Летопись империй — п. 11.1: в слепке она лежит внутри своего игрока.
     * <p>
     * Пересчитать её при загрузке нечем: замеры прошлых ходов больше нигде не хранятся,
     * и без них у загруженной партии окно «Инфо» открывалось бы с пустым графиком.
     */
    private Map<UUID, List<EmpireHistorySnapshot>> historySnapshots(GameEntity game) {
        return empireHistoryRepository.findAllByGameIdOrderByTurnAsc(game.getId()).stream()
                .collect(Collectors.groupingBy(
                        EmpireHistoryEntity::getPlayerId,
                        Collectors.mapping(row -> new EmpireHistorySnapshot(
                                row.getTurn(),
                                row.getPopulationK(),
                                row.getColonies(),
                                row.getBuildings(),
                                row.getProduction(),
                                row.getResearch(),
                                row.getFleetPower(),
                                row.getTechnologies(),
                                row.getCredits()), Collectors.toList())));
    }

    /** Построенные здания колоний — п. 10: в слепке они лежат внутри своей планеты. */
    private Map<UUID, List<PlanetBuildingSnapshot>> buildingSnapshots(List<StarSystemEntity> systems) {
        List<UUID> planetIds = systems.stream()
                .flatMap(system -> system.getPlanets().stream())
                .map(PlanetEntity::getId)
                .toList();
        return planetBuildingRepository.findAllByPlanetIdIn(planetIds).stream()
                .collect(Collectors.groupingBy(
                        PlanetBuildingEntity::getPlanetId,
                        Collectors.mapping(building -> new PlanetBuildingSnapshot(
                                building.getBuildingCode(), building.getBuiltTurn()), Collectors.toList())));
    }

    private List<PlanetSnapshot> planetSnapshots(StarSystemEntity system,
                                                 Map<UUID, Integer> slotByPlayerId,
                                                 Map<UUID, List<PlanetBuildingSnapshot>> buildingsByPlanetId) {
        return system.getPlanets().stream()
                .sorted(Comparator.comparing(PlanetEntity::getOrbit))
                .map(planet -> new PlanetSnapshot(
                        planet.getOrbit(),
                        planet.getName(),
                        planet.getPlanetSize(),
                        planet.getClimate(),
                        planet.getMinerals(),
                        planet.getMaxPopulation(),
                        slotByPlayerId.get(planet.getOwnerPlayerId()),
                        planet.getPopulation(),
                        planet.getPopulationK(),
                        planet.getHomeworld(),
                        planet.getJobs(),
                        planet.getProjectCode(),
                        planet.getProjectPoints(),
                        buildingsByPlanetId.getOrDefault(planet.getId(), List.of()),
                        planet.getColonyBaseReady(),
                        planet.getBuildQueue(),
                        planet.getSoldTurn(),
                        planet.getFind(),
                        planet.getFindClaimed()))
                .toList();
    }

    private PlayerEntity restorePlayer(PlayerSnapshot snapshot) {
        PlayerEntity player = new PlayerEntity();
        player.setSlot(snapshot.slot());
        player.setName(snapshot.name());
        player.setPlayerType(snapshot.playerType());
        player.setRaceCode(snapshot.raceCode());
        // Слепки, снятые до конструктора расы, своей расы не содержат.
        player.setRaceName(snapshot.raceName());
        player.setRaceTraitCodes(snapshot.raceTraits() == null ? List.of() : snapshot.raceTraits());
        // Слепки, снятые до появления разведки, очков шпионажа не содержат.
        player.setEspionagePoints(snapshot.espionagePoints() == null ? 0 : snapshot.espionagePoints());
        player.setSpies(snapshot.spies() == null ? 0 : snapshot.spies());
        // Слепки, снятые до грузового флота, грузовиков не содержат.
        player.setFreighters(snapshot.freighters() == null ? 0 : snapshot.freighters());
        // Характер правителя ИИ — п. 15; в слепках, снятых до него, пусто, и тогда он
        // выдаётся заново при восстановлении партии.
        player.setAiPersonality(snapshot.aiPersonality());
        player.setAiObjective(snapshot.aiObjective());
        player.setColor(snapshot.color());
        // Пропуск выдаётся новый: старый остался у игроков исходной партии.
        player.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
        player.setJoinedAt(OffsetDateTime.now());
        player.setResearchCategoryCode(snapshot.researchCategoryCode());
        player.setResearchLevelOrder(snapshot.researchLevelOrder());
        player.setResearchOptionCode(snapshot.researchOptionCode());
        // Сохранения, снятые до появления исследований, поля исследований не содержат.
        player.setResearchPoints(snapshot.researchPoints() == null ? 0 : snapshot.researchPoints());
        // Сохранения, снятые до появления казны, кредитов не содержат.
        player.setCredits(snapshot.credits() == null ? 0 : snapshot.credits());
        return player;
    }

    private StarSystemEntity restoreSystem(StarSystemSnapshot snapshot, Map<Integer, UUID> playerIdBySlot) {
        StarSystemEntity system = new StarSystemEntity();
        system.setName(snapshot.name());
        system.setXParsec(snapshot.xParsec());
        system.setYParsec(snapshot.yParsec());
        system.setStarColor(snapshot.starColor());
        system.setSpecial(snapshot.special());
        // Чудище и его здоровье: без них поднятая партия теряла сторожей, и особая звезда
        // открывалась без боя. Слепок прошлой версии этих полей не несёт — приходит null,
        // и система просто оказывается чистой, как и была до починки.
        system.setMonster(snapshot.monster());
        system.setMonsterStrength(snapshot.monsterStrength());
        system.setSpecialClaimed(Boolean.TRUE.equals(snapshot.specialClaimed()));

        for (PlanetSnapshot snapshotPlanet : snapshot.planets()) {
            PlanetEntity planet = new PlanetEntity();
            planet.setOrbit(snapshotPlanet.orbit());
            planet.setName(snapshotPlanet.name());
            planet.setPlanetSize(snapshotPlanet.planetSize());
            planet.setClimate(snapshotPlanet.climate());
            planet.setMinerals(snapshotPlanet.minerals());
            planet.setMaxPopulation(snapshotPlanet.maxPopulation());
            planet.setOwnerPlayerId(playerIdBySlot.get(snapshotPlanet.ownerSlot()));
            // Слепки, снятые до появления роста, хранят только целых жителей.
            if (snapshotPlanet.populationK() == null) {
                planet.setPopulation(snapshotPlanet.population());
            } else {
                planet.setPopulationK(snapshotPlanet.populationK());
            }
            // Слепки, снятые до появления занятий, их не содержат: колония получит
            // распределение по умолчанию при первом же обращении к ней.
            planet.setJobs(snapshotPlanet.jobs() == null ? PopulationJobs.NONE : snapshotPlanet.jobs());
            planet.setProjectCode(snapshotPlanet.projectCode());
            // Слепки, снятые до появления очереди, её не содержат — п. 10.
            planet.setBuildQueue(snapshotPlanet.buildQueue());
            planet.setSoldTurn(snapshotPlanet.soldTurn());
            planet.setProjectPoints(snapshotPlanet.projectPoints() == null ? 0 : snapshotPlanet.projectPoints());
            // Слепки, снятые до появления колониальных баз, о них не знают.
            planet.setColonyBaseReady(Boolean.TRUE.equals(snapshotPlanet.colonyBaseReady()));
            planet.setHomeworld(snapshotPlanet.homeworld());
            // Находка планеты — п. 4.1. Слепки, снятые до её появления, о ней не знают:
            // приходит null, и планета читается обыкновенной, какой она в той партии и
            // была. Отметка о выданных технологиях переносится вместе с находкой — иначе
            // артефакты отдавали бы их заново на каждой загрузке.
            planet.setFind(snapshotPlanet.find());
            planet.setFindClaimed(Boolean.TRUE.equals(snapshotPlanet.findClaimed()));
            system.addPlanet(planet);
        }
        return system;
    }

    /**
     * Построенные здания колоний — п. 10. Как и технологии, пишутся после того, как
     * планеты получили идентификаторы: здание ссылается на планету, а не лежит внутри неё.
     */
    private void restoreBuildings(GameSnapshot snapshot, List<StarSystemEntity> systems) {
        List<PlanetBuildingEntity> buildings = new ArrayList<>();
        for (int i = 0; i < systems.size(); i++) {
            List<PlanetSnapshot> snapshotPlanets = snapshot.systems().get(i).planets();
            List<PlanetEntity> planets = systems.get(i).getPlanets().stream()
                    .sorted(Comparator.comparing(PlanetEntity::getOrbit))
                    .toList();
            for (int orbit = 0; orbit < snapshotPlanets.size(); orbit++) {
                // Слепки, снятые до появления зданий, их не содержат.
                List<PlanetBuildingSnapshot> built = snapshotPlanets.get(orbit).buildings();
                if (built == null) {
                    continue;
                }
                for (PlanetBuildingSnapshot snapshotBuilding : built) {
                    PlanetBuildingEntity building = new PlanetBuildingEntity();
                    building.setPlanetId(planets.get(orbit).getId());
                    building.setBuildingCode(snapshotBuilding.buildingCode());
                    building.setBuiltTurn(snapshotBuilding.builtTurn());
                    buildings.add(building);
                }
            }
        }
        planetBuildingRepository.saveAllAndFlush(buildings);
    }

    /**
     * Изученные технологии игроков — п. 9. Пишутся после того, как игроки получили
     * идентификаторы: технология ссылается на игрока, а не лежит внутри него.
     */
    private void restoreTechnologies(List<PlayerEntity> players, GameSnapshot snapshot) {
        Map<Integer, List<PlayerTechnologySnapshot>> bySlot = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            // Сохранения, снятые до появления исследований, изученного не содержат.
            bySlot.put(player.slot(), player.technologies() == null ? List.of() : player.technologies());
        }

        List<PlayerTechnologyEntity> technologies = new ArrayList<>();
        for (PlayerEntity player : players) {
            for (PlayerTechnologySnapshot snapshotTechnology : bySlot.getOrDefault(player.getSlot(), List.of())) {
                PlayerTechnologyEntity technology = new PlayerTechnologyEntity();
                technology.setPlayerId(player.getId());
                technology.setCategoryCode(snapshotTechnology.categoryCode());
                technology.setLevelOrder(snapshotTechnology.levelOrder());
                technology.setOptionCode(snapshotTechnology.optionCode());
                technology.setAcquiredTurn(snapshotTechnology.acquiredTurn());
                technologies.add(technology);
            }
        }
        playerTechnologyRepository.saveAllAndFlush(technologies);
    }

    /**
     * Летопись империй — п. 11.1. Пишется после игроков: замер ссылается на игрока, а
     * идентификаторы у загруженной партии свои.
     */
    private void restoreHistory(GameEntity game, List<PlayerEntity> players, GameSnapshot snapshot) {
        Map<Integer, List<EmpireHistorySnapshot>> bySlot = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            // Слепки, снятые до летописи, замеров не содержат — партия начнёт её заново.
            bySlot.put(player.slot(), player.history() == null ? List.of() : player.history());
        }

        List<EmpireHistoryEntity> rows = new ArrayList<>();
        for (PlayerEntity player : players) {
            for (EmpireHistorySnapshot point : bySlot.getOrDefault(player.getSlot(), List.of())) {
                EmpireHistoryEntity row = new EmpireHistoryEntity();
                row.setGameId(game.getId());
                row.setPlayerId(player.getId());
                row.setTurn(point.turn());
                row.setPopulationK(point.populationK());
                row.setColonies(point.colonies());
                // Слепок мог быть снят до графика построек: тогда поля нет, и линия
                // построек начнётся с хода загрузки.
                row.setBuildings(point.buildings() == null ? 0 : point.buildings());
                row.setProduction(point.production());
                row.setResearch(point.research());
                row.setFleetPower(point.fleetPower());
                row.setTechnologies(point.technologies());
                row.setCredits(point.credits());
                rows.add(row);
            }
        }
        empireHistoryRepository.saveAllAndFlush(rows);
    }

    /** Родные миры игроков — п. 4.2: в слепке система записана порядковым номером. */
    private void linkHomeSystems(List<PlayerEntity> players, GameSnapshot snapshot, List<StarSystemEntity> systems) {
        Map<Integer, Integer> homeIndexBySlot = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            if (player.homeSystemIndex() != null) {
                homeIndexBySlot.put(player.slot(), player.homeSystemIndex());
            }
        }
        for (PlayerEntity player : players) {
            Integer index = homeIndexBySlot.get(player.getSlot());
            if (index != null) {
                player.setHomeSystemId(systems.get(index).getId());
            }
        }
    }

    private GameSaveDto toDto(GameSaveEntity save) {
        return new GameSaveDto(
                save.getId(),
                save.getGameId(),
                save.getName(),
                save.getGalaxySize(),
                save.getWidthParsecs(),
                save.getHeightParsecs(),
                save.getStarCount(),
                save.getTurn(),
                save.getHumanPlayers(),
                save.getTotalPlayers(),
                save.getSavedAt());
    }

    /** Тот же заголовок, но из проекции: списку сам слепок не нужен. */
    private GameSaveDto toDto(GameSaveSummary save) {
        return new GameSaveDto(
                save.getId(),
                save.getGameId(),
                save.getName(),
                save.getGalaxySize(),
                save.getWidthParsecs(),
                save.getHeightParsecs(),
                save.getStarCount(),
                save.getTurn(),
                save.getHumanPlayers(),
                save.getTotalPlayers(),
                save.getSavedAt());
    }

    private String serialize(GameSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать состояние партии", e);
        }
    }

    private GameSnapshot deserialize(String state) {
        try {
            return objectMapper.readValue(state, GameSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new ConflictException("save.corrupted");
        }
    }
}
