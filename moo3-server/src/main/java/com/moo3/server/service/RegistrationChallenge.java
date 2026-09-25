package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.dto.ChallengeDto;
import com.moo3.server.web.error.BadRequestException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Задачка перед регистрацией — п. 3.1: «докажите, что вы человек».
 * <p>
 * <b>Зачем.</b> Предел регистраций с адреса ({@link RegistrationThrottle}) считает адреса,
 * а у наплыва их бывает тысяча. Задачка добавляет к каждой регистрации шаг, который
 * простой рассыльщик не делает вовсе: он умеет слать заявку, а не ходить за вопросом и
 * отвечать на него.
 * <p>
 * <b>Чего она не делает, и это честно сказать сразу.</b> Написанный под нашу игру бот
 * решит её так же легко, как человек: вопросов немного, слова свои, разбор — десяток
 * строк. От целенаправленной атаки защищает не она, а подтверждение почты, пределы
 * частоты и регистраций. Задачка снимает бессмысленный фоновый шум, и на большее
 * рассчитывать не надо — внешняя капча (hCaptcha, reCAPTCHA) в проект не заводится
 * намеренно: это чужая служба, чужие ключи и передача данных игрока третьей стороне.
 * <p>
 * <b>Устройство.</b> Сервер выдаёт вопрос и его номер, ответ держит у себя. Числа названы
 * словами, а не цифрами: разбор «2+3» пишется в одну строку, а слова хотя бы требуют
 * таблицы. Ответ засчитывается <b>один раз</b> — иначе одна решённая задачка открывала бы
 * дорогу тысяче заявок. Срок жизни короткий: незакрытая форма регистрации не должна
 * копить у сервера вопросы неделями.
 */
@Service
public class RegistrationChallenge {

    /** Сколько живёт вопрос: столько хватит на заполнение формы и не больше. */
    private static final Duration LIFETIME = Duration.ofMinutes(15);

    /** Предел на число незакрытых вопросов: их заказывает кто угодно, без пропуска. */
    private static final int MAX_OPEN = 20_000;

    /**
     * Числа словами — от нуля до десяти: цифрами задачка разбирается одной строкой,
     * словами — таблицей. Сами слова лежат в словаре ({@code challenge.word.N}), на языке
     * игрока — п. 3.5; ответ принимается словом любого из поддерживаемых языков, потому
     * что игрок отвечает на то, что видел, а видел он вопрос на своём.
     */
    private static final int WORDS = 11;
    private static final List<Locale> LANGUAGES = List.of(Locale.ENGLISH, Locale.forLanguageTag("ru"));

    /** Вид задачки: сложение и вычитание, оба словами и с ответом числом. */
    private enum Kind { SUM, DIFFERENCE }

    private record Open(Integer answer, Instant expiresAt) {
    }

    private final AuthProperties properties;
    private final Messages messages;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Open> open = new ConcurrentHashMap<>();

    public RegistrationChallenge(AuthProperties properties, Messages messages) {
        this.properties = properties;
        this.messages = messages;
    }

    /** Задачка выключена настройкой: домашней игре в локальной сети она ни к чему. */
    public Boolean enabled() {
        return properties.registrationChallenge();
    }

    /**
     * Новый вопрос. Номер вопроса случайный и ничего о нём не говорит: ответ остаётся у
     * сервера, иначе задачка решалась бы разбором собственного номера.
     */
    public ChallengeDto issue() {
        purge();
        Kind kind = Kind.values()[random.nextInt(Kind.values().length)];
        // Задачу составляем так, чтобы ответ остался в тех же числах, что и вопрос: от
        // нуля до десяти. Вычитание — без отрицательного ответа, сложение — без выхода
        // за десять: игрок решает задачу для человека, а не проверку знака и разрядов.
        int first = 2 + random.nextInt(WORDS - 3);
        int second = kind == Kind.SUM
                ? 1 + random.nextInt(WORDS - 1 - first)
                : 1 + random.nextInt(first - 1);
        int answer = kind == Kind.SUM ? first + second : first - second;
        String question = messages.get(kind == Kind.SUM ? "challenge.sum" : "challenge.difference",
                new MessageKey(word(first)), new MessageKey(word(second)));

        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        open.put(id, new Open(answer, Instant.now().plus(LIFETIME)));
        return new ChallengeDto(id, question, messages.get("challenge.hint"));
    }

    /**
     * Проверяет ответ и <b>тратит</b> вопрос: он одноразовый.
     * <p>
     * Ответ принимается и числом, и словом: игрок пишет то, что видит в вопросе, и спорить
     * с ним из-за формы записи не за что.
     */
    public void require(String challengeId, String answer) {
        if (!Boolean.TRUE.equals(enabled())) {
            return;
        }
        if (challengeId == null || challengeId.isBlank() || answer == null || answer.isBlank()) {
            throw new BadRequestException("challenge.required");
        }
        Open expected = open.remove(challengeId);
        if (expected == null || expected.expiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("challenge.stale");
        }
        if (!expected.answer().equals(parse(answer))) {
            throw new BadRequestException("challenge.wrong");
        }
    }

    /** Ответ числом («7») или словом («семь», «seven»); всё прочее — не ответ. */
    private Integer parse(String answer) {
        String cleaned = answer.trim().toLowerCase(Locale.ROOT);
        for (Locale language : LANGUAGES) {
            for (int i = 0; i < WORDS; i++) {
                if (messages.get(language, word(i)).toLowerCase(Locale.ROOT).equals(cleaned)) {
                    return i;
                }
            }
        }
        try {
            return Integer.valueOf(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String word(int number) {
        return "challenge.word." + number;
    }

    /**
     * Убирает просроченные вопросы и держит карту в пределах {@link #MAX_OPEN}: вопрос
     * заказывается без пропуска, и без предела их заказали бы миллион.
     */
    private void purge() {
        Instant now = Instant.now();
        open.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (open.size() <= MAX_OPEN) {
            return;
        }
        List<String> oldest = open.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                .limit(open.size() - MAX_OPEN)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(open::remove);
    }
}
