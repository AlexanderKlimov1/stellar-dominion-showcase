package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
    private final PresenceService presence;

    public GameAccess(GameRepository gameRepository, PlayerRepository playerRepository,
                      PresenceService presence) {
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.presence = presence;
    }

    /**
     * Отметить, что игрок за столом, — backlog-promo, пункт 11.
     * <p>
     * Здесь, а не в каждом действии: пропуск предъявляет КАЖДОЕ действие над партией, и
     * других дверей нет. Считается только изменяющий запрос — чтение карты и отчёта идёт
     * само, пока вкладка открыта, и присутствия не доказывает. Вне запроса (фазы хода,
     * прогоны) отмечать некого.
     */
    private void present(UUID playerId) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && !"GET".equalsIgnoreCase(attributes.getRequest().getMethod())) {
            presence.act(playerId);
        }
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
        UUID playerId = playerRepository.findIdByGameIdAndAccessToken(gameId, accessToken)
                .orElseThrow(() -> new ForbiddenException("game.unknownPlayer"));
        present(playerId);
        return playerId;
    }

    public PlayerEntity requirePlayer(GameEntity game, String accessToken) {
        PlayerEntity player = playerRepository.findByGameIdAndAccessToken(game.getId(), accessToken)
                .orElseThrow(() -> new ForbiddenException("game.unknownPlayer"));
        present(player.getId());
        return player;
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
