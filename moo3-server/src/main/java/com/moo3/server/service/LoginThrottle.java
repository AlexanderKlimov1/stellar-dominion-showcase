package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.web.error.TooManyAttemptsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Защита от подбора пароля — п. 3.1.
 * <p>
 * <b>Зачем.</b> Пароль проверяется в открытой форме, и выставленный в интернет сервер
 * начинают перебирать в тот же день. BCrypt делает одну попытку дорогой, но не делает
 * дорогими миллион попыток: без счётчика словарь на тысячу паролей проходится за
 * несколько минут. Считаем неудачи и после порога закрываем вход на время.
 * <p>
 * <b>Счётчиков два, и каждый закрывает свою дыру.</b> По записи — от словаря против
 * одного игрока: подряд идут попытки с одним логином. По адресу — от «распыления»,
 * когда один частый пароль пробуют на сотне разных логинов: счётчик записи такого не
 * видит вовсе, каждая запись получает по одной попытке.
 * <p>
 * <b>Считаются только неверные пароли и неизвестные логины.</b> Отказ «почта не
 * подтверждена» приходит на верный пароль — подбором он не является, и запирать за него
 * значило бы наказывать того, кто пароль как раз знает.
 * <p>
 * <b>Успешный вход обнуляет счётчик записи, но не счётчик адреса.</b> Иначе перебор с
 * одной своей заведённой записью сбрасывал бы себе лимит каждые несколько попыток:
 * подобрал — вошёл к себе — считай сначала.
 * <p>
 * <b>Запирание записи — это и способ мешать хозяину войти.</b> Ничего лучше на пароле
 * без второго фактора не придумано, поэтому замок недолгий (минуты, {@code
 * moo3.auth.login-lock-minutes}): перебору он стоит порядка порядков, хозяину — одного
 * ожидания. Постоянной блокировки записи здесь нет намеренно.
 * <p>
 * <b>Счётчики живут в памяти.</b> Перезапуск сервера их забывает, а второй экземпляр
 * считает свои: перебор через перезапуск сервера — не та угроза, ради которой стоит
 * писать в базу на каждую неудачу. Число ключей ограничено ({@link #MAX_KEYS}): ключ
 * приходит из запроса, и без предела перебор логинов сам стал бы атакой на память.
 */
@Service
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    /**
     * Сколько ключей держим в памяти. Ключ задаёт тот, кто стучится, поэтому предел
     * обязателен: миллион выдуманных логинов иначе съел бы кучу без единого верного
     * пароля. При переполнении выбрасываются самые старые — они же и самые безобидные.
     */
    private static final int MAX_KEYS = 20_000;

    /** Что известно об одном ключе: сколько неудач подряд и до каких пор он заперт. */
    private static final class Attempts {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile Instant lastFailure = Instant.now();
        private volatile Instant lockedUntil = Instant.EPOCH;
    }

    private final AuthProperties properties;
    private final Map<String, Attempts> byAccount = new ConcurrentHashMap<>();
    private final Map<String, Attempts> byAddress = new ConcurrentHashMap<>();

    public LoginThrottle(AuthProperties properties) {
        this.properties = properties;
    }

    /**
     * Пускать ли к проверке пароля. Бросает 429, если попытки на время закрыты.
     * <p>
     * Спрашивается <b>до</b> проверки пароля: смысл замка в том, чтобы дорогой хеш и сам
     * ответ «верно/неверно» перестали быть доступны, пока идёт перебор.
     *
     * @param login   что назвали логином — может быть и выдумкой
     * @param address адрес, с которого пришёл запрос
     */
    public void requireAllowed(String login, String address) {
        Instant now = Instant.now();
        requireNotLocked(byAccount.get(key(login)), now, "auth.throttle.account");
        requireNotLocked(byAddress.get(address), now, "auth.throttle.address");
    }

    /**
     * Пароль не подошёл — п. 3.1. Считает неудачу обоим счётчикам и, если порог перейдён,
     * запирает вход на {@code moo3.auth.login-lock-minutes}.
     */
    public void failed(String login, String address) {
        Instant now = Instant.now();
        Integer locked = count(byAccount, key(login), now, properties.loginAttempts());
        Integer lockedByAddress = count(byAddress, address, now, properties.loginIpAttempts());
        if (locked > 0) {
            log.warn("Вход в запись {} закрыт на {} мин.: {} неудачных попыток подряд",
                    key(login), properties.loginLockMinutes(), locked);
        }
        if (lockedByAddress > 0) {
            log.warn("Вход с адреса {} закрыт на {} мин.: {} неудачных попыток подряд",
                    address, properties.loginLockMinutes(), lockedByAddress);
        }
    }

    /**
     * Вход удался: счётчик записи обнуляется. Счётчик адреса намеренно остаётся — см.
     * замечание о сбросе лимита своей же записью в описании класса.
     */
    public void succeeded(String login) {
        byAccount.remove(key(login));
    }

    /**
     * Считает неудачу и возвращает число попыток, если ключ пришлось запереть, — иначе 0.
     * <p>
     * Окно скользящее: неудача старше {@code moo3.auth.login-window-minutes} к перебору
     * отношения не имеет, и счётчик с неё начинается заново. Иначе один опечатавшийся за
     * месяц игрок однажды упёрся бы в замок на ровном месте.
     */
    private Integer count(Map<String, Attempts> counters, String key, Instant now, Integer limit) {
        purge(counters, now);
        Attempts attempts = counters.computeIfAbsent(key, ignored -> new Attempts());
        synchronized (attempts) {
            if (attempts.lastFailure.isBefore(now.minus(window()))) {
                attempts.failures.set(0);
            }
            attempts.lastFailure = now;
            Integer failures = attempts.failures.incrementAndGet();
            if (failures >= limit) {
                attempts.lockedUntil = now.plus(lock());
                // Счётчик не обнуляем: пока замок висит, попытки продолжают приходить, и
                // каждая должна продлевать его, а не пробивать по одной штуке за раз.
                return failures;
            }
            return 0;
        }
    }

    /**
     * Бросает 429, если по этому счётчику вход сейчас заперт.
     *
     * @param reason ключ причины: у записи и у адреса они разные, а срок ожидания к
     *               обеим приписывается подстановкой с вложенным ключом
     */
    private void requireNotLocked(Attempts attempts, Instant now, String reason) {
        if (attempts == null || attempts.lockedUntil.isBefore(now)) {
            return;
        }
        Duration wait = Duration.between(now, attempts.lockedUntil);
        throw new TooManyAttemptsException(wait, reason, minutesOrSeconds(wait));
    }

    /**
     * «через 4 мин» или «через 40 с» — в минутах говорить о полуминуте бессмысленно.
     * Само слово подставит словарь на языке игрока — п. 3.5.
     */
    private MessageKey minutesOrSeconds(Duration wait) {
        long seconds = Math.max(1, wait.toSeconds());
        return seconds >= 60
                ? new MessageKey("time.minutes", (seconds + 59) / 60)
                : new MessageKey("time.seconds", seconds);
    }

    /**
     * Убирает отжившие ключи и держит карту в пределах {@link #MAX_KEYS}.
     * <p>
     * Отдельного планировщика ради этого не заводим: карта растёт только на неудачных
     * попытках, там же её и подчищаем.
     */
    private void purge(Map<String, Attempts> counters, Instant now) {
        Instant stale = now.minus(window()).minus(lock());
        counters.entrySet().removeIf(entry -> entry.getValue().lastFailure.isBefore(stale)
                && entry.getValue().lockedUntil.isBefore(now));
        if (counters.size() <= MAX_KEYS) {
            return;
        }
        List<String> oldest = counters.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastFailure))
                .limit(counters.size() - MAX_KEYS)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(counters::remove);
    }

    /** Логин в ключе приводится к нижнему регистру: запись от регистра не зависит. */
    private String key(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    private Duration window() {
        return Duration.ofMinutes(properties.loginWindowMinutes());
    }

    private Duration lock() {
        return Duration.ofMinutes(properties.loginLockMinutes());
    }
}
