package com.moo3.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.domain.entity.TurnReportEntity;
import com.moo3.server.dto.GameEventDto;
import com.moo3.server.dto.TurnEventDto;
import com.moo3.server.dto.TurnReportDto;
import com.moo3.server.repository.TurnReportRepository;
import com.moo3.server.web.error.ConflictException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * События партии подписчикам — п. 11.1.
 * <p>
 * Игроки ходят одновременно, и почти всё интересное случается не по запросу игрока:
 * сосед закончил ход, галактика пересчиталась. Опрашивать сервер ради этого пришлось бы
 * всем и постоянно — сотня игроков превратилась бы в сотню запросов в секунду на пустом
 * месте. Поэтому сервер сам рассказывает о событиях по подписке (SSE), а клиент слушает.
 * <p>
 * Подписки живут в памяти экземпляра: они и есть открытые соединения, переживать
 * перезапуск им нечем — клиент переподключается сам. Событиям между экземплярами помогает
 * {@link GameEventBridge}: он разносит их через сам Postgres, чтобы игроки, подключённые
 * к разным экземплярам, видели одно и то же.
 * <p>
 * Отчёт хода при этом хранится в базе, а не только в событии: игрок, у которого оборвалось
 * соединение или который перезагрузил страницу, должен узнать, что изменилось, — иначе
 * пересчёт для него произойдёт молча.
 */
@Service
public class GameEventService {

    private static final Logger log = LoggerFactory.getLogger(GameEventService.class);

    /**
     * Сколько живёт подписка. Час: партия идёт долго, а обрыв клиент переживает
     * переподключением, поэтому дольше держать соединение незачем.
     */
    private static final Duration SUBSCRIPTION_TTL = Duration.ofHours(1);

    /**
     * Как часто в подписку уходит пустой пинг. Прокси и браузеры рвут молчащее соединение
     * через минуту-другую, а между ходами партии молчание может длиться и дольше.
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final Map<UUID, List<Subscriber>> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "game-events-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private final TurnReportRepository turnReportRepository;
    private final Messages messages;
    private final CatalogTexts catalogTexts;
    private final ObjectMapper objectMapper;

    public GameEventService(TurnReportRepository turnReportRepository, ObjectMapper objectMapper,
                            Messages messages, CatalogTexts catalogTexts) {
        this.messages = messages;
        this.catalogTexts = catalogTexts;
        this.turnReportRepository = turnReportRepository;
        this.objectMapper = objectMapper;
        heartbeats.scheduleWithFixedDelay(this::ping,
                HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    /** Подписчик: соединение вместе с игроком, которому оно принадлежит. */
    private record Subscriber(UUID playerId, SseEmitter emitter) {
    }

    /** Открывает подписку игрока на события его партии. */
    public SseEmitter subscribe(UUID gameId, UUID playerId) {
        SseEmitter emitter = new SseEmitter(SUBSCRIPTION_TTL.toMillis());
        Subscriber subscriber = new Subscriber(playerId, emitter);
        subscribers.computeIfAbsent(gameId, key -> new CopyOnWriteArrayList<>()).add(subscriber);

        emitter.onCompletion(() -> remove(gameId, subscriber));
        emitter.onTimeout(() -> remove(gameId, subscriber));
        emitter.onError(error -> remove(gameId, subscriber));

        // Первое сообщение сразу: по нему клиент понимает, что подписка жива, а прокси
        // не держит ответ в буфере до первого события.
        send(subscriber, new GameEventDto("SUBSCRIBED", gameId, null, playerId, "Подписка на события партии", null));
        log.debug("Игрок {} подписался на события партии {}", playerId, gameId);
        return emitter;
    }

    /** Событие, одинаковое для всех участников партии. */
    public void publish(GameEventDto event) {
        publish(event.gameId(), playerId -> event);
    }

    /**
     * Событие, у каждого своё: отчёт хода у игроков разный — у одного «выкрал технологию»,
     * у другого «у вас выкрали».
     */
    public void publish(UUID gameId, Function<UUID, GameEventDto> forPlayer) {
        for (Subscriber subscriber : subscribers.getOrDefault(gameId, List.of())) {
            send(subscriber, forPlayer.apply(subscriber.playerId()));
        }
    }

    /** Сколько подписок держит этот экземпляр по партии — для журнала и диагностики. */
    public Integer subscriberCount(UUID gameId) {
        return subscribers.getOrDefault(gameId, List.of()).size();
    }

    /**
     * Сохраняет отчёты хода: строка на игрока, переписывается каждый ход.
     * <p>
     * Отчёт живёт в базе, а не только в событии, потому что подписка могла оборваться,
     * а игрок мог перезагрузить страницу: без записи он бы не узнал, что случилось.
     */
    @Transactional
    public void storeReports(UUID gameId, List<UUID> playerIds, TurnReport report) {
        for (UUID playerId : playerIds) {
            TurnReportEntity entity = turnReportRepository.findByPlayerId(playerId)
                    .orElseGet(TurnReportEntity::new);
            entity.setGameId(gameId);
            entity.setPlayerId(playerId);
            entity.setTurn(report.turn());
            entity.setState(serialize(report.forPlayer(playerId)));
            turnReportRepository.save(entity);
        }
    }

    /** Последний отчёт игрока: его же клиент запрашивает после перезагрузки страницы. */
    @Transactional(readOnly = true)
    public TurnReportDto lastReport(UUID playerId) {
        return turnReportRepository.findByPlayerId(playerId)
                .map(entity -> shown(deserialize(entity.getState())))
                .orElse(null);
    }

    /**
     * Отчёт на языке запроса — п. 3.5: в базе он лежит ключами, читается строками.
     * <p>
     * Ход считается до того, как отчёт попросили показать, поэтому языка при записи нет.
     * Собирается строка здесь, в ответе на запрос, — и один и тот же отчёт двое читают
     * каждый на своём языке.
     * <p>
     * <b>Отчёт, сохранённый до перехода на ключи, отдаётся как есть:</b> у его событий
     * заполнен {@code text} и пуст {@code key}. Так партия, начатая на прошлой версии,
     * доигрывается без пустых строк в итогах хода.
     * <p>
     * Подстановки проходят через {@link CatalogTexts}: ссылка на здание или технологию
     * превращается в название здесь же, на языке читателя, — справочники тоже переведены,
     * и готовое название в подстановке выдало бы язык того, кто закончил ход.
     */
    public TurnReportDto shown(TurnReportDto report) {
        if (report == null) {
            return null;
        }
        List<TurnEventDto> events = report.events().stream()
                .map(event -> event.key() == null
                        ? event
                        : event.shown(messages.event(event.key(), named(event.args()))))
                .toList();
        return new TurnReportDto(report.turn(), events,
                report.changedSystemIds(), report.changedPlanetIds());
    }

    /** Подстановки события: ссылки на справочник — названиями, остальное как есть. */
    private List<String> named(List<String> args) {
        return args == null ? List.of() : args.stream().map(catalogTexts::resolve).toList();
    }

    private void send(Subscriber subscriber, GameEventDto event) {
        if (event == null) {
            return;
        }
        try {
            subscriber.emitter().send(SseEmitter.event().name(event.type()).data(event));
        } catch (IOException | IllegalStateException failure) {
            // Оборвавшееся соединение — обычное дело: клиент закрыл вкладку или ушёл в сон.
            subscriber.emitter().completeWithError(failure);
        }
    }

    /** Пустой комментарий в поток: соединение без трафика прокси считают мёртвым. */
    private void ping() {
        for (Map.Entry<UUID, List<Subscriber>> entry : subscribers.entrySet()) {
            for (Subscriber subscriber : entry.getValue()) {
                try {
                    subscriber.emitter().send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException failure) {
                    subscriber.emitter().completeWithError(failure);
                }
            }
        }
    }

    private void remove(UUID gameId, Subscriber subscriber) {
        List<Subscriber> list = subscribers.get(gameId);
        if (list == null) {
            return;
        }
        list.remove(subscriber);
        if (list.isEmpty()) {
            subscribers.remove(gameId);
        }
    }

    private String serialize(TurnReportDto report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException failure) {
            throw new ConflictException("game.reportWrite", failure.getOriginalMessage());
        }
    }

    private TurnReportDto deserialize(String state) {
        try {
            return objectMapper.readValue(state, TurnReportDto.class);
        } catch (JsonProcessingException failure) {
            throw new ConflictException("game.reportRead", failure.getOriginalMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
        subscribers.values().forEach(list -> list.forEach(subscriber -> subscriber.emitter().complete()));
        subscribers.clear();
    }
}
