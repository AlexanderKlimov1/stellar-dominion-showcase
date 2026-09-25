package com.moo3.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.dto.GameEventDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * Разнос событий партии между экземплярами сервера — п. 11.1.
 * <p>
 * Подписка игрока живёт на том экземпляре, к которому он подключился. Пока сервер один,
 * этого достаточно; как только их становится два, игроки одной партии оказываются на
 * разных, и пересчёт хода на одном экземпляре второму не виден. Поэтому событие идёт
 * через общий для всех Postgres: {@code NOTIFY} на канал, все экземпляры слушают.
 * <p>
 * Очередь сообщений сюда не ставится намеренно: база и так общая для всех экземпляров,
 * события мелкие и терять их не страшно — клиент в любом случае перечитывает состояние.
 * <p>
 * Само событие идёт без личного отчёта: он у каждого игрока свой и лежит в базе, откуда
 * принимающий экземпляр его и берёт. В канал влезает 8 КБ, отчёт туда мог бы не поместиться.
 * <p>
 * Свои же сообщения экземпляр пропускает по метке отправителя: локально они уже разосланы.
 * <p>
 * <b>Соединений тут два, и они разные:</b> рассылка ({@code NOTIFY}) берёт соединение из
 * общего пула на один запрос и тут же возвращает, а слушатель ({@code LISTEN}) держит своё
 * <b>мимо пула</b> — см. {@link #listen()}.
 */
@Service
// Мост между экземплярами держится на LISTEN/NOTIFY САМОГО Postgres, и на другой базе его
// нет. В режиме балансового прогона (профиль `balance`, база в памяти) он и не нужен:
// подписчиков там не бывает, партии живут минуты и никто на них не смотрит. Поэтому мост
// в этом режиме просто не заводится — иначе сервер падал бы при старте на чужом диалекте.
@Profile("!balance")
public class GameEventBridge {

    private static final Logger log = LoggerFactory.getLogger(GameEventBridge.class);

    private static final String CHANNEL = "moo3_game_events";

    /** Пауза между опросами канала: слушающее соединение спит, пока сообщений нет. */
    private static final int POLL_MS = 500;

    /** Пауза перед новой попыткой, если слушающее соединение оборвалось. */
    private static final long RECONNECT_MS = 5_000;

    /** Метка экземпляра: по ней отличаются свои сообщения от чужих. */
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * Настройки базы — те же, что у пула: слушающее соединение открывается драйвером
     * напрямую (см. {@link #listen()}), но ходит в ту же базу и под тем же именем.
     */
    private final DataSourceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final GameEventService events;
    private final ObjectMapper objectMapper;
    private final Boolean enabled;

    private volatile Boolean running = Boolean.FALSE;
    private Thread listener;

    public GameEventBridge(DataSourceProperties properties,
                           JdbcTemplate jdbcTemplate,
                           GameEventService events,
                           ObjectMapper objectMapper,
                           @Value("${moo3.events.cross-instance:true}") Boolean enabled) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.events = events;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!Boolean.TRUE.equals(enabled)) {
            log.info("Разнос событий между экземплярами выключен: moo3.events.cross-instance=false");
            return;
        }
        running = Boolean.TRUE;
        listener = new Thread(this::listen, "game-events-bridge");
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * Рассылает событие остальным экземплярам. Ошибка рассылки роняет только запись в
     * журнал: ход уже посчитан, и терять его из-за подписок нельзя.
     */
    public void broadcast(GameEventDto event) {
        if (!Boolean.TRUE.equals(enabled)) {
            return;
        }
        try {
            jdbcTemplate.queryForObject("SELECT pg_notify(?, ?)", String.class, CHANNEL, payload(event));
        } catch (Exception failure) {
            log.warn("Не удалось разослать событие {} партии {}: {}",
                    event.type(), event.gameId(), failure.getMessage());
        }
    }

    private String payload(GameEventDto event) throws Exception {
        return objectMapper.writeValueAsString(new Envelope(
                instanceId, event.type(), event.gameId(), event.turn(), event.playerId(), event.text()));
    }

    /** Конверт для канала: личного отчёта в нём нет — его получатель берёт из базы. */
    private record Envelope(String instance, String type, UUID gameId, Integer turn, UUID playerId, String text) {
    }

    /**
     * Слушает канал на своём соединении — <b>мимо пула</b>.
     * <p>
     * {@code LISTEN} живёт ровно столько, сколько живёт соединение, поэтому это соединение
     * занято постоянно и не возвращается никогда. Пулу такое не подходит по двум причинам,
     * и обе настоящие: слот занят навсегда (из двадцати четырёх остаётся двадцать три, и
     * пул не может его ни переоткрыть, ни проверить), а Hikari честно считает
     * невозвращённое соединение утечкой — через минуту после старта в журнал падало
     * «Apparent connection leak detected» со стеком этого самого метода.
     * <p>
     * Поэтому соединение открывается напрямую драйвером, теми же настройками, что и пул
     * ({@code spring.datasource.*}): у постоянного слушателя с пулом нет ничего общего,
     * кроме адреса базы.
     */
    private void listen() {
        while (Boolean.TRUE.equals(running)) {
            try (Connection connection = DriverManager.getConnection(
                    properties.determineUrl(),
                    properties.determineUsername(),
                    properties.determinePassword())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                PGConnection pgConnection = connection.unwrap(PGConnection.class);
                log.info("Слушаю события партий на канале {} (экземпляр {})", CHANNEL, instanceId);

                while (Boolean.TRUE.equals(running) && !connection.isClosed()) {
                    PGNotification[] notifications = pgConnection.getNotifications(POLL_MS);
                    if (notifications == null) {
                        continue;
                    }
                    for (PGNotification notification : notifications) {
                        deliver(notification.getParameter());
                    }
                }
            } catch (Exception failure) {
                if (Boolean.TRUE.equals(running)) {
                    log.warn("Слушающее соединение оборвалось, повтор через {} с: {}",
                            RECONNECT_MS / 1000, failure.getMessage());
                    sleep();
                }
            }
        }
    }

    /** Раздаёт чужое событие своим подписчикам; личный отчёт берётся из базы. */
    private void deliver(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (instanceId.equals(node.path("instance").asText())) {
                return;
            }
            // Пустые поля в JSON не приходят вовсе (в сериализации включено non_null),
            // поэтому проверяем наличие, а не значение: отсутствующий playerId — обычное
            // дело для событий про всю партию.
            String type = node.path("type").asText();
            UUID gameId = UUID.fromString(node.path("gameId").asText());
            Integer turn = node.hasNonNull("turn") ? node.get("turn").asInt() : null;
            String text = node.hasNonNull("text") ? node.get("text").asText() : null;
            UUID playerId = node.hasNonNull("playerId") ? UUID.fromString(node.get("playerId").asText()) : null;

            events.publish(gameId, subscriber -> new GameEventDto(
                    type,
                    gameId,
                    turn,
                    playerId,
                    text,
                    "TURN_ADVANCED".equals(type) ? events.lastReport(subscriber) : null));
        } catch (Exception failure) {
            log.warn("Не удалось разобрать событие партии: {}", failure.getMessage());
        }
    }

    private void sleep() {
        try {
            Thread.sleep(RECONNECT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = Boolean.FALSE;
        }
    }

    @PreDestroy
    void stop() {
        running = Boolean.FALSE;
        if (listener != null) {
            listener.interrupt();
        }
    }
}
