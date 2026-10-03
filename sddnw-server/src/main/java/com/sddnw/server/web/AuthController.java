package com.sddnw.server.web;

import com.sddnw.server.dto.AccountDto;
import com.sddnw.server.dto.AccountSummaryDto;
import com.sddnw.server.dto.ChallengeDto;
import com.sddnw.server.dto.HotkeysRequest;
import com.sddnw.server.dto.LocaleRequest;
import com.sddnw.server.dto.LoginRequest;
import com.sddnw.server.dto.RegisterRequest;
import com.sddnw.server.dto.RegisterResultDto;
import com.sddnw.server.dto.SessionDto;
import com.sddnw.server.dto.TextScaleRequest;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.RegistrationChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Вход в игру — п. 3.1: регистрация, подтверждение почты, вход и выход.
 * <p>
 * Единственные эндпоинты, которым пропуск учётной записи не нужен: без них его негде
 * взять. Всё, что заводит партию, спрашивает его — см. {@code GameController}.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AccountService accountService;
    private final RegistrationChallenge registrationChallenge;

    public AuthController(AccountService accountService,
                          RegistrationChallenge registrationChallenge) {
        this.accountService = accountService;
        this.registrationChallenge = registrationChallenge;
    }

    /**
     * Задачка перед регистрацией — п. 3.1: «докажите, что вы человек».
     * <p>
     * Спрашивается формой регистрации перед отправкой заявки. Ответ на вопрос остаётся у
     * сервера, а вопрос одноразовый: решённая задачка открывает дорогу одной заявке, а не
     * тысяче. Когда задачка выключена настройкой, эндпоинт отвечает пустотой, и форма
     * поля ответа не показывает.
     */
    @GetMapping("/challenge")
    public ChallengeDto challenge() {
        return Boolean.TRUE.equals(registrationChallenge.enabled())
                ? registrationChallenge.issue()
                : null;
    }

    /** Регистрация: запись заводится, ссылка подтверждения уходит на почту — п. 3.1. */
    @PostMapping("/register")
    public RegisterResultDto register(@Valid @RequestBody RegisterRequest request,
                                      HttpServletRequest http) {
        return accountService.register(request, clientAddress(http));
    }

    /**
     * Выслать письмо подтверждения заново — п. 3.1.
     * <p>
     * Спрашивает те же логин и пароль, что и вход: иначе сервер слал бы письма на чужие
     * адреса по чужой просьбе. Старая ссылка после этого не годится — ключ от записи один.
     */
    @PostMapping("/resend")
    public RegisterResultDto resend(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest http) {
        return accountService.resend(request, clientAddress(http));
    }

    /**
     * Подтверждение почты по ссылке из письма — п. 3.1.
     * <p>
     * Ссылка ведёт на клиент ({@code /?confirm=…}), а он уже зовёт этот эндпоинт: в ответ
     * приходит готовый пропуск, и игрок из письма попадает сразу в игру.
     */
    @PostMapping("/confirm")
    public SessionDto confirm(@RequestParam String token) {
        return accountService.confirm(token);
    }

    /**
     * Гостевой вход — backlog-promo, пункт 1: партия без регистрации, одним нажатием.
     * <p>
     * В ответ — обычный пропуск, как после входа: дальше гость идёт тем же путём, что и
     * зарегистрированный, только партия у него одна и её настройки назначает сервер
     * ({@code GuestGameRules}).
     */
    @PostMapping("/guest")
    public SessionDto guest(HttpServletRequest http) {
        return accountService.guest(clientAddress(http));
    }

    /** Вход по логину или почте — п. 3.1. */
    @PostMapping("/login")
    public SessionDto login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return accountService.login(request, clientAddress(http));
    }

    /**
     * Адрес, с которого пришёл запрос, — для счётчика попыток входа
     * ({@code LoginThrottle}).
     * <p>
     * <b>Берётся адрес соединения, а не заголовок {@code X-Forwarded-For}:</b> заголовок
     * подделывается одной строкой, и защита от перебора, доверяющая ему, снимается тем же
     * перебором — каждой попытке свой выдуманный адрес. За обратным прокси адрес
     * подставляет сам Spring, если ему разрешить: {@code server.forward-headers-strategy}
     * в настройках. Тогда заголовку доверяет уже прокси, а не кто угодно из интернета.
     */
    private String clientAddress(HttpServletRequest http) {
        String address = http.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    /**
     * Список учётных записей — п. 3.1: только администратору.
     * <p>
     * Нужен подтверждению чужой регистрации: не увидев списка, застрявшую запись не
     * найти. Пароля и ссылки подтверждения в ответе нет.
     */
    @GetMapping("/accounts")
    public List<AccountSummaryDto> accounts(@AccountToken String accountToken) {
        return accountService.accounts(accountToken);
    }

    /**
     * Подтверждение чужой записи администратором — п. 3.1.
     * <p>
     * Последний ключ от застрявшей регистрации: письмо могло не дойти вовсе, и тогда
     * игрок остаётся с занятой почтой и закрытым входом.
     */
    @PostMapping("/accounts/{accountId}/confirm")
    public AccountSummaryDto confirmAccount(@PathVariable UUID accountId,
                                            @AccountToken String accountToken) {
        return accountService.confirmByAdmin(accountToken, accountId);
    }

    /**
     * Удаление учётной записи администратором — п. 3.1.
     * <p>
     * Брошенные заявки и опечатки в адресе иначе висят в очереди вечно, а занятая почта
     * не освобождается сама. Сыгранное при этом остаётся: запись ни на что в партии не
     * ссылается. Свою запись удалить нельзя — см. {@code AccountService.deleteByAdmin}.
     */
    @DeleteMapping("/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable UUID accountId, @AccountToken String accountToken) {
        accountService.deleteByAdmin(accountToken, accountId);
    }

    /** Выход: пропуск перестаёт действовать сразу. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AccountToken String accountToken) {
        accountService.logout(accountToken);
    }

    /** Кто вошёл — этим же клиент проверяет при запуске, жив ли сохранённый пропуск. */
    @GetMapping("/me")
    public AccountDto me(@AccountToken String accountToken) {
        return accountService.current(accountToken);
    }

    /**
     * Запомнить язык игрока — п. 3.5.
     * <p>
     * Зовётся переключателем языка сразу за переключением: язык меняют одним нажатием, и
     * экрана настроек у игры нет. Неизвестный язык — отказ 400: игра знает два, и запись
     * третьего выглядела бы настройкой, которая ничего не делает.
     */
    @PutMapping("/locale")
    public AccountDto locale(@Valid @RequestBody LocaleRequest request,
                             @AccountToken String accountToken) {
        return accountService.changeLocale(accountToken, request.locale());
    }

    /**
     * Запомнить размер текста интерфейса — п. 11.1.
     * <p>
     * Свой адрес, а не общий «настройки»: настроек ровно две, обе меняются одним нажатием
     * переключателя, и общий адрес заставил бы клиент присылать язык всякий раз, когда
     * игрок трогает размер.
     */
    @PutMapping("/text-scale")
    public AccountDto textScale(@Valid @RequestBody TextScaleRequest request,
                                @AccountToken String accountToken) {
        return accountService.changeTextScale(accountToken, request.textScale());
    }

    /**
     * Запомнить раскладку горячих клавиш карты — п. 11.1.
     * <p>
     * Уходит она ЦЕЛИКОМ, с экрана настроек: раскладку правят разом и сохраняют одним
     * нажатием. Пустая раскладка значит «как было» — колонка очищается, и клиент берёт
     * свою раскладку по умолчанию.
     */
    @PutMapping("/hotkeys")
    public AccountDto hotkeys(@Valid @RequestBody HotkeysRequest request,
                              @AccountToken String accountToken) {
        return accountService.changeHotkeys(accountToken, request.hotkeys());
    }
}
