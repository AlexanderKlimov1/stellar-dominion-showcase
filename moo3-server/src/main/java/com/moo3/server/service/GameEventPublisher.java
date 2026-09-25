package com.moo3.server.service;

import com.moo3.server.domain.entity.GameEntity;
import com.moo3.server.domain.entity.PlayerEntity;
import com.moo3.server.dto.GameEventDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Кто и о чём рассказывает игрокам партии — п. 11.1.
 * <p>
 * Одно место на все события: локальным подписчикам их раздаёт {@link GameEventService},
 * остальным экземплярам — {@link GameEventBridge}. Вызывающему знать про это разделение
 * незачем: он говорит «ход посчитан», а куда это уйдёт — забота этого класса.
 * <p>
 * <b>Событие уходит только после фиксации транзакции.</b> Иначе получалось вот что:
 * подписчику приходило «ход посчитан», он тут же перечитывал состояние — и читал старое,
 * потому что транзакция ещё не закрылась. Ход у него оставался прежним, а казна пустой.
 */
@Service
public class GameEventPublisher {

    private final GameEventService events;

    /**
     * Мост между экземплярами — НЕОБЯЗАТЕЛЬНЫЙ: он держится на LISTEN/NOTIFY самого
     * Postgres, а в режиме балансового прогона база в памяти и моста нет вовсе
     * ({@code @Profile("!balance")} у {@link GameEventBridge}). Подписчиков в том режиме
     * тоже не бывает, так что рассылать некому и незачем.
     */
    private final ObjectProvider<GameEventBridge> bridge;

    public GameEventPublisher(GameEventService events, ObjectProvider<GameEventBridge> bridge) {
        this.events = events;
        this.bridge = bridge;
    }

    /**
     * Ход пересчитан: у каждого игрока свой отчёт о том, что изменилось.
     * <p>
     * Отчёты ложатся в базу той же транзакцией, что и сам ход, — они его часть. А вот
     * рассылка ждёт фиксации: до неё читать новое состояние ещё нечего.
     */
    public void turnAdvanced(GameEntity game, List<PlayerEntity> players, TurnReport report) {
        List<UUID> ids = players.stream().map(PlayerEntity::getId).toList();
        events.storeReports(game.getId(), ids, report);

        UUID gameId = game.getId();
        Integer turn = game.getTurn();
        afterCommit(() -> {
            /*
              Отчёт в событии подписки НЕ уходит, и это не упущение — п. 3.5.

              Рассылка идёт вне запроса, языка получателя в ней нет, а отчёт лежит ключами:
              приложить его значило бы выбрать язык за читателя. Клиент этого поля и не
              читал никогда — узнав о пересчёте, он перезапрашивает отчёт сам
              (`getTurnReport`), и приходит он уже на языке запроса.
            */
            events.publish(gameId, playerId -> new GameEventDto(
                    "TURN_ADVANCED", gameId, turn, null, "Наступил ход " + turn, null));
            bridge.ifAvailable(one -> one.broadcast(new GameEventDto(
                    "TURN_ADVANCED", gameId, turn, null, "Наступил ход " + turn, null)));
        });
    }

    /** Игрок закончил ход: остальным видно, кого ещё ждут. */
    public void playerReady(GameEntity game, PlayerEntity player) {
        publish(new GameEventDto(
                "PLAYER_READY",
                game.getId(),
                game.getTurn(),
                player.getId(),
                player.getName() + " закончил ход",
                null));
    }

    /** Партия стартовала: те, кто ждал в лобби, узнают об этом без опроса. */
    public void gameStarted(GameEntity game) {
        publish(new GameEventDto(
                "GAME_STARTED", game.getId(), game.getTurn(), null, "Партия началась", null));
    }

    /** В партию вошёл игрок: лобби у остальных обновляется само. */
    public void playerJoined(GameEntity game, PlayerEntity player) {
        publish(new GameEventDto(
                "PLAYER_JOINED", game.getId(), game.getTurn(), player.getId(),
                player.getName() + " вошёл в игру", null));
    }

    private void publish(GameEventDto event) {
        afterCommit(() -> {
            events.publish(event);
            bridge.ifAvailable(one -> one.broadcast(event));
        });
    }

    /**
     * Откладывает рассылку до фиксации транзакции, а вне транзакции шлёт сразу.
     * <p>
     * Событие — рассказ о том, что уже случилось. Пока транзакция открыта, для остальных
     * не случилось ничего, и разослать раньше значит позвать читать вчерашнее состояние.
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
