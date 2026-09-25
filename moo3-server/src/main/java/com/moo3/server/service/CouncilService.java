package com.moo3.server.service;

import com.moo3.server.domain.entity.CouncilVoteEntity;
import com.moo3.server.domain.entity.DiplomacyRelationEntity;
import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.dto.CouncilDto;
import com.moo3.server.dto.CouncilVoterDto;
import com.moo3.server.repository.CouncilVoteRepository;
import com.moo3.server.repository.DiplomacyRelationRepository;
import com.moo3.server.repository.GameRepository;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.web.error.ConflictException;
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
    private final com.moo3.server.repository.PlayerRepository playerRepository;
    private final GameRepository gameRepository;
    private final DiplomacyService diplomacyService;
    private final PlayerEventService playerEvents;

    public CouncilService(CouncilRules rules, CouncilVoteRepository voteRepository,
                          DiplomacyRelationRepository relationRepository,
                          GameAccess gameAccess,
                          com.moo3.server.repository.PlayerRepository playerRepository,
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
     * Собирает совет, если пришёл его срок, и возвращает избранного; {@code null} —
     * совет не собирался или никого не избрал.
     *
     * @param population голоса империй: у каждой столько, сколько у неё населения
     */
    public PlayerEntity hold(TurnContext context, Map<UUID, Integer> population) {
        GameEntity game = context.game();
        // Партия, в которой совет выключен (замеры балансировки), выборов не знает вовсе.
        if (!Boolean.TRUE.equals(game.getCouncil())) {
            return null;
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
        List<UUID> candidates = rules.candidates(votes);
        if (candidates.size() < 2) {
            return null;
        }
        UUID first = candidates.get(0);
        UUID second = candidates.get(1);

        // Доверие всех ко всем — одной выборкой: совет спрашивает его у каждой империи,
        // а запрос «на пару» изнутри посчитанного хода бьёт по всему ходу.
        Map<UUID, Map<UUID, Integer>> trust = trustByPlayer(alive);

        Integer total = votes.values().stream().mapToInt(Integer::intValue).sum();
        Integer required = rules.requiredVotes(total);

        List<CouncilVoteEntity> rows = new ArrayList<>(alive.size());
        Map<UUID, Integer> tally = new java.util.HashMap<>();
        for (PlayerEntity voter : alive) {
            UUID choice = rules.choice(voter.getId(), first, second,
                    trust.getOrDefault(voter.getId(), Map.of()).getOrDefault(first, 0),
                    trust.getOrDefault(voter.getId(), Map.of()).getOrDefault(second, 0));
            CouncilVoteEntity row = new CouncilVoteEntity();
            row.setGameId(game.getId());
            row.setTurn(context.turn());
            row.setVoterPlayerId(voter.getId());
            row.setWeight(votes.getOrDefault(voter.getId(), 0));
            row.setChoicePlayerId(choice);
            row.setCandidate(voter.getId().equals(first) || voter.getId().equals(second));
            rows.add(row);
            if (choice != null) {
                tally.merge(choice, row.getWeight(), Integer::sum);
            }
        }

        // Прошлые выборы этой партии больше не нужны: сцена показывает последние, а
        // хранить историю голосований незачем — на это есть журнал.
        voteRepository.deleteAllByGameId(game.getId());
        voteRepository.saveAll(rows);

        UUID elected = tally.entrySet().stream()
                .filter(entry -> entry.getValue() >= required)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        game.setCouncilTurn(context.turn());
        game.setCouncilTotalVotes(total);
        game.setCouncilRequiredVotes(required);
        game.setCouncilElectedPlayerId(elected);

        PlayerEntity winner = elected == null ? null : alive.stream()
                .filter(player -> player.getId().equals(elected))
                .findFirst()
                .orElse(null);

        // О совете узнают ВСЕ: событие, которого игрок не увидел, для игры не случилось.
        for (PlayerEntity player : context.players()) {
            context.report().add(player.getId(), "COUNCIL",
                    winner == null
                            ? new MessageKey("turn.council.undecided", context.turn())
                            : new MessageKey("turn.council.elected", winner.getName(),
                                    tally.getOrDefault(elected, 0), total));
        }
        log.info("Высший совет на ходу {}: голосов {}, нужно {}, избран {}",
                context.turn(), total, required,
                winner == null ? "никто" : winner.getName());
        return winner;
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
                .map(row -> voter(row, byId, viewer))
                .toList();
        PlayerEntity elected = game.getCouncilElectedPlayerId() == null
                ? null : byId.get(game.getCouncilElectedPlayerId());
        // Кнопка «не подчиниться» гаснет ровно там же, где отказал бы сервер: правило одно
        // и живёт в CouncilRules.
        Boolean canRefuse = rules.canRefuse(game, viewer == null ? null : viewer.getId());
        return new CouncilDto(
                game.getCouncilTurn(),
                game.getCouncilTotalVotes(),
                game.getCouncilRequiredVotes(),
                game.getCouncilElectedPlayerId(),
                elected == null ? null : elected.getName(),
                voters.stream().filter(CouncilVoterDto::candidate).toList(),
                voters,
                Boolean.TRUE.equals(game.getCouncilRefused()),
                canRefuse);
    }

    private CouncilVoterDto voter(CouncilVoteEntity row, Map<UUID, PlayerEntity> byId,
                                  PlayerEntity viewer) {
        PlayerEntity player = byId.get(row.getVoterPlayerId());
        return new CouncilVoterDto(
                row.getVoterPlayerId(),
                player == null ? "" : player.getName(),
                player == null ? null : player.getColor(),
                row.getWeight(),
                row.getChoicePlayerId(),
                row.getCandidate(),
                viewer != null && viewer.getId().equals(row.getVoterPlayerId()));
    }

    /** Доверие каждой империи к каждой: строка (X, Y) держит мнение X о Y — п. 15. */
    private Map<UUID, Map<UUID, Integer>> trustByPlayer(List<PlayerEntity> players) {
        List<UUID> ids = players.stream().map(PlayerEntity::getId).toList();
        Map<UUID, Map<UUID, Integer>> trust = new java.util.HashMap<>();
        for (DiplomacyRelationEntity relation : relationRepository.findAllByPlayerIdIn(ids)) {
            trust.computeIfAbsent(relation.getPlayerId(), key -> new java.util.HashMap<>())
                    .put(relation.getOtherPlayerId(), relation.getTrust());
        }
        return trust;
    }
}
