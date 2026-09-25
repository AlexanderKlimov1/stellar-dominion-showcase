package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Письма игрокам — п. 3.1: пока письмо одно, со ссылкой подтверждения регистрации.
 * <p>
 * <b>Отправка настоящая.</b> Заданный {@code spring.mail.host} значит, что письма уходят
 * на почтовый сервер по SMTP: письмо собирается как MIME (UTF-8, обе части — текст и
 * HTML), поэтому у него не рассыпается кириллица в теме и его одинаково показывают и
 * почтовые клиенты, и веб-почта. Настройки соединения — обычные для Spring:
 * {@code spring.mail.username/password}, STARTTLS и таймауты в
 * {@code spring.mail.properties.*}; пароль в файл настроек класть не надо, он читается из
 * переменной окружения.
 * <p>
 * <b>Сбой отправки — это отказ, а не мелочь.</b> Если сервер настроен, но письмо не ушло
 * (неверный пароль, недоступный узел, отвергнутый адрес), наружу летит ошибка: игрок
 * должен узнать, что ссылки не будет, — молчаливое «письмо отправлено» оставило бы его
 * ждать вечно. Учётная запись при этом остаётся неподтверждённой и регистрируется заново.
 * <p>
 * <b>Без почтового сервера</b> ({@code spring.mail.host} пуст) письмо не пропадает, а
 * ложится файлом в папку исходящих и целиком уходит в журнал: иначе игру нельзя было бы
 * ни завести на своей машине, ни проверить сквозным прогоном.
 * <p>
 * <b>Отправитель — сам почтовый ящик.</b> Если у SMTP задано имя пользователя, письмо
 * уходит от него, а не от {@code moo3.auth.mail-from}. Так требуют почтовые службы:
 * Gmail, Яндекс и Mail.ru либо отвергают письмо с чужим адресом в поле From, либо молча
 * подменяют его своим, — и настроенный «moo3@localhost» означал бы письма, которые не
 * доходят. Настройка остаётся для случая, когда своего ящика у сервера нет вовсе:
 * открытый релей внутри сети имени пользователя не спрашивает.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /**
     * Имя файла в исходящих: до миллисекунд.
     *
     * Секунд не хватало: письмо, высланное заново сразу после регистрации, попадало в тот
     * же файл и затирало первое — а вместе с ним и ссылку, по которой ещё можно было
     * войти.
     */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ObjectProvider<JavaMailSender> mailSender;
    private final AuthProperties properties;
    private final Messages messages;
    private final String smtpHost;
    private final String smtpUser;

    public MailService(ObjectProvider<JavaMailSender> mailSender,
                       AuthProperties properties,
                       Messages messages,
                       @Value("${spring.mail.host:}") String smtpHost,
                       @Value("${spring.mail.username:}") String smtpUser) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.messages = messages;
        this.smtpHost = smtpHost;
        this.smtpUser = smtpUser;
    }

    /**
     * От кого уходит письмо — п. 3.1.
     * <p>
     * Ящик SMTP сильнее настройки: почтовые службы разрешают писать только от того адреса,
     * которым к ним вошли. Настройка остаётся запасным вариантом — для релея без входа.
     */
    public String sender() {
        return smtpUser == null || smtpUser.isBlank() ? properties.mailFrom() : smtpUser;
    }

    /** Настроен ли почтовый сервер: от этого зависит и путь письма, и ответ на регистрацию. */
    public Boolean sendsForReal() {
        return smtpHost != null && !smtpHost.isBlank();
    }

    /**
     * Письмо со ссылкой подтверждения — п. 3.1.
     *
     * @return файл в исходящих, если почтового сервера нет; {@code null} — письмо ушло
     *         по-настоящему. Путь нужен ответу регистрации: без почтового сервера игрок
     *         должен знать не «где-то в исходящих», а <b>какой именно файл</b> открыть,
     *         иначе ссылку он не найдёт и запись останется мёртвой
     * @throws MailDeliveryException почтовый сервер настроен, но письмо не ушло
     */
    public String sendConfirmation(String email, String login, String link) {
        // Язык письма — язык запроса регистрации (п. 3.5): игрок регистрируется с того
        // экрана, где уже выбрал язык, и письмо приходит на нём же.
        String subject = messages.get("mail.confirm.subject");
        String text = messages.get("mail.confirm.text", login, link, properties.confirmHours());

        // HTML-часть — ради самой ссылки: в тексте её приходится копировать руками.
        // Разметка нарочно скупая: почтовые клиенты режут стили, а письмо должно
        // читаться и без них.
        String html = messages.get("mail.confirm.html", escape(login), link, properties.confirmHours());

        return send(email, subject, text, html);
    }

    private String send(String to, String subject, String text, String html) {
        if (!Boolean.TRUE.equals(sendsForReal())) {
            return outbox(to, subject, text);
        }

        try {
            JavaMailSender sender = mailSender.getObject();
            MimeMessage message = sender.createMimeMessage();
            // true — письмо из двух частей: текстовой и HTML; кодировка задаётся явно,
            // иначе тема и подпись приезжают вопросительными знаками.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(sender());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            sender.send(message);
            log.info("Письмо со ссылкой подтверждения отправлено на {} через {}", to, smtpHost);
            return null;
        } catch (MailException | jakarta.mail.MessagingException failure) {
            // Копия остаётся в исходящих: письмо не ушло, но ссылка не потеряна —
            // администратор может передать её игроку, не заводя запись заново.
            outbox(to, subject, text);
            log.error("Письмо на {} через {} не ушло: {}", to, smtpHost, failure.getMessage());
            throw new MailDeliveryException(
                    "Письмо не удалось отправить: " + failure.getMessage(), failure);
        }
    }

    /**
     * Кладёт письмо файлом в исходящие и целиком пишет в журнал.
     *
     * @return путь к файлу; {@code null} — папка недоступна, и письмо осталось только
     *         в журнале
     */
    private String outbox(String to, String subject, String text) {
        log.info("Письмо для {} оставлено в исходящих:\n{}", to, text);
        try {
            Path dir = Path.of(properties.mailOutboxDir());
            Files.createDirectories(dir);
            Path file = dir.resolve(OffsetDateTime.now().format(FILE_STAMP) + "-"
                    + to.replaceAll("[^A-Za-z0-9._@-]", "_") + ".txt");
            Files.writeString(file, "Кому: " + to + "\nТема: " + subject + "\n\n" + text,
                    StandardCharsets.UTF_8);
            log.info("Письмо сохранено: {}", file.toAbsolutePath());
            return file.toAbsolutePath().normalize().toString();
        } catch (IOException failure) {
            // Письмо уже в журнале, и ссылку оттуда взять можно: падать из-за папки нечего.
            log.warn("Не удалось сохранить письмо в исходящих: {}", failure.getMessage());
            return null;
        }
    }

    /** Логин игрока попадает в HTML письма, а значит, разметкой быть не должен. */
    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Почтовый сервер настроен, но письмо не ушло — п. 3.1.
     * <p>
     * Отдельный тип, а не общий конфликт: обработчик ошибок отвечает на него 502 —
     * виноват не запрос игрока, а внешняя служба, и повторять регистрацию с теми же
     * данными имеет смысл.
     */
    public static class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
