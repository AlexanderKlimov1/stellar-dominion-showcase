package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.domain.entity.history.AccountEntity;
import com.moo3.server.domain.entity.history.AccountSessionEntity;
import com.moo3.server.domain.enums.AccountRole;
import com.moo3.server.dto.AccountDto;
import com.moo3.server.dto.AccountSummaryDto;
import com.moo3.server.dto.LoginRequest;
import com.moo3.server.dto.RegisterRequest;
import com.moo3.server.dto.RegisterResultDto;
import com.moo3.server.dto.SessionDto;
import com.moo3.server.repository.history.AccountRepository;
import com.moo3.server.repository.history.AccountSessionRepository;
import com.moo3.server.web.error.BadRequestException;
import com.moo3.server.web.error.ConflictException;
import com.moo3.server.web.error.ForbiddenException;
import com.moo3.server.web.error.NotFoundException;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Вход в игру: регистрация, подтверждение почты, сеансы — п. 3.1.
 * <p>
 * <b>Учётная запись и игрок партии — разное.</b> Запись живёт между партиями и знает
 * только логин, почту и пароль; всё игровое — раса, колонии, флот — принадлежит игроку
 * внутри своей партии. Поэтому запись ни на что в партии не ссылается: удалённая запись
 * не должна уносить с собой сыгранное.
 * <p>
 * <b>Пароль хранится хешем</b> (BCrypt): в базе, в журналах и в ответах сервера открытого
 * пароля нет нигде. Проверка нарочно не говорит, что именно не сошлось — логин или
 * пароль: иначе подбор начинался бы с перебора логинов. Сам подбор останавливает
 * {@link LoginThrottle}: неудачи считаются по записи и по адресу, и после порога вход
 * закрывается на несколько минут.
 * <p>
 * <b>Регистрация подтверждается почтой:</b> запись заводится сразу, но до перехода по
 * ссылке из письма вход в неё закрыт. Ссылка одноразовая и с сроком; исключение одно —
 * администратор, он заводится сервером и почты не подтверждает (см. {@code AdminAccount}).
 * Не дошло письмо — его высылают заново ({@link #resend}) или запись подтверждает
 * администратор ({@link #confirmByAdmin}): застрявшая регистрация не должна занимать
 * почту навсегда.
 * <p>
 * <b>Логин — это почта.</b> Отдельного логина у записи нет: игрок называет себя тем же
 * адресом, на который пришло письмо, — одно имя вместо двух. Имя записи служит только
 * показу в интерфейсе игры и уникальным быть не обязано. Исключение опять же одно —
 * администратор: у него почты нет, и логин у него свой.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** Короче этого пароль не принимается: восемь знаков — общая нижняя граница. */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository accountRepository;
    private final AccountSessionRepository sessionRepository;
    private final MailService mailService;
    private final AuthProperties properties;
    private final LoginThrottle throttle;
    private final RegistrationThrottle registrations;
    private final RegistrationChallenge challenge;
    private final Messages messages;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accountRepository,
                          AccountSessionRepository sessionRepository,
                          MailService mailService,
                          AuthProperties properties,
                          LoginThrottle throttle,
                          RegistrationThrottle registrations,
                          RegistrationChallenge challenge,
                          Messages messages) {
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.mailService = mailService;
        this.properties = properties;
        this.throttle = throttle;
        this.registrations = registrations;
        this.challenge = challenge;
        this.messages = messages;
    }

    /**
     * Регистрация — п. 3.1: запись заводится, письмо со ссылкой уходит, вход пока закрыт.
     * <p>
     * <b>Не ушло письмо — не будет и записи.</b> Отправка идёт внутри той же транзакции,
     * и её отказ откатывает всё: иначе логин остался бы занят записью, в которую никто
     * никогда не войдёт, — ни ссылки, ни способа её переслать. Игрок повторяет
     * регистрацию с теми же логином и почтой.
     *
     * <b>Регистраций с одного адреса — счётное число</b> ({@link RegistrationThrottle}):
     * каждая шлёт письмо на чужую почту, и без предела сервер оказывается рассылкой
     * чужими руками. Считаются заведённые записи, а не попытки: отказ письма не шлёт.
     *
     * @param clientAddress адрес, с которого пришёл запрос, — по нему и считаем
     * @return что сказать игроку: письмо ушло на почту или лежит в исходящих сервера
     */
    // Транзакция ПОСТОЯННОГО источника, а не игры: учётные записи живут месяцами, как и
    // журнал прогонов, и к партиям отношения не имеют вовсе. Без имени менеджера Spring
    // взял бы главный, игровой, — и в режиме балансового прогона регистрация ложилась бы
    // в память вместе с партией, а admin.txt переставал бы подходить.
    @Transactional("historyTransactionManager")
    public RegisterResultDto register(RegisterRequest request, String clientAddress) {
        registrations.requireAllowed(clientAddress);
        // Задачка тратится здесь, до всякой работы: она одноразовая, и её проверка должна
        // случиться и тогда, когда заявка отвалится дальше по любой другой причине.
        challenge.require(request.challengeId(), request.challengeAnswer());
        // Логин и почта — одно и то же: игроку хватает одного имени, и оно же есть адрес,
        // куда придёт письмо. Приводится к нижнему регистру, чтобы «Игрок@Почта» и
        // «игрок@почта» не оказались двумя разными записями.
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String login = email;
        String name = request.name().trim();
        requirePassword(request.password());

        if (accountRepository.findByLoginIgnoreCase(login).isPresent()
                || accountRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ConflictException("auth.emailTaken");
        }

        AccountEntity account = new AccountEntity();
        account.setLogin(login);
        account.setEmail(email);
        account.setName(name);
        account.setPasswordHash(passwords.encode(request.password()));
        account.setRole(AccountRole.PLAYER);
        account.setConfirmed(Boolean.FALSE);
        account.setCreatedAt(OffsetDateTime.now());
        // Язык запроса — это язык, на котором игрок только что читал экран регистрации, и
        // запоминать его отдельной просьбой незачем (п. 3.5). Сев за другую машину, он
        // получит тот же язык, а не английский заново.
        account.setLocale(LocaleContextHolder.getLocale().getLanguage());
        // Ссылку и срок ставит sendConfirmation: у регистрации и повторной отправки они
        // обязаны быть одинаковыми.
        accountRepository.save(account);

        // Письмо отправляется до записи в журнал: сорвавшаяся отправка откатит и запись,
        // и строка «заведена учётная запись» оказалась бы неправдой.
        RegisterResultDto result = sendConfirmation(account, Boolean.FALSE);
        // Отмечаем после отправки: не ушло письмо — не было и регистрации, запись
        // откатится вместе с ним, и тратить на неё попытку адреса не за что.
        registrations.registered(clientAddress);
        log.info("Заведена учётная запись {}, ждёт подтверждения почты", login);
        return result;
    }

    /**
     * Выслать письмо подтверждения заново — п. 3.1.
     * <p>
     * Без этого регистрация оказывалась ловушкой: письмо не дошло (почтовый сервер не
     * настроен, адрес прочитан не тем, ссылка просрочена) — логин занят, вход закрыт, и
     * сделать с этим нельзя ничего. Здесь запись получает <b>новую ссылку с новым
     * сроком</b>, а старая перестаёт годиться: двух ключей от одной записи быть не должно.
     * <p>
     * <b>Пароль спрашивается тот же, что и при входе.</b> Иначе сервер по чужой просьбе
     * слал бы письма на любой зарегистрированный адрес — а это и есть рассылка чужими
     * руками. Ответ поэтому и не скрывает, есть ли такая запись: пароль к ней у того, кто
     * спрашивает, уже есть.
     */
    @Transactional("historyTransactionManager")
    public RegisterResultDto resend(LoginRequest request, String clientAddress) {
        // Пароль спрашивается тот же, значит и подбирать его можно здесь же: счётчик
        // попыток общий с входом, иначе перебор просто переехал бы на этот эндпоинт.
        String name = request.login().trim();
        throttle.requireAllowed(name, clientAddress);
        AccountEntity account = findByLoginOrEmail(name)
                .orElseThrow(() -> {
                    throttle.failed(name, clientAddress);
                    return new ForbiddenException("auth.badCredentials");
                });
        if (!passwords.matches(request.password(), account.getPasswordHash())) {
            throttle.failed(name, clientAddress);
            throw new ForbiddenException("auth.badCredentials");
        }
        throttle.succeeded(name);
        if (Boolean.TRUE.equals(account.getConfirmed())) {
            throw new ConflictException("auth.alreadyConfirmed");
        }

        RegisterResultDto result = sendConfirmation(account, Boolean.TRUE);
        log.info("Письмо подтверждения выслано заново для {}", account.getLogin());
        return result;
    }

    /**
     * Заводит новую ссылку подтверждения и отправляет её письмом.
     * <p>
     * Одно место на регистрацию и на повторную отправку: срок ссылки, её вид и ответ
     * игроку должны быть одинаковыми, откуда бы письмо ни пошло.
     *
     * @param again письмо высылается заново: тогда игроку говорится и о том, что прежняя
     *              ссылка больше не годится, — иначе он открыл бы старое письмо и получил
     *              отказ, не поняв почему
     * @return что сказать игроку: письмо ушло на почту или лежит в исходящих сервера
     */
    private RegisterResultDto sendConfirmation(AccountEntity account, Boolean again) {
        account.setConfirmToken(token());
        account.setConfirmExpiresAt(OffsetDateTime.now().plusHours(properties.confirmHours()));
        accountRepository.save(account);

        String link = properties.clientUrl() + "/?confirm=" + account.getConfirmToken();
        String outbox = mailService.sendConfirmation(account.getEmail(), account.getLogin(), link);

        String notice = Boolean.TRUE.equals(mailService.sendsForReal())
                ? messages.get("auth.notice.sent", account.getEmail())
                : outbox == null
                        ? messages.get("auth.notice.outboxLog")
                        : messages.get("auth.notice.outbox", outbox);
        return new RegisterResultDto(account.getLogin(), account.getEmail(),
                mailService.sendsForReal(),
                Boolean.TRUE.equals(again) ? messages.get("auth.notice.resent", notice) : notice);
    }

    /** Запись по логину или почте: вход и повторная отправка ищут её одинаково. */
    private java.util.Optional<AccountEntity> findByLoginOrEmail(String name) {
        return accountRepository.findByLoginIgnoreCase(name)
                .or(() -> accountRepository.findByEmailIgnoreCase(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Подтверждение почты по ссылке из письма — п. 3.1.
     * <p>
     * Ссылка одноразовая: после перехода она стирается, иначе письмо оставалось бы
     * вечным ключом от записи. Сразу же выдаётся пропуск — игрок пришёл из письма и
     * второй раз вводить пароль ради того же самого не должен.
     */
    @Transactional("historyTransactionManager")
    public SessionDto confirm(String confirmToken) {
        AccountEntity account = accountRepository.findByConfirmToken(confirmToken)
                .orElseThrow(() -> new ForbiddenException("auth.linkInvalid"));
        if (account.getConfirmExpiresAt() != null
                && account.getConfirmExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ForbiddenException("auth.linkExpired");
        }

        account.setConfirmed(Boolean.TRUE);
        account.setConfirmToken(null);
        account.setConfirmExpiresAt(null);
        accountRepository.save(account);
        log.info("Учётная запись {} подтверждена", account.getLogin());
        return open(account);
    }

    /**
     * Вход по логину или почте — п. 3.1.
     * <p>
     * <b>Попытки считаются</b> ({@link LoginThrottle}): выставленный в интернет сервер
     * перебирают словарём, и одного медленного хеша против этого мало. Счётчик
     * спрашивается до проверки пароля — заперев вход, мы отнимаем у перебора и сам ответ
     * «верно или нет», и стоимость хеша.
     * <p>
     * <b>Неудачей считается только неверный пароль или неизвестный логин.</b> Отказ
     * «почта не подтверждена» приходит на верный пароль: подбором он не является, и
     * запирать за него значило бы мешать тому, кто пароль как раз знает.
     *
     * @param clientAddress адрес, с которого пришёл запрос: по нему ловится перебор,
     *                      идущий сразу по многим записям
     */
    @Transactional("historyTransactionManager")
    public SessionDto login(LoginRequest request, String clientAddress) {
        String name = request.login().trim();
        throttle.requireAllowed(name, clientAddress);
        // Не уточняем, что не сошлось: иначе подбор начинался бы с перебора логинов.
        AccountEntity account = findByLoginOrEmail(name)
                .orElseThrow(() -> {
                    throttle.failed(name, clientAddress);
                    return new ForbiddenException("auth.badCredentials");
                });

        if (!passwords.matches(request.password(), account.getPasswordHash())) {
            throttle.failed(name, clientAddress);
            throw new ForbiddenException("auth.badCredentials");
        }
        throttle.succeeded(name);
        if (!Boolean.TRUE.equals(account.getConfirmed())) {
            // Названо прямо, что делать: письмо могло не дойти, и без подсказки игрок
            // остаётся с занятым логином и без ссылки.
            throw new ForbiddenException("auth.notConfirmed");
        }

        // Просроченные пропуска убираются здесь: вход — единственный момент, когда их
        // становится больше, и отдельный планировщик ради этого заводить незачем.
        sessionRepository.deleteExpired(OffsetDateTime.now());
        return open(account);
    }

    /** Выход: пропуск перестаёт действовать сразу, а не по сроку. */
    @Transactional("historyTransactionManager")
    public void logout(String sessionToken) {
        sessionRepository.deleteById(sessionToken);
    }

    /**
     * Кто пришёл с этим пропуском — п. 3.1; это же проверка «игрок авторизован».
     * <p>
     * Спрашивается перед входом в партию: создать игру, присоединиться к чужой и
     * загрузить сохранение может только тот, кто назвал себя.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountEntity require(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new ForbiddenException("auth.sessionRequired");
        }
        AccountSessionEntity session = sessionRepository.findById(sessionToken)
                .orElseThrow(() -> new ForbiddenException("auth.sessionUnknown"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ForbiddenException("auth.sessionExpired");
        }
        return accountRepository.findById(session.getAccountId())
                .orElseThrow(() -> new ForbiddenException("auth.accountDeleted"));
    }

    /**
     * Пришедший с этим пропуском — администратор; иначе отказ — п. 3.1.
     * <p>
     * Спрашивается там, где действие касается не своей партии, а хозяйства сервера:
     * сохранения лежат общей кучей и хозяина не имеют вовсе, поэтому удалить чужое
     * сохранение может только тот, кто отвечает за сервер. Прежде удаление не спрашивало
     * ничего, и снести чужую партию мог кто угодно.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountEntity requireAdmin(String sessionToken) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() != AccountRole.ADMIN) {
            throw new ForbiddenException("auth.adminOnly");
        }
        return account;
    }

    /** Учётная запись пропуска для клиента — им же проверяется, жив ли сеанс. */
    /** Языки игры — п. 3.5: тот же список, что у {@code WebConfig.localeResolver}. */
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "ru");

    /**
     * Ступени размера текста — п. 11.1: проценты, те же, что у переключателя на клиенте
     * ({@code TEXT_SCALES} в {@code ui/textScale.ts}).
     * <p>
     * Список закрытый, а не «от 100 до 200»: масштаб правит не только кегль, но и высоту
     * панелей, и проверены ступени поштучно — на каждой смотрели, что разметка не рвётся.
     */
    private static final Set<Integer> SUPPORTED_TEXT_SCALES = Set.of(100, 115, 130, 150);

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountDto current(String sessionToken) {
        return toDto(require(sessionToken));
    }

    public AccountDto toDto(AccountEntity account) {
        return new AccountDto(account.getId(), account.getLogin(), account.getEmail(),
                account.getName(), account.getRole().name(),
                messages.get(account.getRole().labelKey()), account.getLocale(),
                account.getTextScale());
    }

    /**
     * Запоминает язык игрока — п. 3.5.
     * <p>
     * Зовётся переключателем языка, а не настройками: язык меняют одним нажатием, и
     * отдельного экрана у него нет. Неизвестный язык не пишется вовсе: игра знает два, и
     * запись «de» значила бы для следующего входа английский с видом настроенного.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeLocale(String sessionToken, String locale) {
        AccountEntity account = require(sessionToken);
        if (!SUPPORTED_LOCALES.contains(locale)) {
            throw new BadRequestException("auth.localeUnknown", locale);
        }
        account.setLocale(locale);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает размер текста интерфейса — п. 11.1.
     * <p>
     * Живёт рядом с языком и по той же причине: настройка из {@code localStorage} теряется
     * на второй машине, а размер текста игрок выбирает один раз и навсегда. Неизвестная
     * ступень — отказ 400: список закрыт, и запись «180» выглядела бы настройкой, которой
     * интерфейс не подчиняется.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeTextScale(String sessionToken, Integer textScale) {
        AccountEntity account = require(sessionToken);
        if (!SUPPORTED_TEXT_SCALES.contains(textScale)) {
            throw new BadRequestException("auth.textScaleUnknown", String.valueOf(textScale));
        }
        account.setTextScale(textScale);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Все учётные записи — п. 3.1: их видит только администратор.
     * <p>
     * Нужен списку записей: подтвердить чужую регистрацию нельзя, не увидев, какая из них
     * застряла. Ни пароля, ни ссылки подтверждения наружу не уходит — только то, что
     * видно в списке.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<AccountSummaryDto> accounts(String sessionToken) {
        requireAdmin(sessionToken);
        return accountRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(account -> new AccountSummaryDto(
                        account.getId(),
                        account.getLogin(),
                        account.getEmail(),
                        account.getName(),
                        account.getRole().name(),
                        messages.get(account.getRole().labelKey()),
                        account.getConfirmed(),
                        account.getCreatedAt()))
                .toList();
    }

    /**
     * Подтверждение записи администратором — п. 3.1.
     * <p>
     * Последний ключ от застрявшей регистрации: письмо могло не дойти вовсе (почтовый
     * сервер не настроен, адрес закрыт, письмо съел спам-фильтр), и тогда игрок остаётся
     * с занятой почтой и закрытым входом. Администратор отвечает за сервер — ему и
     * решать, впускать ли.
     * <p>
     * Ссылка при этом гасится: подтверждать второй раз нечего, а живой ключ от чужой
     * записи оставлять незачем.
     */
    @Transactional("historyTransactionManager")
    public AccountSummaryDto confirmByAdmin(String sessionToken, UUID accountId) {
        requireAdmin(sessionToken);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("auth.accountNotFound", accountId));
        if (Boolean.TRUE.equals(account.getConfirmed())) {
            throw new ConflictException("auth.accountAlreadyConfirmed", account.getLogin());
        }

        account.setConfirmed(Boolean.TRUE);
        account.setConfirmToken(null);
        account.setConfirmExpiresAt(null);
        accountRepository.save(account);
        log.info("Администратор подтвердил учётную запись {}", account.getLogin());

        return new AccountSummaryDto(account.getId(), account.getLogin(), account.getEmail(),
                account.getName(), account.getRole().name(), messages.get(account.getRole().labelKey()),
                account.getConfirmed(), account.getCreatedAt());
    }

    /**
     * Удаление учётной записи администратором — п. 3.1.
     * <p>
     * <b>Зачем.</b> Заявок накапливается больше, чем игроков: брошенные регистрации,
     * опечатки в адресе, следы прогонов. Подтверждать их незачем, а висеть в очереди они
     * будут вечно — занятая почта не освобождается сама.
     * <p>
     * <b>Сыгранное при этом не пропадает.</b> Запись ни на что в партии не ссылается:
     * раса, колонии и флот принадлежат игроку внутри партии, а не человеку между
     * партиями. Поэтому удаление записи не трогает ни партий, ни сохранений — оно
     * освобождает почту и убирает строку из списка.
     * <p>
     * <b>Себя удалить нельзя.</b> Администратор в игре один, и удаливший себя запирает
     * сервер: подтверждать чужие регистрации станет некому, а новая запись администратора
     * заводится только при следующем запуске и с новым паролем.
     * <p>
     * Сеансы удалённой записи гасятся здесь же: пропуск живёт в своей таблице и пережил бы
     * запись, оставшись действующим ключом от того, чего уже нет.
     */
    @Transactional("historyTransactionManager")
    public void deleteByAdmin(String sessionToken, UUID accountId) {
        AccountEntity admin = requireAdmin(sessionToken);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("auth.accountNotFound", accountId));
        if (admin.getId().equals(account.getId())) {
            throw new ConflictException("auth.selfDelete");
        }

        sessionRepository.deleteByAccountId(account.getId());
        accountRepository.delete(account);
        log.info("Администратор удалил учётную запись {}", account.getLogin());
    }

    /** Заводит сеанс: новый пропуск со сроком из настроек. */
    private SessionDto open(AccountEntity account) {
        AccountSessionEntity session = new AccountSessionEntity();
        session.setToken(token());
        session.setAccountId(account.getId());
        session.setCreatedAt(OffsetDateTime.now());
        session.setExpiresAt(OffsetDateTime.now().plusDays(properties.sessionDays()));
        sessionRepository.save(session);
        return new SessionDto(session.getToken(), session.getExpiresAt(), toDto(account));
    }

    private void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ConflictException("auth.passwordShort", MIN_PASSWORD_LENGTH);
        }
    }

    /**
     * Случайная строка для пропуска и ссылки подтверждения.
     * <p>
     * 32 байта из {@link SecureRandom} в виде base64 без набивки: угадать такую нельзя, а
     * в адрес ссылки она помещается без экранирования.
     */
    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
