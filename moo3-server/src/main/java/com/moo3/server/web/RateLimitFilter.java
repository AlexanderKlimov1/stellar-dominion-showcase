package com.moo3.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moo3.server.service.Messages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Предел частоты запросов — по адресу всегда и по игроку сверх того.
 * <p>
 * Партия на сотню игроков живёт на общем сервере, и один сорвавшийся клиент — цикл
 * перезапросов после ошибки, зажатая кнопка «ход» — способен занять собой весь пул
 * соединений к базе. Выставленный в интернет сервер получает то же самое уже нарочно.
 * <p>
 * <b>Считается и адрес, и пропуск, а не одно из двух.</b> Раньше запрос с пропуском
 * получал своё ведро вместо адресного, и предел снимался одной строкой: пропуск в
 * заголовке ничем не проверен, и на каждый запрос можно выдумать новый — сколько
 * выдуманных пропусков, столько и полных вёдер. Теперь ведро адреса тратится всегда, а
 * ведро игрока — сверх него: у адреса запас щедрый (за ним может стоять целая комната
 * и свой же сервер с проверками), у игрока — обычный.
 * <p>
 * <b>Число вёдер ограничено</b> ({@link #MAX_BUCKETS}): ключ приходит из запроса, и без
 * предела те же выдуманные пропуска стали бы атакой на память. Лишние выбрасываются
 * начиная с самых давних — они же и самые безобидные.
 * <p>
 * Предел не спасает от настоящего наводнения: до приложения оно уже дошло, и держать его
 * положено прокси-серверу впереди (nginx {@code limit_req}, Cloudflare). Здесь он держит
 * в границах случайную беду и грубый перебор.
 * <p>
 * Ведро на ключ: {@code burst} запросов про запас, доливается по
 * {@code requests-per-second}. Подписка на события под предел не попадает — это одно
 * долгое соединение, а не поток запросов.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Сколько вёдер держим в памяти: ключей столько, сколько адресов и пропусков. */
    private static final int MAX_BUCKETS = 20_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Messages messages;
    private final Boolean enabled;
    private final Rate perPlayer;
    private final Rate perAddress;

    public RateLimitFilter(ObjectMapper objectMapper,
                           Messages messages,
                           @Value("${moo3.rate-limit.enabled:true}") Boolean enabled,
                           @Value("${moo3.rate-limit.requests-per-second:50}") Integer perSecond,
                           @Value("${moo3.rate-limit.burst:150}") Integer burst,
                           @Value("${moo3.rate-limit.ip-requests-per-second:150}") Integer ipPerSecond,
                           @Value("${moo3.rate-limit.ip-burst:400}") Integer ipBurst) {
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.enabled = enabled;
        this.perPlayer = new Rate(perSecond, burst);
        this.perAddress = new Rate(ipPerSecond, ipBurst);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !Boolean.TRUE.equals(enabled)
                || !path.startsWith("/api/")
                || path.endsWith("/events");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Адрес считается первым и всегда: это единственный ключ, который нельзя выдумать
        // на ходу. Пропуск — сверх него, и только если он вообще прислан.
        Double wait = take("ip:" + request.getRemoteAddr(), perAddress);
        String player = playerKey(request);
        if (wait == null && player != null) {
            wait = take(player, perPlayer);
        }
        if (wait == null) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("Предел частоты запросов: {} {} с адреса {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        refuse(request, response, wait);
    }

    /**
     * Тратит запрос из ведра ключа. Возвращает {@code null}, если запрос проходит, иначе
     * — сколько секунд ждать до следующего.
     */
    private Double take(String key, Rate rate) {
        purge();
        return buckets.computeIfAbsent(key, ignored -> new Bucket()).allow(rate);
    }

    /**
     * Отказ 429 в том же виде, что и остальные ошибки API, плюс {@code Retry-After}.
     * <p>
     * Фильтр стоит до Spring MVC, и языка запроса контекст ещё не знает: заголовок
     * {@code Accept-Language} читается здесь самим фильтром, поддерживаются те же два языка.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response, Double wait)
            throws IOException {
        Locale locale = "ru".equals(request.getLocale().getLanguage())
                ? Locale.forLanguageTag("ru") : Locale.ENGLISH;
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Retry-After — целые секунды: дробь в заголовке не по формату, и клиент её не поймёт.
        response.setHeader(HttpHeaders.RETRY_AFTER,
                String.valueOf(Math.max(1L, (long) Math.ceil(wait))));
        objectMapper.writeValue(response.getWriter(), Map.of(
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "message", messages.get(locale, "error.tooFrequent"),
                "timestamp", OffsetDateTime.now().toString()));
    }

    /**
     * Пропуск игрока, если он прислан, — вторым ключом сверх адреса. Берётся и из
     * заголовка, и из параметра — там же, где его ищет {@link AccessTokenResolver}.
     */
    private String playerKey(HttpServletRequest request) {
        String header = request.getHeader(AccessTokenResolver.HEADER);
        if (header != null && !header.isBlank()) {
            return "token:" + header;
        }
        String param = request.getParameter("accessToken");
        return param == null || param.isBlank() ? null : "token:" + param;
    }

    /**
     * Держит карту вёдер в пределах {@link #MAX_BUCKETS}: полное ведро давно не
     * тронутого ключа ничем не отличается от нового, и терять его не жалко.
     */
    private void purge() {
        if (buckets.size() <= MAX_BUCKETS) {
            return;
        }
        List<String> oldest = buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().updatedAt))
                .limit(buckets.size() - MAX_BUCKETS)
                .map(Map.Entry::getKey)
                .toList();
        oldest.forEach(buckets::remove);
    }

    /** Скорость долива и запас ведра: своя у адреса, своя у игрока. */
    private record Rate(Integer perSecond, Integer burst) {
    }

    /** Ведро с доливом: тратится на запросах, наполняется временем. */
    private static final class Bucket {

        private double tokens = -1;
        private long updatedAt = System.nanoTime();

        /** @return null, если запрос проходит, иначе сколько секунд ждать до следующего */
        synchronized Double allow(Rate rate) {
            long now = System.nanoTime();
            if (tokens < 0) {
                tokens = rate.burst();
            }
            double seconds = (now - updatedAt) / 1_000_000_000.0;
            updatedAt = now;
            tokens = Math.min(rate.burst(), tokens + seconds * rate.perSecond());
            if (tokens < 1) {
                return (1 - tokens) / rate.perSecond();
            }
            tokens -= 1;
            return null;
        }
    }
}
