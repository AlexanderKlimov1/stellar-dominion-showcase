package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.dto.AdvanceTurnsRequest;
import com.moo3.server.dto.AdvanceTurnsResponse;
import com.moo3.server.dto.EndTurnRequest;
import com.moo3.server.dto.EndTurnResponse;
import com.moo3.server.repository.PlayerRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Прогон подряд нескольких ходов — этап 0 балансировки (`balance-metrics-works.txt`).
 * <p>
 * Балансировка играет тысячи партий по сотне ходов, и отдельный запрос на каждый ход
 * тратит время на дорогу, а не на игру. Ход при этом считается тем же
 * {@link GameService#endTurn}, что и от кнопки игрока: второго пути у хода нет, иначе
 * прогоны мерили бы не ту игру, в которую играют люди.
 * <p>
 * <b>Почему это отдельная служба, а не метод {@code GameService}.</b> Каждый ход обязан
 * идти <i>своей</i> транзакцией — так же, как идёт запрос живого игрока. Вызов соседнего
 * метода того же бина проходит мимо прокси Spring, и {@code @Transactional} на нём не
 * срабатывает вовсе: ход считался бы одной огромной транзакцией на все триста ходов, а
 * замок партии держался бы всё это время. Здесь же вызов идёт через прокси, и каждый ход
 * получает свою транзакцию и свой замок — ровно как в жизни.
 */
@Service
public class TurnBatchService {

    private final GameService gameService;
    private final GameAccess gameAccess;
    private final PlayerRepository playerRepository;

    public TurnBatchService(GameService gameService, GameAccess gameAccess,
                            PlayerRepository playerRepository) {
        this.gameService = gameService;
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
    }

    /**
     * Играет подряд до {@code request.turns()} ходов.
     * <p>
     * Прогон прекращается раньше, когда партия кончилась победой или когда ход не
     * сдвинулся: последнее значит, что партия ждёт другого человека, и крутить запрос
     * дальше бессмысленно.
     */
    public AdvanceTurnsResponse advance(UUID gameId, AdvanceTurnsRequest request) {
        EndTurnRequest step = new EndTurnRequest(request.accessToken());
        int played = 0;
        int turn = gameAccess.requireGame(gameId).getTurn();

        for (int i = 0; i < request.turns(); i++) {
            EndTurnResponse answer = gameService.endTurn(gameId, step);
            if (!Boolean.TRUE.equals(answer.advanced())) {
                break;
            }
            played++;
            turn = answer.state().game().turn();
            if (!GameStatus.IN_PROGRESS.name().equals(answer.state().game().status())) {
                break;
            }
        }

        GameEntity after = gameAccess.requireGame(gameId);
        PlayerEntity winner = after.getWinnerPlayerId() == null
                ? null
                : playerRepository.findById(after.getWinnerPlayerId()).orElse(null);
        return new AdvanceTurnsResponse(gameId, played, turn, after.getStatus().name(),
                winner == null ? null : winner.getSlot(),
                after.getVictoryKind() == null ? null : after.getVictoryKind().name());
    }
}
