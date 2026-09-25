package com.moo3.server.web.error;

import com.moo3.server.dto.ApiErrorDto;
import com.moo3.server.service.MailService;
import com.moo3.server.service.Messages;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Ответы об ошибках — п. 3.5 (локализация): текст игроку уходит на языке запроса.
 * <p>
 * Отказы API ({@link ApiException}) несут ключ и подстановки, а не текст; переводит их
 * здесь {@link Messages} по {@code Accept-Language}. Остальные сообщения этого класса —
 * тоже ключи: у ошибки, которую видит игрок, нет языка, пока её не показали.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Messages messages;

    public GlobalExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorDto> handleNotFound(NotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, messages.get(exception), List.of());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiErrorDto> handleConflict(ConflictException exception) {
        return build(HttpStatus.CONFLICT, messages.get(exception), List.of());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiErrorDto> handleForbidden(ForbiddenException exception) {
        return build(HttpStatus.FORBIDDEN, messages.get(exception), List.of());
    }

    /** Заявка неверна по сути, а не по форме: 400 — чинить запрос, а не сервер. */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiErrorDto> handleBadRequest(BadRequestException exception) {
        return build(HttpStatus.BAD_REQUEST, messages.get(exception), List.of());
    }

    /**
     * Подбор пароля остановлен — п. 3.1.
     * <p>
     * 429, а не 403: отказ временный и относится не к паролю, а к числу попыток. Вместе
     * с ним уходит {@code Retry-After} в секундах — по нему клиент (и всякий вежливый
     * робот) знает, когда пробовать снова, не разбирая текста сообщения.
     */
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ApiErrorDto> handleTooManyAttempts(TooManyAttemptsException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, exception.retryAfter().toSeconds())));
        return build(HttpStatus.TOO_MANY_REQUESTS, headers, messages.get(exception), List.of());
    }

    /**
     * Почтовый сервер настроен, но письмо не ушло — п. 3.1.
     * <p>
     * 502, а не 500: запрос игрока в порядке, подвела внешняя служба. Разница не
     * формальная — по ней видно, что повторять регистрацию с теми же данными имеет
     * смысл, а искать ошибку надо в настройках почты, а не в игре.
     */
    @ExceptionHandler(MailService.MailDeliveryException.class)
    public ResponseEntity<ApiErrorDto> handleMailFailure(MailService.MailDeliveryException exception) {
        log.error("Письмо не отправлено: {}", exception.getMessage());
        return build(HttpStatus.BAD_GATEWAY, messages.get("error.mail"), List.of());
    }

    /**
     * Двое изменили одно и то же одновременно — п. 11.1.
     * <p>
     * Это не поломка сервера, а обычная жизнь партии на нескольких игроков: чья-то запись
     * оказалась первой. Клиенту говорим прямо, чтобы он обновил состояние и повторил, —
     * молча терять чужую правку, как раньше, нельзя.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorDto> handleConcurrentChange(OptimisticLockingFailureException exception) {
        log.warn("Состояние изменилось под рукой: {}", exception.getMessage());
        return build(HttpStatus.CONFLICT, messages.get("error.concurrentChange"), List.of());
    }

    /**
     * Тело запроса не прошло проверки.
     * <p>
     * Пишется в журнал: отклонённый запрос не оставлял в нём следа вовсе, и «партия не
     * создаётся» приходилось воспроизводить наугад — по логам сервера было видно только,
     * что запроса как будто и не было.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorDto> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        log.warn("Запрос не прошёл валидацию: {}", details);
        return build(HttpStatus.BAD_REQUEST, messages.get("error.validation"), details);
    }

    /** Обязательный параметр запроса не передан — это ошибка клиента, а не сервера. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorDto> handleMissingParameter(MissingServletRequestParameterException exception) {
        return build(HttpStatus.BAD_REQUEST,
                messages.get("error.missingParameter", exception.getParameterName()), List.of());
    }

    /** Ограничения на параметрах метода контроллера — @NotBlank и подобные. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorDto> handleConstraintViolation(ConstraintViolationException exception) {
        List<String> details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
        log.warn("Запрос не прошёл валидацию: {}", details);
        return build(HttpStatus.BAD_REQUEST, messages.get("error.validation"), details);
    }

    /**
     * Подписчик закрыл вкладку — п. 11.1. Для потока событий это обычный конец жизни
     * соединения, а не поломка: отвечать уже некому, и в журнале ошибок такому не место.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientGone(AsyncRequestNotUsableException exception) {
        log.debug("Подписчик отключился: {}", exception.getMessage());
    }

    /**
     * Путь такой есть, а метод у него другой — GET там, где объявлен только POST.
     * <p>
     * Ошибся вызывающий, и раньше он получал в ответ «внутреннюю ошибку сервера» и искал
     * поломку не в своём коде. Список настоящих методов уходит заголовком {@code Allow}:
     * этого требует HTTP для 405, и по нему сразу видно, как звать эндпоинт правильно.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorDto> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        log.debug("Метод не поддерживается: {}", exception.getMessage());
        HttpHeaders headers = new HttpHeaders();
        // Список по контракту Spring может быть null — когда путь не поддерживает ничего.
        Set<HttpMethod> supported = exception.getSupportedHttpMethods();
        if (supported != null) {
            headers.setAllow(supported);
        }
        return build(HttpStatus.METHOD_NOT_ALLOWED, headers,
                messages.get("error.methodNotAllowed", exception.getMethod()), List.of());
    }

    /**
     * Тело пришло в чужом формате: API разговаривает только JSON. Разговор о заголовке
     * {@code Content-Type} — ошибка клиента, а не поломка; что сервер принимает,
     * перечисляем заголовком {@code Accept}, чтобы не пришлось гадать.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorDto> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        log.debug("Формат тела не поддерживается: {}", exception.getMessage());
        HttpHeaders headers = new HttpHeaders();
        List<MediaType> supported = exception.getSupportedMediaTypes();
        if (!supported.isEmpty()) {
            headers.setAccept(supported);
        }
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, headers,
                messages.get("error.mediaType"), List.of());
    }

    /**
     * Такого пути на сервере нет. Spring говорит об этом двумя разными исключениями —
     * {@link NoHandlerFoundException}, когда не нашлось контроллера, и
     * {@link NoResourceFoundException}, когда запрос дошёл до раздачи статики, — а для
     * клиента это одно и то же: он ошибся адресом, и ответ должен быть 404, не 500.
     * Сам путь пишем в журнал: в теле ответа ему делать нечего.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorDto> handleUnknownPath(Exception exception) {
        log.debug("Неизвестный путь: {}", exception.getMessage());
        return build(HttpStatus.NOT_FOUND, messages.get("error.unknownPath"), List.of());
    }

    /**
     * Значение в пути или параметре не того типа — чаще всего вместо идентификатора партии
     * пришла строка, не разбирающаяся в UUID. Разбор идёт до входа в контроллер, поэтому
     * своей проверкой ({@code GameAccess}) такое не поймать, — отвечаем 400 отсюда.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorDto> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.debug("Значение не того типа: {}", exception.getMessage());
        return build(HttpStatus.BAD_REQUEST,
                messages.get("error.typeMismatch", exception.getName()), List.of());
    }

    /**
     * Тело запроса не разобралось: сломанный JSON или не тот тип поля. Подробности разбора
     * — про внутренности классов, наружу их не отдаём, а в журнал кладём: по ним видно,
     * на каком поле споткнулся клиент.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorDto> handleUnreadableBody(HttpMessageNotReadableException exception) {
        log.debug("Тело запроса не разобрано: {}", exception.getMessage());
        return build(HttpStatus.BAD_REQUEST, messages.get("error.unreadableBody"), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorDto> handleUnexpected(Exception exception) {
        log.error("Необработанная ошибка", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, messages.get("error.internal"), List.of());
    }

    private ResponseEntity<ApiErrorDto> build(HttpStatus status, String message, List<String> details) {
        return build(status, new HttpHeaders(), message, details);
    }

    private ResponseEntity<ApiErrorDto> build(HttpStatus status, HttpHeaders headers,
                                              String message, List<String> details) {
        ApiErrorDto body = new ApiErrorDto(
                status.value(),
                status.getReasonPhrase(),
                message,
                details.isEmpty() ? null : details,
                OffsetDateTime.now());
        return ResponseEntity.status(status).headers(headers).body(body);
    }
}
