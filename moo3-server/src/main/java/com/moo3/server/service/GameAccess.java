package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.domain.enums.GameStatus;
import com.moo3.server.repository.GameRepository;
import com.moo3.server.repository.PlayerRepository;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Проверки доступа к партии: существует ли игра, в нужном ли она состоянии и кто именно
 * её просит.
 * <p>
 * Игрок опознаётся пропуском ({@code accessToken}), выданным при входе в партию: он же
 * подтверждает право стартовать и удалять игру. Проверки нужны каждому действию над
 * партией — ход, карта, колонии, исследования, — поэтому живут отдельно, а не повторяются
 * в каждом сервисе.
 */
@Service
public class GameAccess {

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;

    public GameAccess(GameRepository gameRepository, PlayerRepository playerRepository) {
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
    }

    public GameEntity requireGame(UUID gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
    }

    /** Партия, которая уже идёт: ходы, колонии и исследования доступны только в ней. */
    public GameEntity requireRunningGame(UUID gameId) {
        GameEntity game = requireGame(gameId);
        requireInProgress(game);
        return game;
    }

    public void requireLobby(GameEntity game) {
        if (game.getStatus() != GameStatus.LOBBY) {
            throw new ConflictException("game.alreadyStarted");
        }
    }

    public void requireInProgress(GameEntity game) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            throw new ConflictException("game.notRunning");
        }
    }

    /**
     * Игрок по пропуску, без чтения самой партии.
     * <p>
     * Нужен там, где партию потом берут под замок: прочитанные заранее игра и игрок
     * попали бы в контекст со своими тогдашними версиями, и запись под замком падала бы
     * на проверке версии из-за соседа, успевшего раньше. Поэтому возвращается только
     * идентификатор — сущности читаются уже под замком.
     */
    public UUID requireToken(UUID gameId, String accessToken) {
        return playerRepository.findIdByGameIdAndAccessToken(gameId, accessToken)
                .orElseThrow(() -> new ForbiddenException("game.unknownPlayer"));
    }

    public PlayerEntity requirePlayer(GameEntity game, String accessToken) {
        return playerRepository.findByGameIdAndAccessToken(game.getId(), accessToken)
                .orElseThrow(() -> new ForbiddenException("game.unknownPlayer"));
    }

    /** Участник идущей партии — обычный случай для действий внутри хода. */
    public PlayerEntity requirePlayerOfRunningGame(UUID gameId, String accessToken) {
        return requirePlayer(requireRunningGame(gameId), accessToken);
    }

    public void requireHost(GameEntity game, String accessToken) {
        PlayerEntity player = requirePlayer(game, accessToken);
        if (!player.getId().equals(game.getHostPlayerId())) {
            throw new ForbiddenException("game.creatorOnly");
        }
    }
}
