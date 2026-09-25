package com.moo3.server.web;

import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.service.GameAccess;
import com.moo3.server.service.GameEventService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Подписка на события партии — п. 11.1.
 * <p>
 * Игроки ходят одновременно, и почти всё интересное случается не по запросу игрока:
 * сосед закончил ход, галактика пересчиталась. Вместо того чтобы каждый клиент опрашивал
 * сервер, сервер сам шлёт события в открытое соединение (SSE).
 * <p>
 * Пропуск здесь принимается параметром запроса: подписку открывает {@code EventSource}
 * браузера, а он заголовков слать не умеет. На остальных запросах пропуск идёт заголовком
 * {@code X-Access-Token} — см. {@link AccessTokenResolver}.
 */
@RestController
@RequestMapping("/api/games")
public class GameEventController {

    private final GameAccess gameAccess;
    private final GameEventService events;

    public GameEventController(GameAccess gameAccess, GameEventService events) {
        this.gameAccess = gameAccess;
        this.events = events;
    }

    @GetMapping(value = "/{gameId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID gameId, @AccessToken String accessToken) {
        PlayerEntity player = gameAccess.requirePlayer(gameAccess.requireGame(gameId), accessToken);
        return events.subscribe(gameId, player.getId());
    }
}
