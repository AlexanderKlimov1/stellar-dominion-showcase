package com.moo3.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.repository.history.BalanceRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
     * Прогон, застигнутый остановкой сервера, живым уже не станет: его поток умер вместе
     * с процессом. Оставленный «идущим», он навсегда запер бы пульт — новый прогон не
     * заводится, пока идёт прежний. Поэтому при старте такие записи закрываются отказом.
     */
    @EventListener(ApplicationReadyEvent.class)
    // Транзакция ИСТОРИИ, а не игры: запись прогона живёт в своём источнике данных
    // (PersistenceConfig, HistoryPersistenceConfig). Без имени менеджера Spring взял бы
    // главный — игровой, — и в режиме прогона запись уехала бы в память вместе с партией.
    @Transactional("historyTransactionManager")
    public void closeAbandoned() {
        repository.findAllByStatus(BalanceRunService.PAUSED).forEach(run -> {
            // Приостановленный прогон перезапуска не переживает: пауза живёт в памяти, и
            // партии прогона тоже (H2, п. 3.90). Оставить его «на паузе» значило бы
            // навсегда запереть пульт прогоном, который уже никогда не продолжится.
            run.setStatus(BalanceRunService.FAILED);
            run.setFailure("Сервер перезапущен, пока прогон стоял на паузе");
            run.setFinishedAt(OffsetDateTime.now());
            repository.save(run);
        });
        repository.findAllByStatus(BalanceRunService.RUNNING).forEach(run -> {
            run.setStatus(BalanceRunService.FAILED);
            run.setFinishedAt(OffsetDateTime.now());
            run.setFailure("сервер перезапустили, пока прогон шёл");
            repository.save(run);
            log.info("Балансовый прогон {} закрыт: сервер перезапускали", run.getId());
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
