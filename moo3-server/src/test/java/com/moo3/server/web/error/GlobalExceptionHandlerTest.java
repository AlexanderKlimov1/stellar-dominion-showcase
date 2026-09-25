package com.moo3.server.web.error;

import com.moo3.server.service.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ошибка клиента не должна выглядеть как поломка сервера: общий обработчик
 * {@code Exception} превращал в 500 всё подряд — и запрос чужим методом, и чужой
 * Content-Type, и несуществующий путь. Здесь проверяется, что каждому такому случаю
 * достаётся свой код ответа.
 */
class GlobalExceptionHandlerTest {

    private static final String PATH = "/probe/00000000-0000-0000-0000-000000000000";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Messages.standalone());
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(handler)
                .build();
    }

    /**
     * Текст ошибки — на языке заголовка Accept-Language (п. 3.5): без заголовка английский,
     * с {@code ru} — русский. Проверяется на отказе сервиса с подстановкой: у него и ключ,
     * и аргумент, и оба должны дойти до текста.
     */
    @Test
    @DisplayName("Текст ошибки идёт на языке запроса: английский по умолчанию, русский по заголовку")
    void messageFollowsAcceptLanguage() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The game has already started"));
        mockMvc.perform(get("/probe/conflict").header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
                .andExpect(jsonPath("$.message").value("Игра уже стартовала"));
        mockMvc.perform(get("/probe/not-found").header(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Игра не найдена: 42"));
        mockMvc.perform(get("/probe/not-found").header(HttpHeaders.ACCEPT_LANGUAGE, "de"))
                .andExpect(jsonPath("$.message").value("Game not found: 42"));
    }

    @Test
    @DisplayName("GET на путь, объявленный только под POST, — 405 со списком методов")
    void methodNotSupported() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    @DisplayName("Тело не в JSON — 415, а не 500")
    void mediaTypeNotSupported() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content("привет"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("Вместо UUID произвольная строка — 400, разбор идёт до контроллера")
    void typeMismatch() throws Exception {
        mockMvc.perform(post("/probe/не-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("Сломанный JSON в теле — 400")
    void unreadableBody() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Несуществующий путь через MockMvc не воспроизвести: в отдельно поднятом
     * DispatcherServlet нет ни раздачи статики, ни настоящей карты путей. Поэтому оба
     * исключения отдаём обработчику напрямую — проверяется именно перевод их в 404.
     */
    @Test
    @DisplayName("Неизвестный путь — 404 обоими исключениями Spring")
    void unknownPath() {
        assertThat(handler.handleUnknownPath(new NoResourceFoundException(HttpMethod.GET, "/api/nope"))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(handler.handleUnknownPath(
                        new NoHandlerFoundException("GET", "/api/nope", new HttpHeaders()))
                .getStatusCode().value()).isEqualTo(404);
    }

    /** Путь только под POST — на нём и проверяется, что GET получает 405, а не 500. */
    @RestController
    static class ProbeController {

        @PostMapping("/probe/{id}")
        String probe(@PathVariable UUID id, @RequestBody Payload payload) {
            return "ok";
        }

        @GetMapping("/probe/conflict")
        String conflict() {
            throw new ConflictException("game.alreadyStarted");
        }

        @GetMapping("/probe/not-found")
        String notFound() {
            throw new NotFoundException("game.notFound", 42);
        }
    }

    record Payload(String name) {
    }
}
