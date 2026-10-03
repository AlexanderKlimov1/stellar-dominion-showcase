package com.sddnw.server.service;

import com.sddnw.server.domain.RaceTrait;
import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.dto.EmpireHistoryPointDto;
import com.sddnw.server.dto.EmpireInfoDto;
import com.sddnw.server.dto.EmpireProfileDto;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Окно «Инфо» — п. 11.1: летопись империй и их описание.
 * <p>
 * В MOO II окно Information даёт четыре вещи: график, на котором своя империя стоит рядом
 * с соперниками; свойства рас всех империй, «с которыми игрок в контакте»; изученные
 * технологии; описания всех технологий. Первое и второе живут здесь, третье и четвёртое
 * клиент уже знает — своё дерево исследований и справочник технологий.
 * <p>
 * <b>Чужое видно ровно по знакомству</b> (п. 15): империя, о которой игрок ещё не слышал,
 * в окно не попадает — иначе окно оказалось бы разведкой сильнее всякого шпиона. Зато
 * летопись пишется по всем: знакомство случается посреди партии, а прошлые ходы к тому
 * времени уже не пересчитать, и без записи график соседа начинался бы с пустоты.
 */
@Service
public class EmpireInfoService {

    private final GameAccess gameAccess;
    private final EmpireHistoryRepository historyRepository;
    private final PlayerRepository playerRepository;
    private final PlanetRepository planetRepository;
    private final PlayerTechnologyRepository technologyRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final DiplomacyService diplomacyService;
    private final GovernmentService governmentService;
    private final RaceTraitCatalog raceTraitCatalog;
    private final RaceService raceService;
    private final PlayerRoster playerRoster;
    private final ColonyService colonyService;
    private final PopulationCalculator populationCalculator;
    private final FleetService fleetService;
    private final EmpireMightRules empireMightRules;

    public EmpireInfoService(GameAccess gameAccess,
                             EmpireHistoryRepository historyRepository,
                             PlayerRepository playerRepository,
                             PlanetRepository planetRepository,
                             PlayerTechnologyRepository technologyRepository,
                             DiplomacyRelationRepository relationRepository,
                             DiplomacyService diplomacyService,
                             GovernmentService governmentService,
                             RaceTraitCatalog raceTraitCatalog,
                             RaceService raceService,
                             PlayerRoster playerRoster,
                             ColonyService colonyService,
                             PopulationCalculator populationCalculator,
                             FleetService fleetService,
                             EmpireMightRules empireMightRules) {
        this.gameAccess = gameAccess;
        this.historyRepository = historyRepository;
        this.playerRepository = playerRepository;
        this.planetRepository = planetRepository;
        this.technologyRepository = technologyRepository;
        this.relationRepository = relationRepository;
        this.diplomacyService = diplomacyService;
        this.governmentService = governmentService;
        this.raceTraitCatalog = raceTraitCatalog;
        this.raceService = raceService;
        this.playerRoster = playerRoster;
        this.colonyService = colonyService;
        this.populationCalculator = populationCalculator;
        this.fleetService = fleetService;
        this.empireMightRules = empireMightRules;
    }

    /**
     * Замер всех империй за посчитанный ход — фаза конца хода.
     * <p>
     * Берётся из того, что уже загружено на этот ход ({@link TurnContext}): колонии, их
     * эффекты и раса владельца. Своих выборок фаза делает две — изученное и флоты, — и обе
     * одним запросом на всю партию, а не по запросу на игрока.
     */
    @Transactional
    public void record(TurnContext context) {
        historyRepository.saveAll(measured(context));
    }

    /**
     * Замер этого хода — один на всех, кто его просит (п. 11.1).
     * <p>
     * Просят двое: дипломатия ИИ (порядок 17) сравнивает по нему империи, летопись (18)
     * его записывает. Считать дважды значило бы дважды пройти по колониям партии и дважды
     * поднять силу флотов всех игроков — на конце хода это было заметно, поэтому итог
     * запоминает сам ход.
     */
    private List<EmpireHistoryEntity> measured(TurnContext context) {
        return context.empireMeasure(() -> measure(context.game(), context.players(),
                context.turn(), context.colonies(), context.colonyContext()));
    }

    /**
     * Мощь империй на этот ход — п. 15: по ней империи ИИ и решают, нападать ли.
     * <p>
     * Считается тем же замером, что ложится в летопись, и той же формулой
     * ({@link EmpireMightRules}), что рисует первую линию графика: игрок видит на экране
     * ровно то, по чему судит сосед. Замер здесь не сохраняется — его запишет фаза
     * летописи в конце того же хода.
     */
    public java.util.Map<UUID, Integer> mightByPlayer(TurnContext context) {
        java.util.Map<UUID, Integer> might = new HashMap<>();
        for (EmpireHistoryEntity row : measured(context)) {
            might.put(row.getPlayerId(), empireMightRules.might(row.getFleetPower(),
                    row.getPopulationK(), row.getProduction(), row.getResearch(),
                    row.getTechnologies()));
        }
        return might;
    }

    /**
     * Замер прямо сейчас, без хода, — для партии, у которой летописи ещё нет.
     * <p>
     * Такие партии есть: начатые до появления летописи и просто те, где ход ещё не
     * заканчивали. Пустой график в окне читается как поломка, поэтому первая точка
     * ПОКАЗЫВАЕТСЯ при первом же открытии окна — но НЕ СОХРАНЯЕТСЯ.
     * <p>
     * Прежде она ложилась в летопись, и это роняло партию: точка снималась с номером
     * ТЕКУЩЕГО хода, а фаза летописи в конце этого же хода пишет точку с тем же номером, и
     * уникальность {@code (player_id, turn)} рвалась — ход не считался вовсе, 500 на каждый
     * конец хода. Хватало открыть «Инфо» на первом ходу, до первого конца хода. Нашлось
     * 27.09.2026, когда сеть обучения стала спрашивать окно каждый ход. Запишет точку фаза
     * конца хода — ей и положено.
     * <p>
     * Выборки здесь свои — колонии партии и их контекст, — но делаются они лишь до первого
     * конца хода: дальше летопись не пуста.
     */
    private List<EmpireHistoryEntity> measureNow(GameEntity game, List<PlayerEntity> players) {
        List<PlanetEntity> colonies = planetRepository
                .findAllByOwnerPlayerIdIn(players.stream().map(PlayerEntity::getId).toList()).stream()
                .sorted(GameOrder.PLANETS)
                .filter(colony -> colony.getPopulation() > 0)
                .toList();
        return measure(game, players, game.getTurn(), colonies, colonyService.context(colonies));
    }

    /**
     * Сам замер: строка на игрока со снимком семи величин — п. 11.1.
     * <p>
     * Считается одним проходом по колониям: экран смотрят редко, а ход считается всегда,
     * и лишний проход по колониям партии Huge стоит дороже, чем массив на владельца.
     */
    private List<EmpireHistoryEntity> measure(GameEntity game, List<PlayerEntity> players,
                                              Integer turn, List<PlanetEntity> colonies,
                                              ColonyService.ColonyContext colonyContext) {
        Map<UUID, Integer> technologies = technologyRepository
                .findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(PlayerTechnologyEntity::getPlayerId,
                        Collectors.summingInt(technology -> 1)));
        // Сила флотов — одной выборкой на партию, а не запросом на игрока: см.
        // FleetService.empirePowerByPlayer.
        Map<UUID, Integer> fleetPower = fleetService.empirePowerByPlayer(game.getId(), players);

        // [население в тысячах, колоний, производство, наука, постройки ценой] на владельца.
        Map<UUID, int[]> totalsByOwner = new HashMap<>();
        for (PlanetEntity colony : colonies) {
            UUID owner = colony.getOwnerPlayerId();
            if (owner == null) {
                continue;
            }
            int[] totals = totalsByOwner.computeIfAbsent(owner, id -> new int[6]);
            totals[0] += colony.getPopulationK();
            totals[1] += 1;
            // Замер идёт с прибавками лидеров — п. 6: мощь империи считается по тому, что
            // она на самом деле производит, а губернатор системы это меняет.
            totals[2] += colonyService.production(colony, colonyContext);
            totals[3] += colonyContext.withLeader(populationCalculator.research(
                    colonyService.jobs(colony).scientists(), colonyContext.effects(colony)),
                    colony, "SCIENCE");
            // Постройки — ценой, а не числом: так их считает график оригинала («each
            // building adds the production cost of that building»). Здания колонии уже
            // загружены контекстом хода, лишней выборки замер не делает.
            for (Building building : colonyContext.buildings(colony)) {
                totals[4] += building.cost();
            }
            // Доход — ПОТОК, как выработка и наука, и меряется тем же замером: казна в
            // летописи есть, но она запас, и империя, потратившая всё на стройку, стоит с
            // нулём в кармане, живя при этом лучше скопидома.
            totals[5] += colonyService.income(colony, colonyContext);
        }

        List<EmpireHistoryEntity> rows = new ArrayList<>(players.size());
        for (PlayerEntity player : players) {
            int[] totals = totalsByOwner.getOrDefault(player.getId(), new int[6]);
            EmpireHistoryEntity row = new EmpireHistoryEntity();
            row.setGameId(game.getId());
            row.setPlayerId(player.getId());
            row.setTurn(turn);
            row.setPopulationK(totals[0]);
            row.setColonies(totals[1]);
            row.setBuildings(totals[4]);
            row.setProduction(totals[2]);
            row.setResearch(totals[3]);
            row.setFleetPower(fleetPower.getOrDefault(player.getId(), 0));
            row.setTechnologies(technologies.getOrDefault(player.getId(), 0));
            row.setCredits(player.getCredits());
            row.setIncome(totals[5]);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Что показать в окне «Инфо»: своя империя и знакомые, с их летописью — п. 11.1.
     * <p>
     * У партии без единого замера первая точка считается прямо здесь и показывается, не
     * сохраняясь ({@link #measureNow}) — см. её описание.
     */
    @Transactional
    public EmpireInfoDto info(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity self = gameAccess.requirePlayer(game, accessToken);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);

        // Показываются действующие знакомства: потерянное (п. 15, пауза) снова прячет
        // соседа, как будто знакомы и не были.
        Set<UUID> shown = relationRepository.findAllByPlayerId(self.getId()).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .map(relation -> relation.getOtherPlayerId())
                .collect(Collectors.toCollection(java.util.HashSet::new));
        shown.add(self.getId());

        List<EmpireHistoryEntity> rows = historyRepository.findAllByGameIdOrderByTurnAsc(gameId);
        if (rows.isEmpty()) {
            rows = measureNow(game, players);
        }

        Map<UUID, List<EmpireHistoryPointDto>> history = new HashMap<>();
        for (EmpireHistoryEntity row : rows) {
            if (!shown.contains(row.getPlayerId())) {
                continue;
            }
            history.computeIfAbsent(row.getPlayerId(), id -> new ArrayList<>())
                    .add(new EmpireHistoryPointDto(row.getTurn(), row.getPopulationK(),
                            row.getColonies(), row.getBuildings(),
                            row.getProduction(), row.getResearch(),
                            row.getFleetPower(), row.getTechnologies(), row.getCredits(),
                            empireMightRules.might(row.getFleetPower(), row.getPopulationK(),
                                    row.getProduction(), row.getResearch(), row.getTechnologies())));
        }

        Map<String, String> raceNames = playerRoster.raceNames();
        List<EmpireProfileDto> empires = players.stream()
                .filter(player -> shown.contains(player.getId()))
                .map(player -> new EmpireProfileDto(
                        player.getId(),
                        player.getName(),
                        raceService.raceName(player, raceNames::get),
                        player.getColor(),
                        player.getId().equals(self.getId()),
                        governmentService.government(player),
                        diplomacyService.character(player),
                        traits(player),
                        history.getOrDefault(player.getId(), List.of())))
                .toList();

        return new EmpireInfoDto(game.getTurn(), empires);
    }

    /**
     * Стороны расы империи — п. 7: названия и цены в очках.
     * <p>
     * Своей расы у пустого набора нет: готовую расу игрок берёт целиком, и её стороны
     * лежат в справочнике под кодом расы, а не у игрока (см. {@code PlayerRoster}).
     */
    private List<EmpireProfileDto.EmpireTraitDto> traits(PlayerEntity player) {
        List<String> codes = player.getRaceTraitCodes().isEmpty()
                ? raceTraitCatalog.raceTraits(player.getRaceCode())
                : player.getRaceTraitCodes();
        return codes.stream()
                .map(code -> {
                    RaceTrait trait = raceTraitCatalog.require(code);
                    return new EmpireProfileDto.EmpireTraitDto(
                            trait.code(), trait.name(), trait.picks());
                })
                .toList();
    }
}
