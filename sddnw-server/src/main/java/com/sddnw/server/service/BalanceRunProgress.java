package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.entity.history.BalanceRunEntity;
import com.sddnw.server.repository.history.BalanceRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Ход балансового прогона: сколько партий сыграно, чем он кончился.
 * <p>
 * <b>Почему это отдельный бин, а не методы {@link BalanceRunService}.</b> Прогон идёт в
 * фоновом потоке и метит своё продвижение из середины длинной работы. Вызов соседнего
 * метода своего же бина проходит мимо прокси Spring, и {@code @Transactional} на нём не
 * срабатывает вовсе: запись не легла бы в базу, и пульт всю дорогу показывал бы ноль
 * сыгранных партий. На эти грабли в проекте уже наступали с прогоном ходов
 * ({@link TurnBatchService}).
 * <p>
 * Каждая отметка — своя короткая транзакция: пульт спрашивает прогон, пока тот идёт, а
 * одна транзакция на весь прогон не показала бы ничего до самого конца.
 */
@Service
public class BalanceRunProgress {

    private static final Logger log = LoggerFactory.getLogger(BalanceRunProgress.class);

    private final BalanceRunRepository repository;
    private final ObjectMapper objectMapper;

    public BalanceRunProgress(BalanceRunRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Опознаватель этого экземпляра сервера: случайная часть отличает перезапуск того же
     * процесса, номер процесса и машина — для того, кто читает запись глазами.
     */
    static final String INSTANCE = UUID.randomUUID() + " " + ProcessHandle.current().pid() + "@"
            + hostName();

    /**
     * Сколько пульс может молчать, прежде чем прогон сочтут брошенным: четыре пропущенных
     * отметки (хозяин отмечается раз в полминуты, {@link #beat}). Меньше нельзя — под нагрузкой сервер отмечается с опозданием, и живой прогон
     * соседа оказался бы «брошенным».
     */
    static final Duration STALE = Duration.ofMinutes(2);

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException unknown) {
            return "?";
        }
    }

    /** Прогон заводится этим экземпляром: он и будет его вести, и отмечаться за него. */
    public void claim(BalanceRunEntity run) {
        run.setOwnerInstance(INSTANCE);
        run.setHeartbeatAt(OffsetDateTime.now());
    }

    /**
     * Брошен ли прогон — трек техдолга, пункт 15.
     * <p>
     * Прежде при старте закрывался КАЖДЫЙ «идущий» прогон: считалось, что идти он мог только
     * в этом же процессе. Но история прогонов одна на все экземпляры, и сервер, поднятый
     * рядом, обрывал живой оракул соседа на полпути — без единого следа в журнале того, кто
     * его вёл. Теперь брошенным считается только ЧУЖОЙ прогон с замолчавшим пульсом: свой
     * этот процесс ведёт сам, а чужой с живым пульсом ведёт живой сосед. Прогон без пульса
     * вовсе заведён до миграции 073 — вести его некому.
     */
    static Boolean abandoned(BalanceRunEntity run, String instance, OffsetDateTime now) {
        if (instance.equals(run.getOwnerInstance())) {
            return false;
        }
        return run.getHeartbeatAt() == null || run.getHeartbeatAt().isBefore(now.minus(STALE));
    }

    /**
     * Чужие прогоны, чей пульс молчал на ПРОШЛОМ обходе, — с тем пульсом, что был тогда
     * (трек техдолга, пункт 20). Закрывается прогон, только если на двух обходах подряд
     * молчит ОДИН И ТОТ ЖЕ пульс.
     * <p>
     * <b>Зачем второй обход.</b> Сон машины останавливает все серверы разом, и после
     * пробуждения пульс любого прогона «молчал» столько, сколько машина спала. Кто проснётся
     * первым, решает случай: если сосед успеет обойти прогоны раньше, чем хозяин отметит
     * пульс, живой прогон оказался бы проваленным — а сторож круга, прочтя «провален»,
     * оборвал бы круг. Между двумя обходами минута, а хозяин отмечается раз в полминуты:
     * живой хозяин за это время пульс сдвинет, и второй обход увидит другую отметку.
     * Мёртвый — не сдвинет, и прогон закроется минутой позже, чем раньше; это вся цена.
     */
    private final Map<UUID, OffsetDateTime> suspects = new ConcurrentHashMap<>();

    /**
     * Можно ли закрыть прогон: он брошен ({@link #abandoned}) и был под подозрением на
     * прошлом обходе с тем же самым пульсом — то есть хозяин молчал весь промежуток.
     */
    static Boolean closable(BalanceRunEntity run, String instance, OffsetDateTime now,
                            Map<UUID, OffsetDateTime> suspects) {
        if (!abandoned(run, instance, now)) {
            suspects.remove(run.getId());
            return false;
        }
        // Пустой пульс (прогон до миграции 073) хранится как «эпоха»: Map не держит null.
        OffsetDateTime seen = run.getHeartbeatAt() == null ? NO_HEARTBEAT : run.getHeartbeatAt();
        OffsetDateTime previous = suspects.put(run.getId(), seen);
        if (previous != null && seen.isEqual(previous)) {
            suspects.remove(run.getId());
            return true;
        }
        return false;
    }

    private static final OffsetDateTime NO_HEARTBEAT =
            OffsetDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);

    /**
     * Прогон, застигнутый остановкой сервера, живым уже не станет: его поток умер вместе
     * с процессом. Оставленный «идущим», он навсегда запер бы пульт — новый прогон не
     * заводится, пока идёт прежний. Поэтому такие записи закрываются отказом — обходом раз в
     * минуту, начиная со старта; закрывает второй обход подряд, видящий тот же молчащий пульс
     * ({@link #suspects}): сервер, упавший только что, пульс ещё «не просрочил», а спавший
     * вместе с машиной — успеет его сдвинуть.
     */
    @EventListener(ApplicationReadyEvent.class)
    // Транзакция ИСТОРИИ, а не игры: запись прогона живёт в своём источнике данных
    // (PersistenceConfig, HistoryPersistenceConfig). Без имени менеджера Spring взял бы
    // главный — игровой, — и в режиме прогона запись уехала бы в память вместе с партией.
    @Transactional("historyTransactionManager")
    public void closeAbandoned() {
        sweep();
    }

    /** Раз в минуту: закрыть прогоны, которые вести больше некому. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    @Transactional("historyTransactionManager")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now();
        repository.findAllByStatus(BalanceRunService.PAUSED).stream()
                .filter(run -> closable(run, INSTANCE, now, suspects))
                .forEach(run -> {
                    // Приостановленный прогон перезапуска не переживает: пауза живёт в
                    // памяти, и партии прогона тоже (H2, п. 3.90).
                    run.setStatus(BalanceRunService.FAILED);
                    run.setFailure("Сервер, который вёл прогон, остановлен, пока прогон стоял на паузе");
                    run.setFinishedAt(now);
                    repository.save(run);
                });
        repository.findAllByStatus(BalanceRunService.RUNNING).stream()
                .filter(run -> closable(run, INSTANCE, now, suspects))
                .forEach(run -> {
                    run.setStatus(BalanceRunService.FAILED);
                    run.setFinishedAt(now);
                    run.setFailure("сервер, который вёл прогон, перестал отзываться");
                    repository.save(run);
                    log.info("Балансовый прогон {} закрыт: его сервер ({}) молчит с {}",
                            run.getId(), run.getOwnerInstance(), run.getHeartbeatAt());
                });
    }

    /** Пульс: этот экземпляр жив и ведёт свои прогоны — соседи их не тронут. */
    @Scheduled(fixedDelay = 30_000)
    @Transactional("historyTransactionManager")
    public void beat() {
        OffsetDateTime now = OffsetDateTime.now();
        repository.findAllByOwnerInstanceAndStatusIn(INSTANCE,
                List.of(BalanceRunService.RUNNING, BalanceRunService.PAUSED)).forEach(run -> {
            run.setHeartbeatAt(now);
            repository.save(run);
        });
    }

    /**
     * Отмечает паузу — п. 2 этапа 2: прогон виден приостановленным и в списке, и на пульте.
     * <p>
     * Статус меняется сразу, а партии, уже начатые, ещё доигрываются: пауза мягкая
     * ({@link BalanceRunService#PAUSED}). Число сыгранных при этом растёт — и правильно,
     * это и есть та работа, которую не выбросили.
     */
    @Transactional("historyTransactionManager")
    public void paused(UUID runId) {
        repository.findById(runId).ifPresent(run -> {
            if (BalanceRunService.RUNNING.equals(run.getStatus())) {
                run.setStatus(BalanceRunService.PAUSED);
                repository.save(run);
            }
        });
    }

    /** Снимает отметку паузы. */
    @Transactional("historyTransactionManager")
    public void resumed(UUID runId) {
        repository.findById(runId).ifPresent(run -> {
            if (BalanceRunService.PAUSED.equals(run.getStatus())) {
                run.setStatus(BalanceRunService.RUNNING);
                repository.save(run);
            }
        });
    }

    @Transactional("historyTransactionManager")
    public void played(UUID runId, Integer played) {
        repository.findById(runId).ifPresent(run -> {
            run.setPlayed(played);
            repository.save(run);
        });
    }

    @Transactional("historyTransactionManager")
    public void finished(UUID runId, BalanceVerdictRules.Assessment assessment,
                         List<BalanceVerdictRules.Empire> measurements) {
        repository.findById(runId).ifPresent(run -> {
            run.setStatus(BalanceRunService.FINISHED);
            run.setFinishedAt(OffsetDateTime.now());
            try {
                run.setResult(objectMapper.writeValueAsString(assessment));
                // Сырые замеры — вместе с итогом: правила чтения меняются чаще замеров,
                // и без них всякая правка правил требовала бы переигрывать прогон заново.
                run.setMeasurements(objectMapper.writeValueAsString(measurements));
            } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
                throw new IllegalStateException("Не удалось записать итог прогона", broken);
            }
            repository.save(run);
        });
    }

    /**
     * Итог поиска сборок — этап 3.
     * <p>
     * Ладдер приходит уже записанным в JSON: он собран не правилами оценки, а оракулом, и
     * складывать его тем же способом, что и приговоры ценам, значило бы тащить в этот бин
     * ещё один словарь типов.
     */
    @Transactional("historyTransactionManager")
    public void searched(UUID runId, String ladder) {
        repository.findById(runId).ifPresent(run -> {
            run.setStatus(BalanceRunService.FINISHED);
            run.setFinishedAt(OffsetDateTime.now());
            run.setLadder(ladder);
            repository.save(run);
        });
    }

    /** Новый приговор старому прогону: замеры те же, правила нынешние. */
    @Transactional("historyTransactionManager")
    public void reassessed(UUID runId, BalanceVerdictRules.Assessment assessment) {
        repository.findById(runId).ifPresent(run -> {
            try {
                run.setResult(objectMapper.writeValueAsString(assessment));
            } catch (com.fasterxml.jackson.core.JsonProcessingException broken) {
                throw new IllegalStateException("Не удалось записать итог прогона", broken);
            }
            repository.save(run);
        });
    }

    @Transactional("historyTransactionManager")
    public void failed(UUID runId, String reason) {
        repository.findById(runId).ifPresent(run -> {
            run.setStatus(BalanceRunService.FAILED);
            run.setFinishedAt(OffsetDateTime.now());
            String text = reason == null ? "неизвестная причина" : reason;
            run.setFailure(text.substring(0, Math.min(text.length(), 500)));
            repository.save(run);
        });
    }
}
