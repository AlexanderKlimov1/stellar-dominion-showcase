package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import com.moo3.server.domain.entity.history.AccountEntity;
import com.moo3.server.domain.enums.AccountRole;
import com.moo3.server.repository.history.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * Администратор игры — п. 3.1: заводится сам при первом запуске сервера.
 * <p>
 * Регистрацию он не проходит и почту не подтверждает: иначе в игру нельзя было бы войти
 * вообще, пока не настроен почтовый сервер. Пароль случайный и в базе лежит хешем, а в
 * открытом виде он попадает ровно в один файл ({@code moo3.auth.admin-file}) — прочитать
 * его больше негде, восстановить нельзя.
 * <p>
 * <b>Пароль пишется один раз.</b> Пока запись администратора в базе есть, файл не
 * трогается и новый пароль не выдаётся: иначе каждый перезапуск сервера менял бы пароль
 * под ногами у того, кто им уже пользуется. Забыли пароль — удалите запись
 * администратора, и следующий запуск заведёт её заново.
 */
@Service
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    /**
     * Длина случайного пароля в знаках.
     * <p>
     * Двадцать знаков из набора ниже — это около 118 бит: подобрать нельзя, а перенести
     * руками в окно входа ещё можно. Двусмысленных знаков в наборе нет (ни {@code O} и
     * {@code 0}, ни {@code l} и {@code 1}): пароль читают с экрана и набирают руками.
     */
    private static final int PASSWORD_LENGTH = 20;
    private static final String ALPHABET =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final AccountRepository accountRepository;
    private final AuthProperties properties;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    public AdminAccountInitializer(AccountRepository accountRepository, AuthProperties properties) {
        this.accountRepository = accountRepository;
        this.properties = properties;
    }

    @Override
    // Транзакция постоянного источника: администратор — запись, а не часть партии.
    @Transactional("historyTransactionManager")
    public void run(ApplicationArguments args) {
        String login = properties.adminLogin();
        if (accountRepository.findByLoginIgnoreCase(login).isPresent()) {
            log.debug("Администратор {} уже заведён, пароль остаётся прежним", login);
            return;
        }

        String password = password();
        AccountEntity admin = new AccountEntity();
        admin.setLogin(login);
        // Единственная запись, у которой логин не почта: почты у администратора нет
        // вовсе — он заведён сервером и регистрацию не проходит (п. 3.1).
        admin.setName(login);
        admin.setPasswordHash(passwords.encode(password));
        admin.setRole(AccountRole.ADMIN);
        // Почты у администратора нет, и подтверждать ему нечего: он заведён сервером.
        admin.setConfirmed(Boolean.TRUE);
        admin.setCreatedAt(OffsetDateTime.now());
        accountRepository.save(admin);

        write(login, password);
    }

    /** Кладёт логин и пароль в файл рядом с игрой: другого места узнать пароль нет. */
    private void write(String login, String password) {
        Path file = Path.of(properties.adminFile()).toAbsolutePath();
        String text = """
                Учётная запись администратора Stellar Dominion 3 — п. 3.1.

                логин:  %s
                пароль: %s

                Пароль случайный, выдан при первом запуске сервера и записан только сюда:
                в базе он лежит хешем, восстановить его нельзя. Забыли — удалите запись
                администратора в таблице account, и следующий запуск заведёт её заново.
                Регистрация администратору не нужна: почту он не подтверждает.
                """.formatted(login, password);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
            log.info("Заведён администратор {}; логин и пароль записаны в {}", login, file);
        } catch (IOException failure) {
            // Файл не записался — пароль потерян безвозвратно, и молчать об этом нельзя:
            // отдаём его в журнал, иначе в игру не войти вовсе.
            log.error("Не удалось записать {}: {}. Пароль администратора {}: {}",
                    file, failure.getMessage(), login, password);
        }
    }

    private String password() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
