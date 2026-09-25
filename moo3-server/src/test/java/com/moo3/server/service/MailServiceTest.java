package com.moo3.server.service;

import com.moo3.server.config.AuthProperties;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import org.springframework.context.i18n.LocaleContextHolder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Письмо со ссылкой подтверждения — п. 3.1.
 * <p>
 * Проверяется само письмо, а не факт вызова: кириллица в теме и теле, обе части (текст и
 * HTML) и живая ссылка внутри. Ошибка здесь не падает — письмо просто приходит игроку
 * нечитаемым или без ссылки, и увидеть это можно только глазами в почтовом ящике.
 */
class MailServiceTest {

    private final AuthProperties properties = new AuthProperties(
            "admin", "admin.txt", 30, 48, "moo3@example.test",
            "http://localhost:5173", "mail-outbox", 5, 20, 15, 5, 10, Boolean.TRUE);

    /** Словарь и язык запроса: письмо уходит на языке игрока (п. 3.5), здесь — русском. */
    private final Messages messages = Messages.standalone();

    @org.junit.jupiter.api.BeforeEach
    void russianRequest() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
    }

    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** Отправитель, который никуда не ходит: письмо остаётся у теста в руках. */
    private static class CapturingSender extends JavaMailSenderImpl {
        private final List<MimeMessage> sent = new ArrayList<>();

        @Override
        public void send(MimeMessage... messages) {
            sent.addAll(List.of(messages));
        }
    }

    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject() {
                return sender;
            }

            @Override
            public JavaMailSender getObject(Object... args) {
                return sender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return sender;
            }

            @Override
            public JavaMailSender getIfUnique() {
                return sender;
            }
        };
    }

    @Test
    @DisplayName("Письмо уходит на SMTP: кириллица в теме, обе части и ссылка внутри")
    void sendsRealLetter() throws Exception {
        CapturingSender sender = new CapturingSender();
        MailService mail = new MailService(provider(sender), properties, messages, "smtp.example.test", "");
        String link = "http://localhost:5173/?confirm=abc123";

        assertTrue(mail.sendsForReal());
        mail.sendConfirmation("igrok@example.test", "Игрок", link);

        assertEquals(1, sender.sent.size(), "письмо должно уйти одно");
        MimeMessage message = sender.sent.get(0);
        assertEquals("Stellar Dominion: подтверждение регистрации", message.getSubject(),
                "тема приходит в UTF-8, а не вопросительными знаками");
        assertEquals("igrok@example.test", message.getAllRecipients()[0].toString());
        assertEquals("moo3@example.test", message.getFrom()[0].toString());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        String raw = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(raw.contains("text/plain"), "текстовая часть");
        assertTrue(raw.contains("text/html"), "часть с разметкой: в ней ссылка нажимается");
        assertTrue(raw.contains("UTF-8"), "кодировка объявлена в самом письме");

        /*
          Части письма разбираются, а не ищутся в сыром виде: кириллицу почта передаёт
          закодированной (base64 или quoted-printable), и ссылка в исходном тексте
          письма буквально не встречается. Игрок читает разобранное письмо — его и
          проверяем.
        */
        List<String> parts = new ArrayList<>();
        collect(message.getContent(), parts);
        assertEquals(2, parts.size(), "письмо из двух частей: текст и разметка");
        assertTrue(parts.stream().allMatch(part -> part.contains(link)),
                "ссылка подтверждения должна быть в обеих частях: " + parts);
        assertTrue(parts.stream().allMatch(part -> part.contains("Игрок")),
                "кириллица должна доезжать читаемой");
    }

    @Test
    @DisplayName("Английский игрок получает письмо по-английски")
    void englishLetterForEnglishRequest() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        CapturingSender sender = new CapturingSender();
        MailService mail = new MailService(provider(sender), properties, messages, "smtp.example.test", "");

        mail.sendConfirmation("player@example.test", "Player", "http://localhost:5173/?confirm=abc123");

        assertEquals("Stellar Dominion: confirm your registration", sender.sent.get(0).getSubject(),
                "язык письма — язык запроса регистрации, а не сервера");
    }

    /** Разбирает письмо на части: текст и разметка лежат в multipart. */
    private void collect(Object content, List<String> parts) throws Exception {
        if (content instanceof String text) {
            parts.add(text);
            return;
        }
        if (content instanceof jakarta.mail.Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i).getContent(), parts);
            }
        }
    }

    @Test
    @DisplayName("Письмо уходит от ящика SMTP, а не от настройки")
    void senderIsTheSmtpMailbox() throws Exception {
        CapturingSender sender = new CapturingSender();
        MailService mail = new MailService(
                provider(sender), properties, messages, "smtp.gmail.com", "igrok@gmail.com");

        assertEquals("igrok@gmail.com", mail.sender(),
                "почтовые службы разрешают писать только от того адреса, которым вошли");

        mail.sendConfirmation("drug@example.test", "Игрок", "http://localhost/?confirm=x");
        assertEquals("igrok@gmail.com", sender.sent.get(0).getFrom()[0].toString());
    }

    @Test
    @DisplayName("Без имени пользователя отправитель берётся из настройки")
    void senderFallsBackToSettings() {
        MailService mail = new MailService(
                provider(new CapturingSender()), properties, messages, "relay.local", "");

        assertEquals("moo3@example.test", mail.sender(),
                "открытый релей внутри сети имени пользователя не спрашивает");
    }

    @Test
    @DisplayName("Сбой отправки — отказ, а не тишина")
    void deliveryFailureIsLoud() {
        JavaMailSenderImpl broken = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage... messages) {
                throw new MailSendException("почтовый сервер отверг письмо");
            }
        };
        MailService mail = new MailService(provider(broken), properties, messages, "smtp.example.test", "");

        assertThrows(MailService.MailDeliveryException.class,
                () -> mail.sendConfirmation("igrok@example.test", "Игрок", "http://localhost/?confirm=x"),
                "молчаливый сбой оставил бы игрока ждать письма, которого не будет");
    }

    @Test
    @DisplayName("Без почтового сервера письмо не пропадает, а ложится в исходящие")
    void withoutSmtpLetterGoesToOutbox(@org.junit.jupiter.api.io.TempDir Path outbox) {
        AuthProperties local = new AuthProperties("admin", "admin.txt", 30, 48,
                "moo3@example.test", "http://localhost:5173", outbox.toString(), 5, 20, 15, 5, 10, Boolean.TRUE);
        MailService mail = new MailService(provider(new JavaMailSenderImpl()), local, messages, "", "");

        assertEquals(Boolean.FALSE, mail.sendsForReal());
        mail.sendConfirmation("igrok@example.test", "Игрок", "http://localhost:5173/?confirm=abc123");

        assertTrue(outbox.toFile().listFiles() != null && outbox.toFile().listFiles().length == 1,
                "письмо должно лечь файлом в папку исходящих");
    }
}
