package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.web.error.TooManyAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Предел регистраций с одного адреса — п. 3.1.
 * <p>
 * <b>Зачем отдельно от общего предела частоты.</b> Регистрация стоит нам письма на чужой
 * адрес: заведя её тысячу раз, злоумышленник рассылает тысячу писем от нашего имени — и
 * платит за это репутацией нашего почтового ящика, а не своей. Общий предел частоты
 * ({@code RateLimitFilter}) считает запросы в секунду и такого не ловит вовсе: сорок
 * регистраций в минуту для него — тишина.
 * <p>
 * <b>Считаются только заведённые записи.</b> Отказы (почта занята, короткий пароль, нет
 * имени) письма не шлют и в счёт не идут: иначе один опечатавшийся игрок закрывал бы вход
 * себе и соседям по адресу, а сквозной прогон, который нарочно проверяет все отказы, не
 * проходил бы дважды подряд. Поток бессмысленных попыток и без того упирается в общий
 * предел частоты.
 * <p>
 * <b>Окно скользящее</b> ({@code moo3.auth.registrations-per-hour} за час): считается не
 * «сколько было в этом часу», а «сколько за последний час», иначе на границе часов подряд
 * проходили бы две полные порции.
 * <p>
 * Счётчики живут в памяти, как и у {@link LoginThrottle}, и по той же причине: писать в
 * базу на каждую регистрацию ради предела, который переживает перезапуск сервера, здесь
 * незачем. Число ключей ограничено — ключом служит адрес запроса.
 */
@Service
public class RegistrationThrottle {

    private static final Logger log = LoggerFactory.getLogger(RegistrationThrottle.class);

    /** Сколько адресов держим в памяти: ключ приходит из запроса, предел обязателен. */
    private static final int MAX_KEYS = 20_000;

    private static final Duration WINDOW = Duration.ofHours(1);

    private final AuthProperties properties;
    /** Отметки времени заведённых записей по адресам — по одной на регистрацию. */
    private final Map<String, Deque<Instant>> byAddress = new ConcurrentHashMap<>();

    public RegistrationThrottle(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * Пускать ли к регистрации. Бросает 429, если с этого адреса за последний час уже
     * завели столько записей, сколько разрешено.
     * <p>
     * Спрашивается <b>до</b> заведения записи: смысл предела в том, чтобы письмо не ушло.
     */
    public void requireAllowed(String address) {
        Instant now = Instant.now();
        Deque<Instant> done = byAddress.get(address);
        if (done == null) {
            return;
        }
        synchronized (done) {
            forget(done, now);
            if (done.size() < properties.registrationsPerHour()) {
                return;
            }
            Duration wait = Duration.between(now, done.peekFirst().plus(WINDOW));
            log.warn("Регистрация с адреса {} закрыта: {} записей за последний час",
                    address, done.size());
            throw new TooManyAttemptsException(wait, "auth.throttle.registrations");
        }
    }

    /** Запись заведена и письмо ушло — отмечаем, что адрес потратил одну попытку. */
    public void registered(String address) {
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
