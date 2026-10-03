package com.sddnw.server.service;

import com.sddnw.server.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Часы хода — backlog-promo, пункт 11: раз в несколько секунд обходят партии, где кто-то
 * ждёт, и дают каждой {@link GameService#tick} — отметить ушедших и посчитать ход, если
 * срок вышел или ждать больше некого.
 * <p>
 * Пять секунд — точность срока: ход в полторы минуты, посчитанный на пять секунд позже,
 * игрок не заметит, а обход чаще только грузил бы базу. Каждая партия — своей
 * транзакцией: сбой одной не должен останавливать часы у остальных.
 */
@Component
public class TurnClock {

    private static final Logger log = LoggerFactory.getLogger(TurnClock.class);

    private final PlayerRepository playerRepository;
    private final GameService gameService;

    public TurnClock(PlayerRepository playerRepository, GameService gameService) {
        this.playerRepository = playerRepository;
        this.gameService = gameService;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void tick() {
        for (UUID gameId : playerRepository.findGameIdsWithSomebodyWaiting()) {
            try {
                gameService.tick(gameId);
            } catch (RuntimeException e) {
                log.warn("Часы хода: партия {} не посчиталась: {}", gameId, e.toString());
            }
        }
    }
}
