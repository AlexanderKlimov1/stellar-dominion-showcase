package com.sddnw.server.service;

import com.sddnw.server.web.error.TooManyAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Предел «столько-то записей с одного адреса за последний час» — общая часть
 * {@link RegistrationThrottle} и {@link GuestThrottle}.
 * <p>
 * Окно скользящее: считается не «сколько было в этом часу», а «сколько за последний час»,
 * иначе на границе часов подряд проходили бы две полные порции. Счётчики живут в памяти —
 * писать в базу ради предела, переживающего перезапуск сервера, незачем, — а число ключей
 * ограничено: ключом служит адрес запроса, и без предела карта росла бы от чужих запросов.
 */
public abstract class HourlyAddressLimit {

    private static final Logger log = LoggerFactory.getLogger(HourlyAddressLimit.class);

    /** Сколько адресов держим в памяти: ключ приходит из запроса, предел обязателен. */
    private static final int MAX_KEYS = 20_000;

    private static final Duration WINDOW = Duration.ofHours(1);

    /** Отметки времени заведённых записей по адресам — по одной на запись. */
    private final Map<String, Deque<Instant>> byAddress = new ConcurrentHashMap<>();

    /** Сколько записей разрешено с одного адреса за час. */
    protected abstract Integer limit();

    /** Ключ отказа в словаре сервера: игрок читает, что именно закрыто. */
    protected abstract String refusalKey();

    /**
     * Пускать ли адрес. Бросает 429, если с него за последний час уже завели столько
     * записей, сколько разрешено. Спрашивается <b>до</b> заведения записи.
     */
    public void requireAllowed(String address) {
        Instant now = Instant.now();
        Deque<Instant> done = byAddress.get(address);
        if (done == null) {
            return;
        }
        synchronized (done) {
            forget(done, now);
            if (done.size() < limit()) {
                return;
            }
            Duration wait = Duration.between(now, done.peekFirst().plus(WINDOW));
            log.warn("{}: адрес {} закрыт, {} записей за последний час",
                    getClass().getSimpleName(), address, done.size());
            throw new TooManyAttemptsException(wait, refusalKey());
        }
    }

    /** Запись заведена — отмечаем, что адрес потратил одну попытку. */
    protected void spend(String address) {
        Instant now = Instant.now();
        purge(now);
        Deque<Instant> done = byAddress.computeIfAbsent(address, ignored -> new ArrayDeque<>());
        synchronized (done) {
            forget(done, now);
            done.addLast(now);
        }
    }

    /** Убирает отметки старше окна: они к «за последний час» уже не относятся. */
    private void forget(Deque<Instant> done, Instant now) {
        Instant edge = now.minus(WINDOW);
        while (!done.isEmpty() && done.peekFirst().isBefore(edge)) {
            done.removeFirst();
        }
    }

    /** Держит карту в пределах {@link #MAX_KEYS}, выбрасывая самые давние адреса. */
    private void purge(Instant now) {
        byAddress.values().removeIf(done -> {
            synchronized (done) {
                forget(done, now);
                return done.isEmpty();
            }
        });
        if (byAddress.size() <= MAX_KEYS) {
            return;
        }
        List<String> oldest = byAddress.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().peekFirst()))
                .limit(byAddress.size() - MAX_KEYS)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(byAddress::remove);
    }
}
