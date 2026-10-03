package com.sddnw.server.service;

import com.sddnw.server.domain.entity.CouncilVoteEntity;
import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.dto.CouncilDto;
import com.sddnw.server.dto.CouncilVoterDto;
import com.sddnw.server.repository.CouncilVoteRepository;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Выборы Высшего совета — п. 3 (`docs/moo2/council.png`).
 * <p>
 * Совет собирается раз в двадцать пять ходов начиная с пятидесятого, голосов у империи
 * столько, сколько у неё населения, и кандидатов двое — сильнейшие по голосам. Прочие
 * голосуют за того, к кому относятся лучше, или воздерживаются. Набравший две трети
 * голосов галактики избран правителем и побеждает; не набрал никто — совет расходится
 * ни с чем, и партия идёт дальше.
 * <p>
 * Числа и оговорки — в {@link CouncilRules}; там же объяснено, почему совет вернулся в
 * игру не таким, каким его когда-то убрали.
 * <p>
 * <b>Голоса записываются строками</b> ({@code council_vote}), потому что сцена показывает
 * их ПО ОДНОМУ уже после хода, а пересчитать их позже нечем: доверие империй меняется
 * каждый ход. Выборка доверия здесь одна на партию и раз в двадцать пять ходов — это не
 * та выборка «на игрока в каждой фазе», от которой дорожает ход.
 */
@Service
public class CouncilService {

    private static final Logger log = LoggerFactory.getLogger(CouncilService.class);

    private final CouncilRules rules;
    private final CouncilVoteRepository voteRepository;
    private final DiplomacyRelationRepository relationRepository;

    private final GameAccess gameAccess;
    private final com.sddnw.server.repository.PlayerRepository playerRepository;
    private final GameRepository gameRepository;
    private final DiplomacyService diplomacyService;
    private final PlayerEventService playerEvents;

    public CouncilService(CouncilRules rules, CouncilVoteRepository voteRepository,
                          DiplomacyRelationRepository relationRepository,
                          GameAccess gameAccess,
                          com.sddnw.server.repository.PlayerRepository playerRepository,
                          GameRepository gameRepository,
                          DiplomacyService diplomacyService,
                          PlayerEventService playerEvents) {
        this.rules = rules;
        this.voteRepository = voteRepository;
        this.relationRepository = relationRepository;
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
        this.gameRepository = gameRepository;
        this.diplomacyService = diplomacyService;
        this.playerEvents = playerEvents;
    }

    /**
     * Собирает совет, если пришёл его срок, или подводит итог открытого; возвращает
     * избранного, {@code null} — совет не собирался, ещё ждёт голосов или никого не избрал.
     * <p>
     * <b>Совет с избирателями-людьми идёт в два хода</b> ({@link CouncilRules#asks}): на ходу
     * созыва выбираются кандидаты, записываются веса и голоса ИИ, а голоса людей остаются
     * пустыми до их ответа; итог подводит {@link #close} в конце следующего хода. Веса и
     * голоса ИИ при этом не пересчитываются: они решены на ходу созыва, и ответ человека
     * их не двигает. Совет без людей (замеры, наблюдатель) считается в тот же ход, как и
     * прежде, — его партии обязаны повторяться, и ждать там некого.
     * <p>
     * Открытый совет в слепок партии не попадает — как и сами голоса с ходом созыва: поднятое
     * сохранение начинает без него, и совет соберётся снова в свой срок.
     *
     * @param population голоса империй: у каждой столько, сколько у неё населения
     */
    public PlayerEntity hold(TurnContext context, Map<UUID, Integer> population) {
        GameEntity game = context.game();
        // Партия, в которой совет выключен (замеры балансировки), выборов не знает вовсе.
        if (!Boolean.TRUE.equals(game.getCouncil())) {
            return null;
        }
        if (Boolean.TRUE.equals(game.getCouncilOpen())) {
            return close(context);
        }
        List<PlayerEntity> alive = context.players().stream()
                .filter(player -> population.getOrDefault(player.getId(), 0) > 0)
                .sorted(Comparator.comparing(PlayerEntity::getSlot))
                .toList();
        if (!rules.convenes(context.turn(), alive.size())) {
            return null;
        }

        Map<UUID, Integer> votes = alive.stream()
                .collect(Collectors.toMap(PlayerEntity::getId,
                        player -> population.getOrDefault(player.getId(), 0)));
        List<UUID> candidates = rules.candidates(votes, alive.stream()
                .collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getSlot)));
        if (candidates.size() < 2) {
            return null;
        }
        UUID first = candidates.get(0);
        UUID second = candidates.get(1);

        // Доверие всех ко всем — одной выборкой: совет спрашивает его у каждой империи,
        // а запрос «на пару» изнутри посчитанного хода бьёт по всему ходу.
        Map<UUID, Map<UUID, Integer>> trust = trustByPlayer(
                alive.stream().map(PlayerEntity::getId).toList());

        Integer total = votes.values().stream().mapToInt(Integer::intValue).sum();
        Integer required = rules.requiredVotes(total);

        List<CouncilVoteEntity> rows = new ArrayList<>(alive.size());
        for (PlayerEntity voter : alive) {
            Boolean asked = rules.asks(voter.getPlayerType(), voter.getId(), first, second);
            CouncilVoteEntity row = new CouncilVoteEntity();
            row.setGameId(game.getId());
            row.setTurn(context.turn());
            row.setVoterPlayerId(voter.getId());
            row.setWeight(votes.getOrDefault(voter.getId(), 0));
            row.setChoicePlayerId(asked ? null : choiceByTrust(voter.getId(), first, second, trust));
            row.setCandidate(voter.getId().equals(first) || voter.getId().equals(second));
            row.setAsked(asked);
            row.setPending(asked);
            rows.add(row);
        }

        // Прошлые выборы этой партии больше не нужны: сцена показывает последние, а
        // хранить историю голосований незачем — на это есть журнал.
        voteRepository.deleteAllByGameId(game.getId());
        voteRepository.saveAll(rows);

        game.setCouncilTurn(context.turn());
        game.setCouncilTotalVotes(total);
        game.setCouncilRequiredVotes(required);
        game.setCouncilElectedPlayerId(null);

        if (rows.stream().anyMatch(CouncilVoteEntity::getAsked)) {
            game.setCouncilOpen(Boolean.TRUE);
            Map<UUID, String> names = alive.stream()
                    .collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getName));
            // Спрошенному — вопрос, прочим — весть о созыве: событие, которого игрок не
            // увидел, для игры не случилось.
            for (PlayerEntity player : context.players()) {
                Boolean asked = rows.stream().anyMatch(row -> row.getAsked()
                        && row.getVoterPlayerId().equals(player.getId()));
                context.report().add(player.getId(), "COUNCIL", asked
                        ? new MessageKey("turn.council.ballot", names.get(first), names.get(second))
                        : new MessageKey("turn.council.convened", names.get(first), names.get(second)));
            }
            log.info("Высший совет созван на ходу {}: кандидаты {} и {}, ждём голосов людей",
                    context.turn(), names.get(first), names.get(second));
            return null;
        }
        return decide(context, rows);
    }

    /**
     * Итог открытого совета — конец хода, следующего за созывом (п. 3).
     * <p>
     * Кто из людей так и не ответил, голосует правилом доверия — тем же, что и ИИ, но по
     * доверию НЫНЕШНЕГО хода: оно и есть мнение империи на момент итога. Молчание не отнимает
     * у игрока голоса и не запирает выборы.
     */
    private PlayerEntity close(TurnContext context) {
        GameEntity game = context.game();
        game.setCouncilOpen(Boolean.FALSE);
        List<CouncilVoteEntity> rows = voteRepository
                .findAllByGameIdAndTurnOrderByWeightDescVoterPlayerIdAsc(game.getId(), game.getCouncilTurn());
        List<UUID> candidates = rows.stream()
                .filter(CouncilVoteEntity::getCandidate)
                .map(CouncilVoteEntity::getVoterPlayerId)
                .toList();
        if (candidates.size() < 2) {
            return null;
        }
        List<CouncilVoteEntity> silent = rows.stream()
                .filter(CouncilVoteEntity::getPending)
                .toList();
        if (!silent.isEmpty()) {
            Map<UUID, Map<UUID, Integer>> trust = trustByPlayer(
                    silent.stream().map(CouncilVoteEntity::getVoterPlayerId).toList());
            for (CouncilVoteEntity row : silent) {
                row.setChoicePlayerId(choiceByTrust(row.getVoterPlayerId(),
                        candidates.get(0), candidates.get(1), trust));
                row.setPending(Boolean.FALSE);
            }
            voteRepository.saveAll(silent);
        }
        return decide(context, rows);
    }

    /** Голос по доверию к двум кандидатам — {@link CouncilRules#choice}. */
    private UUID choiceByTrust(UUID voter, UUID first, UUID second,
                               Map<UUID, Map<UUID, Integer>> trust) {
        Map<UUID, Integer> mine = trust.getOrDefault(voter, Map.of());
        return rules.choice(voter, first, second,
                mine.getOrDefault(first, 0), mine.getOrDefault(second, 0));
    }

    /** Подсчёт записанных голосов: набравший две трети избран, о чём узнают все. */
    private PlayerEntity decide(TurnContext context, List<CouncilVoteEntity> rows) {
        GameEntity game = context.game();
        Integer total = game.getCouncilTotalVotes();
        Integer required = game.getCouncilRequiredVotes();
        Map<UUID, Integer> tally = new java.util.HashMap<>();
        for (CouncilVoteEntity row : rows) {
            if (row.getChoicePlayerId() != null) {
                tally.merge(row.getChoicePlayerId(), row.getWeight(), Integer::sum);
            }
        }
        UUID elected = tally.entrySet().stream()
                .filter(entry -> entry.getValue() >= required)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        game.setCouncilElectedPlayerId(elected);

        PlayerEntity winner = elected == null ? null : context.players().stream()
                .filter(player -> player.getId().equals(elected))
                .findFirst()
                .orElse(null);

        // О совете узнают ВСЕ: событие, которого игрок не увидел, для игры не случилось.
        for (PlayerEntity player : context.players()) {
            context.report().add(player.getId(), "COUNCIL",
                    winner == null
                            ? new MessageKey("turn.council.undecided", game.getCouncilTurn())
                            : new MessageKey("turn.council.elected", winner.getName(),
                                    tally.getOrDefault(elected, 0), total));
        }
        log.info("Высший совет хода {}: голосов {}, нужно {}, избран {}",
                game.getCouncilTurn(), total, required,
                winner == null ? "никто" : winner.getName());
        return winner;
    }

    /**
     * Голос игрока в открытом совете — п. 3: за одного из двух кандидатов или воздержаться.
     * <p>
     * Партия берётся под замок, как у конца хода: итог подводит конец хода, и голос, поданный
     * одновременно с ним, не должен ни потеряться, ни лечь в уже закрытый совет. До итога голос
     * можно переменить — совет сходится один раз для всех, и первое нажатие не приговор.
     */
    @Transactional
    public CouncilDto vote(UUID gameId, String accessToken, UUID choice) {
        // Игру до замка не читаем: её версия устарела бы, и своя же запись упала бы на проверке.
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        if (!Boolean.TRUE.equals(game.getCouncilOpen())) {
            throw new ConflictException("council.notOpen");
        }
        List<CouncilVoteEntity> rows = voteRepository
                .findAllByGameIdAndTurnOrderByWeightDescVoterPlayerIdAsc(gameId, game.getCouncilTurn());
        CouncilVoteEntity mine = rows.stream()
                .filter(row -> row.getVoterPlayerId().equals(playerId) && row.getAsked())
                .findFirst()
                .orElseThrow(() -> new ConflictException("council.notAsked"));
        if (choice != null && rows.stream().noneMatch(row -> row.getCandidate()
                && row.getVoterPlayerId().equals(choice))) {
            throw new BadRequestException("council.notCandidate");
        }
        mine.setChoicePlayerId(choice);
        mine.setPending(Boolean.FALSE);
        voteRepository.save(mine);

        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity viewer = players.stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
        log.info("Игрок {} подал голос в совете хода {}: {}", viewer.getName(),
                game.getCouncilTurn(), choice == null ? "воздержался" : choice);
        return session(game, viewer, players);
    }

    /**
     * Проигравший не признаёт избрания — п. 3.
     * <p>
     * Правило MOO II: избранному правителю галактики можно не подчиниться, и тогда партия
     * продолжается, а <b>все, кто голосовал за избранного, объявляют отказнику войну</b>.
     * Отсюда и устройство: отказ не отменяет выборов (голоса остаются записанными и сцена
     * показывает их как были), а <i>оживляет партию</i> — снимает победу, возвращает
     * состояние {@code IN_PROGRESS} и стирает победителя, потому что победы больше нет.
     * <p>
     * <b>Отказ в партии ОДИН</b> ({@code game.council_refused}): без отметки следующий
     * совет через двадцать пять ходов избрал бы того же, а отказаться можно было бы снова
     * и снова — победа советом перестала бы существовать вовсе. Второй раз совет решает
     * окончательно.
     * <p>
     * <b>Кто вправе отказаться — реконструкция.</b> В оригинале выбор предлагают человеку,
     * проигравшему голосование; у нас людей в партии бывает несколько, поэтому отказаться
     * может любой участник, кроме самого избранного. Приговор совета галактический, и
     * отказ одного возвращает партию всем — об этом узнаёт каждый игрок.
     */
    @Transactional
    public CouncilDto refuse(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireGame(gameId);
        PlayerEntity rebel = gameAccess.requirePlayer(game, accessToken);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        UUID elected = requireRefusable(game, rebel);

        List<PlayerEntity> supporters = supporters(game, elected, players);
        Integer declared = diplomacyService.warOnCouncilRebel(rebel, supporters, game.getTurn());

        game.setStatus(GameStatus.IN_PROGRESS);
        game.setWinnerPlayerId(null);
        game.setVictoryKind(null);
        game.setFinishedAt(null);
        game.setCouncilRefused(Boolean.TRUE);
        gameRepository.save(game);

        // Событие не по нажатию читателя: о войне на весь свет узнают все, а не один
        // отказник, — иначе соседи получили бы войну без объяснения.
        String electedName = players.stream()
                .filter(player -> player.getId().equals(elected))
                .map(PlayerEntity::getName)
                .findFirst()
                .orElse("");
        for (PlayerEntity player : players) {
            playerEvents.record(game.getId(), player.getId(), game.getTurn(), "COUNCIL",
                    new MessageKey("turn.council.refused", rebel.getName(), electedName, declared));
        }
        log.info("Империя {} не признала избрания {}: войну объявили {} империй",
                rebel.getName(), electedName, declared);
        return session(game, rebel, players);
    }

    /**
     * Отказ возможен ровно один раз и ровно у проигравшего; возвращает избранного.
     * <p>
     * Решает {@link CouncilRules#canRefuse} — оно же гасит кнопку в сцене, и второго свода
     * правил отказа нет. Здесь только ПРИЧИНА: игроку нужно не «нельзя», а то, почему
     * нельзя. Отказ спрашивается первым: после отказа партия снова идёт, и проверка
     * состояния сказала бы «отказываться не от чего» — правду о состоянии и неправду о деле.
     */
    private UUID requireRefusable(GameEntity game, PlayerEntity rebel) {
        if (Boolean.TRUE.equals(rules.canRefuse(game, rebel.getId()))) {
            return game.getCouncilElectedPlayerId();
        }
        if (Boolean.TRUE.equals(game.getCouncilRefused())) {
            throw new ConflictException("council.alreadyRefused");
        }
        if (rebel.getId().equals(game.getCouncilElectedPlayerId())) {
            throw new ConflictException("council.electedCannotRefuse");
        }
        throw new ConflictException("council.nothingToRefuse");
    }

    /** Кто голосовал за избранного — им и воевать; воздержавшиеся остаются в стороне. */
    private List<PlayerEntity> supporters(GameEntity game, UUID elected, List<PlayerEntity> players) {
        List<UUID> voted = voteRepository
                .findAllByGameIdAndTurnOrderByWeightDescVoterPlayerIdAsc(game.getId(), game.getCouncilTurn())
                .stream()
                .filter(row -> elected.equals(row.getChoicePlayerId()))
                .map(CouncilVoteEntity::getVoterPlayerId)
                .toList();
        return players.stream().filter(player -> voted.contains(player.getId())).toList();
    }

    /**
     * Последние выборы партии для сцены — п. 3: доступ спрашивается так же, как у любого
     * действия внутри партии, и смотрит их участник.
     */
    @Transactional(readOnly = true)
    public CouncilDto forPlayer(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireGame(gameId);
        PlayerEntity viewer = gameAccess.requirePlayer(game, accessToken);
        return session(game, viewer, playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId));
    }

    /**
     * Последние выборы этой партии для сцены совета — п. 3; {@code null} — совет ещё не
     * собирался.
     * <p>
     * Голоса отдаются в том порядке, в каком их объявляют: сперва тяжёлые. Порядок задан
     * выборкой, а не сценой: партия обязана рассказывать о выборах одинаково при каждом
     * открытии.
     */
    public CouncilDto session(GameEntity game, PlayerEntity viewer, List<PlayerEntity> players) {
        if (game.getCouncilTurn() == null) {
            return null;
        }
        List<CouncilVoteEntity> rows = voteRepository
                .findAllByGameIdAndTurnOrderByWeightDescVoterPlayerIdAsc(
                        game.getId(), game.getCouncilTurn());
        if (rows.isEmpty()) {
            return null;
        }
        Map<UUID, PlayerEntity> byId = players.stream()
                .collect(Collectors.toMap(PlayerEntity::getId, player -> player));
        List<CouncilVoterDto> voters = rows.stream()
                .map(row -> voter(row, byId, viewer, Boolean.TRUE.equals(game.getCouncilOpen())))
                .toList();
        PlayerEntity elected = game.getCouncilElectedPlayerId() == null
                ? null : byId.get(game.getCouncilElectedPlayerId());
        // Кнопка «не подчиниться» гаснет ровно там же, где отказал бы сервер: правило одно
        // и живёт в CouncilRules.
        Boolean canRefuse = rules.canRefuse(game, viewer == null ? null : viewer.getId());
        Boolean open = Boolean.TRUE.equals(game.getCouncilOpen());
        Boolean ballot = open && viewer != null && rows.stream().anyMatch(row -> row.getAsked()
                && row.getVoterPlayerId().equals(viewer.getId()));
        return new CouncilDto(
                game.getCouncilTurn(),
                game.getCouncilTotalVotes(),
                game.getCouncilRequiredVotes(),
                game.getCouncilElectedPlayerId(),
                elected == null ? null : elected.getName(),
                voters.stream().filter(CouncilVoterDto::candidate).toList(),
                voters,
                Boolean.TRUE.equals(game.getCouncilRefused()),
                canRefuse,
                open,
                ballot);
    }

    /**
     * Голос одной империи для сцены. <b>Пока совет открыт, чужие голоса не объявлены</b>:
     * голосующий человек не должен видеть, куда склоняется зал, — иначе его голос решался бы
     * подсчётом, а не мнением, а у ИИ такого знания нет. Свой голос виден всегда.
     */
    private CouncilVoterDto voter(CouncilVoteEntity row, Map<UUID, PlayerEntity> byId,
                                  PlayerEntity viewer, Boolean open) {
        PlayerEntity player = byId.get(row.getVoterPlayerId());
        Boolean yours = viewer != null && viewer.getId().equals(row.getVoterPlayerId());
        Boolean hidden = open && !yours;
        return new CouncilVoterDto(
                row.getVoterPlayerId(),
                player == null ? "" : player.getName(),
                player == null ? null : player.getColor(),
                row.getWeight(),
                hidden ? null : row.getChoicePlayerId(),
                row.getCandidate(),
                yours,
                hidden || Boolean.TRUE.equals(row.getPending()));
    }

    /** Доверие каждой империи к каждой: строка (X, Y) держит мнение X о Y — п. 15. */
    private Map<UUID, Map<UUID, Integer>> trustByPlayer(List<UUID> ids) {
        Map<UUID, Map<UUID, Integer>> trust = new java.util.HashMap<>();
        for (DiplomacyRelationEntity relation : relationRepository.findAllByPlayerIdIn(ids)) {
            trust.computeIfAbsent(relation.getPlayerId(), key -> new java.util.HashMap<>())
                    .put(relation.getOtherPlayerId(), relation.getTrust());
        }
        return trust;
    }
}
