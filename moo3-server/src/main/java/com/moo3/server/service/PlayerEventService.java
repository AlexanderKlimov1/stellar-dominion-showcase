package com.moo3.server.service;

import com.moo3.server.domain.entity.PlayerEventEntity;
import com.moo3.server.repository.PlayerEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * События, случившиеся с игроком внутри хода — п. 11.1.
 * <p>
 * Половина интересного в партии происходит не по нажатию самого игрока: сосед объявил
 * войну, кто-то познакомился с его расой, шпион вынес технологию, десант забрал колонию.
 * Всё это надо показать игроку в итогах хода — значит, до конца хода где-то держать.
 * <p>
 * Событие пишется строкой в базу, а не в память: игрок может в этот момент вообще не быть
 * подключён, а его подписка — оборваться. В конце хода {@code TurnService} забирает
 * накопленное в отчёт и очищает очередь.
 */
@Service
public class PlayerEventService {

    private final PlayerEventRepository repository;

    public PlayerEventService(PlayerEventRepository repository) {
        this.repository = repository;
    }

    /** Событие империи: дипломатия, разведка, потеря колонии. */
    @Transactional
    public void record(UUID gameId, UUID playerId, Integer turn, String code, MessageKey message) {
        record(gameId, playerId, turn, code, message, null, null);
    }

    /** Событие, привязанное к месту: по нему клиент поймёт, что обновлять. */
    @Transactional
    public void record(UUID gameId, UUID playerId, Integer turn, String code, MessageKey message,
                       UUID starSystemId, UUID planetId) {
        if (gameId == null || playerId == null || message == null) {
            return;
        }
        PlayerEventEntity event = new PlayerEventEntity();
        event.setGameId(gameId);
        event.setPlayerId(playerId);
        event.setTurn(turn);
        event.setCode(code);
        event.setMessageKey(message.key());
        event.setArgs(message.storedArgs());
        event.setStarSystemId(starSystemId);
        event.setPlanetId(planetId);
        repository.save(event);
    }

    /**
     * Перекладывает накопленное партией в отчёт хода и очищает очередь.
     * <p>
     * Вызывается один раз за ход, под замком партии: события уходят ровно в те итоги,
     * которые игрок увидит следующими.
     */
    @Transactional
    public void drainInto(UUID gameId, TurnReport report) {
        List<PlayerEventEntity> events = repository.findAllByGameIdOrderByTurnAsc(gameId);
        if (events.isEmpty()) {
            return;
        }
        for (PlayerEventEntity event : events) {
            report.add(event.getPlayerId(), event.getCode(), event.getMessageKey(),
                    event.getArgs(), event.getStarSystemId(), event.getPlanetId());
        }
        repository.deleteAll(events);
    }
}
