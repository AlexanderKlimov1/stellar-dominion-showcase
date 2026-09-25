package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlanetEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.entity.PopulationTransferEntity;
import com.moo3.server.repository.PlanetRepository;
import com.moo3.server.repository.PopulationTransferRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Перевозка жителей между колониями — п. 4.1.1.
 * <p>
 * <b>Внутри своей системы жителей перевозят без грузовиков, и переходят они сразу</b> —
 * правило оригинала: «population transfers do not need Freighters and take effect
 * immediately if within the same system» (StrategyWiki, Growing your population).
 * Грузовики нужны там, где начинается перелёт между звёздами.
 * <p>
 * <b>Между системами грузовой флот резервируется из расчёта один грузовик на единицу
 * населения.</b> Пока рейс в пути, эти грузовики заняты им и еду не возят: у империи их
 * столько, сколько она построила, и делить один грузовик между рейсом и подвозом нельзя.
 * <p>
 * Рейс занимает остаток хода: жители садятся на корабли сразу — колония-отправитель
 * теряет их в тот же момент, — и сходят на планету в конце этого же хода
 * ({@link TransferPhase}). Пока они в пути, грузовики заняты, и еду в этот ход не возят:
 * подвоз считается в начале расчёта хода, когда рейс ещё не пришёл.
 * <p>
 * Расстояний игра пока не считает, поэтому время в пути одинаковое для любых двух колоний;
 * когда появятся скорости, рейс станет длиннее одного хода, а правило резерва не изменится.
 * <p>
 * Если на планете назначения не хватило места, сходят те, кто помещается, а остальные
 * остаются на борту и ждут следующего хода — вместе со своими грузовиками. Так населению
 * некуда пропасть: жители либо в колонии, либо в рейсе.
 */
@Service
public class PopulationTransferService {

    private static final Logger log = LoggerFactory.getLogger(PopulationTransferService.class);

    private final PopulationTransferRepository transferRepository;
    private final PlanetRepository planetRepository;
    private final ColonyService colonyService;
    private final PopulationCalculator populationCalculator;
    private final PlayerEventService playerEvents;

    public PopulationTransferService(PopulationTransferRepository transferRepository,
                                     PlanetRepository planetRepository,
                                     ColonyService colonyService,
                                     PopulationCalculator populationCalculator,
                                     PlayerEventService playerEvents) {
        this.transferRepository = transferRepository;
        this.planetRepository = planetRepository;
        this.colonyService = colonyService;
        this.populationCalculator = populationCalculator;
        this.playerEvents = playerEvents;
    }

    /**
     * Отправляет жителей в другую свою колонию — п. 4.1.1.
     * <p>
     * Жители садятся на корабли сразу: колония-отправитель теряет их в этот же ход, а
     * грузовики уходят в резерв до конца рейса.
     *
     * @return колония-отправитель после посадки жителей
     */
    @Transactional
    public PlanetEntity send(GameEntity game, PlayerEntity player, UUID fromPlanetId,
                             UUID targetPlanetId, Integer population) {
        PlanetEntity from = planetRepository.findById(fromPlanetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", fromPlanetId));
        if (!player.getId().equals(from.getOwnerPlayerId())) {
            throw new ForbiddenException("colony.notYours", from.getName());
        }

        PlanetEntity target = planetRepository.findById(targetPlanetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", targetPlanetId));
        if (!player.getId().equals(target.getOwnerPlayerId())) {
            throw new ConflictException("transfer.ownColonyOnly");
        }
        if (from.getId().equals(target.getId())) {
            throw new ConflictException("transfer.sameColony");
        }

        if (population <= 0 || population >= from.getPopulation()) {
            throw new ConflictException("transfer.range", (from.getPopulation() - 1));
        }

        /*
          Внутри своей системы жителей перевозят без грузовиков, и переходят они сразу —
          п. 4.1.1. Правило оригинала: «population transfers do not need Freighters and
          take effect immediately if within the same system» (StrategyWiki, Growing your
          population); грузовики и время в пути начинаются там, где начинается перелёт
          между звёздами. Соседняя планета той же системы — не перелёт, и держать ради
          неё грузовой флот незачем.
         */
        if (from.getStarSystem().getId().equals(target.getStarSystem().getId())) {
            return moveWithinSystem(from, target, population);
        }

        Integer free = freeFreighters(player);
        Integer needed = populationCalculator.freightersForTransfer(population);
        if (needed > free) {
            throw new ConflictException("transfer.noFreighters", needed, free);
        }

        from.setPopulation(from.getPopulation() - population);
        from.setJobs(colonyService.jobs(from));
        planetRepository.save(from);

        PopulationTransferEntity transfer = new PopulationTransferEntity();
        transfer.setGameId(game.getId());
        transfer.setOwnerPlayerId(player.getId());
        transfer.setFromPlanetId(from.getId());
        transfer.setToPlanetId(target.getId());
        transfer.setPopulation(population);
        transfer.setDepartedTurn(game.getTurn());
        transferRepository.save(transfer);

        log.info("Игрок {} отправил {} жителей с {} на {}: занято {} грузовиков",
                player.getName(), population, from.getName(), target.getName(), needed);
        return from;
    }

    /**
     * Переселение внутри одной системы — п. 4.1.1: жители переходят сразу.
     * <p>
     * Рейса здесь нет вовсе: ни грузовиков, ни строки перевозки, ни ожидания конца хода.
     * Поэтому и мест на планете назначения спрашиваем сразу — ждать на борту жителям
     * негде, а тихо потерять их нельзя.
     * <p>
     * Занятия пересчитываются у обеих колоний: ушедший житель освобождает работу, а
     * пришедший её занимает, и обе выработки меняются тем же мгновением.
     */
    private PlanetEntity moveWithinSystem(PlanetEntity from, PlanetEntity target, Integer population) {
        ColonyService.ColonyContext context = colonyService.context(target);
        Integer capacity = colonyService.maxPopulation(
                target, context.effects(target), context.race(target));
        int room = Math.max(0, capacity - target.getPopulation());
        if (room < population) {
            throw new ConflictException("transfer.noRoom", target.getName(), room);
        }

        from.setPopulation(from.getPopulation() - population);
        from.setJobs(colonyService.jobs(from));
        target.setPopulation(target.getPopulation() + population);
        target.setJobs(colonyService.jobs(target));
        planetRepository.saveAll(List.of(from, target));

        log.info("Переселение внутри системы: {} жителей с {} на {} без грузовиков",
                population, from.getName(), target.getName());
        return from;
    }

    /**
     * Тот же рейс, но изнутри посчитанного хода — для империи ИИ (журнал, п. 3.102).
     * <p>
     * <b>Зачем свой вход.</b> {@link #send} поднимает обе планеты из базы по
     * идентификаторам и спрашивает занятые грузовики отдельной выборкой. Внутри хода это
     * запрещено: после первой правки каждый запрос заставляет Hibernate сбросить в базу
     * всё, что ход успел изменить (CLAUDE.md, «Запрос в базу изнутри посчитанного хода»).
     * Здесь планеты уже в руках у вызывающего, а грузовики он посчитал сам — больше одного
     * рейса за ход империя ИИ не отправляет, и вычесть занятое можно без базы.
     * <p>
     * <b>Правила при этом те же самые.</b> Ни второго свода, ни поблажек: колонию нельзя
     * оставить без населения, привезти больше, чем поместится, тоже нельзя, а внутри своей
     * системы жители переходят даром и сразу. Проверки стоят здесь же, потому что ИИ
     * обязан спрашивать ЗАРАНЕЕ, а не ловить отказом: отказ прилетел бы изнутри
     * посчитанного хода и уронил бы весь ход — так уже бывало с подчинением телепатами.
     *
     * @return отправлен ли рейс
     */
    public Boolean sendFromTurn(TurnContext context, PlayerEntity player,
                                PlanetEntity from, PlanetEntity target, Integer population) {
        if (population <= 0 || population >= from.getPopulation()) {
            return Boolean.FALSE;
        }
        ColonyService.ColonyContext colonies = context.colonyContext();
        Integer capacity = colonyService.maxPopulation(
                target, colonies.effects(target), colonies.race(target));
        if (capacity - target.getPopulation() < population) {
            return Boolean.FALSE;
        }

        if (from.getStarSystem() != null && target.getStarSystem() != null
                && from.getStarSystem().getId().equals(target.getStarSystem().getId())) {
            from.setPopulation(from.getPopulation() - population);
            from.setJobs(colonyService.jobs(from));
            target.setPopulation(target.getPopulation() + population);
            target.setJobs(colonyService.jobs(target));
            log.debug("ИИ {}: {} жителей с {} на {} внутри системы",
                    player.getName(), population, from.getName(), target.getName());
            return Boolean.TRUE;
        }

        from.setPopulation(from.getPopulation() - population);
        from.setJobs(colonyService.jobs(from));

        PopulationTransferEntity transfer = new PopulationTransferEntity();
        transfer.setGameId(context.game().getId());
        transfer.setOwnerPlayerId(player.getId());
        transfer.setFromPlanetId(from.getId());
        transfer.setToPlanetId(target.getId());
        transfer.setPopulation(population);
        transfer.setDepartedTurn(context.game().getTurn());
        transferRepository.save(transfer);
        log.debug("ИИ {}: {} жителей с {} на {} грузовиками",
                player.getName(), population, from.getName(), target.getName());
        return Boolean.TRUE;
    }

    /**
     * Высаживает прибывших — вызывается фазой конца хода.
     * <p>
     * Отправленные в этом ходу сходят здесь же: рейс занимает остаток хода. Не
     * поместившиеся остаются на борту вместе со своими грузовиками и ждут следующего хода.
     */
    @Transactional
    public void arrive(TurnContext context) {
        List<PopulationTransferEntity> transfers =
                transferRepository.findAllByGameId(context.game().getId());
        if (transfers.isEmpty()) {
            return;
        }

        for (PopulationTransferEntity transfer : transfers) {
            PlanetEntity target = planetRepository.findById(transfer.getToPlanetId()).orElse(null);
            if (target == null || !transfer.getOwnerPlayerId().equals(target.getOwnerPlayerId())) {
                // Колонию назначения потеряли, пока рейс был в пути: жители возвращаются
                // тем же рейсом — иначе они пропали бы вместе с грузовиками.
                returnHome(transfer, context);
                continue;
            }

            Integer capacity = colonyService.maxPopulation(target,
                    context.colonyContext().effects(target), context.colonyContext().race(target));
            int room = Math.max(0, capacity - target.getPopulation());
            int landed = Math.min(room, transfer.getPopulation());
            if (landed > 0) {
                target.setPopulation(target.getPopulation() + landed);
                target.setJobs(colonyService.jobs(target));
                planetRepository.save(target);
            }

            int left = transfer.getPopulation() - landed;
            if (left > 0) {
                // Мест не хватило: остаток ждёт на борту, грузовики остаются занятыми.
                transfer.setPopulation(left);
                transferRepository.save(transfer);
                context.report().add(transfer.getOwnerPlayerId(), "TRANSFER",
                        new MessageKey("turn.transfer.landedPartly", target.getName(), landed, left),
                        context.systemOf(target), target.getId());
                continue;
            }

            transferRepository.delete(transfer);
            context.report().add(transfer.getOwnerPlayerId(), "TRANSFER",
                    new MessageKey("turn.transfer.landed", target.getName(), landed),
                    context.systemOf(target), target.getId());
            log.info("Рейс доставил {} жителей в колонию {}", landed, target.getName());
        }
    }

    /** Сколько грузовиков империи заняты рейсами прямо сейчас — п. 4.1.1. */
    public Integer reservedBy(UUID playerId) {
        return reservedByPlayers(List.of(playerId)).getOrDefault(playerId, 0);
    }

    /** То же для нескольких игроков разом: контекст колоний считает их вместе. */
    public Map<UUID, Integer> reservedByPlayers(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        return transferRepository.findAllByOwnerPlayerIdInOrderByIdAsc(playerIds).stream()
                .collect(Collectors.groupingBy(PopulationTransferEntity::getOwnerPlayerId,
                        Collectors.summingInt(transfer ->
                                populationCalculator.freightersForTransfer(transfer.getPopulation()))));
    }

    /** Грузовики, которыми империя может распорядиться: всего минус занятые рейсами. */
    public Integer freeFreighters(PlayerEntity player) {
        return Math.max(0, player.getFreighters() - reservedBy(player.getId()));
    }

    /** Рейсы игрока — для интерфейса: видно, кто куда едет и сколько грузовиков занято. */
    @Transactional(readOnly = true)
    public List<PopulationTransferEntity> transfersOf(UUID playerId) {
        return transferRepository.findAllByOwnerPlayerIdInOrderByIdAsc(List.of(playerId));
    }

    /**
     * Жители с числительным в винительном падеже: «приняла 1 жителя», «приняла
     * 2 жителей».
     * <p>
     * Отчёт хода читают каждый ход, а жителей чаще всего возят по одному — перетаскиванием
     * на экране колоний (п. 4.1.1), — поэтому «1 жителей» попадалось бы игроку постоянно.
     */
    private String accusative(Integer count) {
        return count + (single(count) ? " жителя" : " жителей");
    }

    /**
     * Жители с числительным в именительном: «1 житель», «2 жителя», «5 жителей». Стоит
     * после глагола в единственном числе («остаётся», «возвращается»): так сказуемое согласуется
     * с любым числом, и второго правила — для глагола — не нужно.
     */
    private String nominative(Integer count) {
        if (single(count)) {
            return count + " житель";
        }
        int last = count % 10;
        return count + (last >= 2 && last <= 4 && (count % 100 < 12 || count % 100 > 14)
                ? " жителя" : " жителей");
    }

    /** Число кончается на один, кроме одиннадцати: «21 житель», но «11 жителей». */
    private Boolean single(Integer count) {
        return count % 10 == 1 && count % 100 != 11;
    }

    /** Колония назначения пропала — жители возвращаются в колонию отправления. */
    private void returnHome(PopulationTransferEntity transfer, TurnContext context) {
        PlanetEntity home = planetRepository.findById(transfer.getFromPlanetId()).orElse(null);
        if (home != null && transfer.getOwnerPlayerId().equals(home.getOwnerPlayerId())) {
            home.setPopulation(home.getPopulation() + transfer.getPopulation());
            home.setJobs(colonyService.jobs(home));
            planetRepository.save(home);
            context.report().add(transfer.getOwnerPlayerId(), "TRANSFER",
                    new MessageKey("turn.transfer.returned",
                            transfer.getPopulation(), home.getName()),
                    context.systemOf(home), home.getId());
        } else {
            playerEvents.record(transfer.getGameId(), transfer.getOwnerPlayerId(), context.turn(),
                    "TRANSFER", new MessageKey("turn.transfer.nowhere"));
        }
        transferRepository.delete(transfer);
    }
}
