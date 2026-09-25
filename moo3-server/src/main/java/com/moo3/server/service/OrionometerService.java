package com.moo3.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.dto.OrionometerDto;
import com.moo3.server.web.error.ConflictException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Орионометр: запись того, как человек играет в ОРИГИНАЛ, — и пульт к ней.
 *
 * <p><b>Зачем это есть.</b> Инструмент игры в оригинал ({@code tools/moo2_play.py}) водит
 * игру сам, и каждое незнакомое управление приходится выводить по снимкам: где кнопка, что
 * она делает, каким нажатием отдаётся приказ. За один вечер так набралось пять неверных
 * догадок подряд про одну только отправку флота, и каждая стоила получасовой партии.
 * Посмотреть, как ту же дорогу проходит человек, дешевле любого разбора.
 *
 * <p><b>Почему процессом, а не внутри сервера.</b> Записывать нажатия умеет только тот, кто
 * живёт на той же машине, где открыта игра, и спрашивает состояние мыши у самой системы.
 * Это питоновский {@code tools/moo2_record.py}; сервер его лишь запускает и останавливает,
 * а состояние читает файлом, который тот кладёт рядом с протоколом.
 *
 * <p><b>Остановка — просьбой, а не убийством.</b> Пульт кладёт файл-просьбу, запись видит
 * её и заканчивает сама, дописав протокол. Убить процесс можно и грубо, но тогда последняя
 * строка может не долететь до диска, а протокол — это всё, ради чего запись велась.
 * Поэтому грубый путь оставлен на случай, когда просьба осталась без ответа.
 */
@Service
public class OrionometerService {

    private static final Logger log = LoggerFactory.getLogger(OrionometerService.class);

    /** Сколько ждать, что запись отзовётся на просьбу, прежде чем прекращать её силой. */
    private static final long STOP_PATIENCE_MS = 4000;

    private final ObjectMapper mapper = new ObjectMapper();

    private final String python;
    private final Path recorder;
    private final Path brain;
    private final Path recordDir;

    private Process recording;
    private Process brainRun;
    private String failure;

    public OrionometerService(
            @Value("${moo3.orionometer.python:python}") String python,
            @Value("${moo3.orionometer.recorder:../tools/moo2_record.py}") String recorder,
            @Value("${moo3.orionometer.brain:../tools/moo3_learn.py}") String brain,
            @Value("${moo3.orionometer.record-dir:../tools/moo2-scenes/record}") String recordDir) {
        this.python = python;
        this.recorder = Path.of(recorder).toAbsolutePath().normalize();
        this.brain = Path.of(brain).toAbsolutePath().normalize();
        this.recordDir = Path.of(recordDir).toAbsolutePath().normalize();
    }

    /** Открыто ли окно самой игры: без него записывать нечего. */
    public Boolean gameOpen() {
        // Спрашиваем у той же стороны, что и запись, — у списка процессов системы. Своего
        // способа заглянуть в чужое окно у сервера нет, а заводить его ради одной проверки
        // дороже, чем прочитать список.
        try {
            return ProcessHandle.allProcesses()
                    .map(one -> one.info().command().orElse(""))
                    .anyMatch(one -> one.toLowerCase().contains("dosbox"));
        } catch (RuntimeException cannotAsk) {
            log.debug("не удалось спросить список процессов", cannotAsk);
            return Boolean.FALSE;
        }
    }

    /**
     * Начать запись. Игра должна быть уже открыта — иначе записывать нечего.
     *
     * @param minutes сколько минут писать, если не остановят раньше
     */
    public synchronized OrionometerDto start(Integer minutes) {
        if (alive(recording)) {
            throw new ConflictException("orionometer.alreadyRunning");
        }
        if (!Boolean.TRUE.equals(gameOpen())) {
            throw new ConflictException("orionometer.gameClosed");
        }
        failure = null;
        List<String> command = List.of(python, recorder.toString(),
                "--minutes", String.valueOf(minutes == null || minutes <= 0 ? 60 : minutes));
        try {
            // Папку заводим САМИ, а не надеемся на записыватель: вывод процесса
            // перенаправляется в файл ВНУТРИ неё, и без неё запуск падает ещё до того,
            // как записыватель успеет её создать.
            Files.createDirectories(recordDir);
            ProcessBuilder how = new ProcessBuilder(command)
                    .directory(recorder.getParent().getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(recordDir.resolve("recorder.log").toFile());
            // Питон, чей вывод перенаправлен в файл, пишет его кодировкой КОНСОЛИ Windows,
            // а читаем мы UTF-8 — и причина отказа, сказанная по-русски, превращалась в
            // нечитаемые байты, то есть пропадала совсем. Просим UTF-8 у самого процесса:
            // так обе стороны говорят на одном языке, какой бы скрипт ни запустили.
            how.environment().put("PYTHONIOENCODING", "utf-8");
            recording = how.start();
            log.info("Орионометр: запись начата, {}", command);
        } catch (IOException notStarted) {
            failure = "не удалось запустить запись: " + notStarted.getMessage();
            log.error("Орионометр не запустился", notStarted);
        }
        return state();
    }

    /** Остановить запись: сперва просьбой, и только упрямую — силой. */
    public synchronized OrionometerDto stop() {
        if (!alive(recording)) {
            return state();
        }
        try {
            Files.createDirectories(recordDir);
            Files.writeString(recordDir.resolve("stop"), "stop");
        } catch (IOException cannotAsk) {
            log.warn("не удалось попросить запись остановиться", cannotAsk);
        }
        long until = System.currentTimeMillis() + STOP_PATIENCE_MS;
        while (alive(recording) && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (alive(recording)) {
            log.warn("Орионометр не отозвался на просьбу — останавливаем силой");
            recording.destroy();
        }
        return state();
    }

    /**
     * Пустить нейросеть играть в оригинал ЧЕРЕЗ Орионометр.
     *
     * <p><b>Моста пока нет, и это сказано прямо.</b> Обучаемая нейросеть
     * ({@code tools/moo3_learn.py}) умеет играть в НАШУ игру по сети, а не в оригинал
     * мышью; связать её с Орионометром — отдельная работа. Пусковой механизм сделан
     * целиком, чтобы потом не переделывать пульт, но пока он честно отвечает, что вести
     * игру некому: притвориться работающим здесь хуже, чем не работать.
     */
    public synchronized OrionometerDto playWithBrain() {
        if (alive(brainRun)) {
            throw new ConflictException("orionometer.brainAlreadyRunning");
        }
        if (!Files.exists(brain)) {
            throw new ConflictException("orionometer.brainMissing");
        }
        throw new ConflictException("orionometer.bridgeMissing");
    }

    /** Что сейчас с записью: состояние она кладёт файлом, читаем его. */
    public OrionometerDto state() {
        Boolean running = alive(recording);
        Double seconds = null;
        Integer clicks = null;
        Integer shots = null;
        String scene = null;
        String logPath = null;
        String dataPath = null;
        Path where = recordDir.resolve("state.json");
        if (Files.exists(where)) {
            try {
                JsonNode said = mapper.readTree(Files.readString(where));
                // Пустые поля в JSON не приходят вовсе (`non_null`), поэтому спрашиваем
                // наличие, а не значение, — иначе разбор падает на отсутствующем поле.
                seconds = said.hasNonNull("seconds") ? said.get("seconds").asDouble() : null;
                clicks = said.hasNonNull("clicks") ? said.get("clicks").asInt() : null;
                shots = said.hasNonNull("shots") ? said.get("shots").asInt() : null;
                scene = said.hasNonNull("scene") ? said.get("scene").asText() : null;
                logPath = said.hasNonNull("log") ? said.get("log").asText() : null;
                dataPath = said.hasNonNull("data") ? said.get("data").asText() : null;
            } catch (IOException unreadable) {
                log.debug("состояние записи не прочиталось", unreadable);
            }
        }
        return new OrionometerDto(running, gameOpen(), seconds, clicks, shots, scene,
                logPath, dataPath, alive(brainRun), failure == null ? died() : failure);
    }

    /**
     * Почему запись кончилась сама, если кончилась плохо.
     *
     * <p>Запуск процесса удаётся почти всегда — и почти ничего не значит: записыватель
     * может умереть первой же строкой (нет питона в пути, закрылось окно игры, не
     * прочитался справочник сцен). Пульт тогда показывал бы «запись остановлена» без
     * единого слова о причине, а молчание читается как поломка самого пульта. Причину
     * записыватель говорит своим выводом, и последняя его строка — это она и есть.
     *
     * <p>Спокойный конец (код 0) причиной не считается: запись просто отработала своё.
     */
    private String died() {
        if (recording == null || recording.isAlive() || recording.exitValue() == 0) {
            return null;
        }
        Path where = recordDir.resolve("recorder.log");
        if (!Files.exists(where)) {
            return "запись оборвалась, код " + recording.exitValue();
        }
        try {
            // Читаем ТЕРПИМО: чужой процесс мог написать что угодно, а строгий разбор
            // уронил бы чтение целиком — и вместо причины остался бы голый код возврата.
            String[] said = new String(Files.readAllBytes(where), StandardCharsets.UTF_8)
                    .split("\\R");
            for (int line = said.length - 1; line >= 0; line--) {
                if (!said[line].isBlank()) {
                    return said[line].strip();
                }
            }
        } catch (IOException unreadable) {
            log.debug("вывод записи не прочитался", unreadable);
        }
        return "запись оборвалась, код " + recording.exitValue();
    }

    private Boolean alive(Process one) {
        return one != null && one.isAlive();
    }
}
