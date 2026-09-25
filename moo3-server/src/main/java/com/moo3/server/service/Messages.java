package com.moo3.server.service;

import com.moo3.server.web.error.ApiException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Тексты сервера на языке игрока — п. 3.5 (локализация).
 * <p>
 * Язык по умолчанию — английский; русский игрок выбирает в клиенте, и клиент шлёт его
 * заголовком {@code Accept-Language} с каждым запросом. Словари лежат в
 * {@code messages.properties} (английский, он же запасной для любого другого языка) и
 * {@code messages_ru.properties}; ключ, которого нет ни в одном, отдаётся как есть — так
 * пропущенный перевод виден на экране, а не прячется за исключением.
 * <p>
 * <b>Подстановки переводятся тоже:</b> перечисление — по ключу {@code enum.<Тип>.<ИМЯ>}
 * (нет ключа — его {@code name()}), {@link MessageKey} и {@link ApiException} — своим
 * ключом. Так «Встреча уже закрыта: {0}» получает состояние встречи на языке игрока, а не
 * ярлык из кода.
 * <p>
 * Вне запроса (фазы конца хода, фоновые прогоны, юнит-тесты) языка у контекста нет, и
 * берётся английский: {@link LocaleContextHolder#setDefaultLocale} ставится здесь, при
 * создании словаря, — иначе брался бы язык операционной системы, и тот же код на русской
 * машине отвечал бы по-русски, а на английской по-английски. Тексты, которые рождаются
 * внутри хода и показываются потом (отчёт хода), сюда не идут: их хранят ключом и
 * переводят при показе.
 */
@Service
public class Messages {

    private final MessageSource source;

    public Messages(MessageSource source) {
        this.source = source;
        LocaleContextHolder.setDefaultLocale(Locale.ENGLISH);
    }

    /**
     * Словарь для юнит-тестов — тот же, что поднимает Spring Boot, но без контекста:
     * {@code messages*.properties} с classpath, английский запасным, апострофы и
     * фигурные скобки — по правилам {@code MessageFormat} всегда.
     */
    public static Messages standalone() {
        ResourceBundleMessageSource bundle = new ResourceBundleMessageSource();
        bundle.setBasename("messages");
        bundle.setDefaultEncoding("UTF-8");
        bundle.setFallbackToSystemLocale(false);
        bundle.setAlwaysUseMessageFormat(true);
        return new Messages(bundle);
    }

    /** Текст на языке текущего запроса. */
    public String get(String key, Object... args) {
        return get(LocaleContextHolder.getLocale(), key, args);
    }

    /** Текст на заданном языке — для мест, где язык известен не из контекста (фильтры). */
    public String get(Locale locale, String key, Object... args) {
        Object[] resolved = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            resolved[i] = resolve(locale, args[i]);
        }
        return source.getMessage(key, resolved, key, locale);
    }

    /**
     * Ярлык перечисления на языке запроса — ключ {@code enum.<Тип>.<ИМЯ>}.
     * <p>
     * Ярлык в самом перечислении при этом остаётся: его читают тексты отчёта хода, которые
     * переводятся своим этапом (они рождаются внутри хода, а показываются потом). Всё, что
     * собирается на запрос, берёт ярлык отсюда.
     */
    public String label(Enum<?> value) {
        return (String) resolve(LocaleContextHolder.getLocale(), value);
    }

    /** Отказ API на языке текущего запроса. */
    public String get(ApiException exception) {
        return get(exception.key(), exception.args());
    }

    /**
     * Текст события хода — п. 3.5: ключ и подстановки прочитаны из базы, где типов нет.
     * <p>
     * Подстановка переводится, <b>только если она и есть ключ словаря</b>: так ярлык
     * перечисления ({@code enum.DiplomacyTreaty.TRADE}) становится словом, а название
     * планеты, имя игрока и число проходят как есть. Проверка идёт по самому словарю, а не
     * по виду строки: угадывать «похоже на ключ» значило бы однажды перевести чьё-то имя.
     */
    public String event(String key, List<String> args) {
        Locale locale = LocaleContextHolder.getLocale();
        Object[] resolved = new Object[args == null ? 0 : args.size()];
        for (int i = 0; i < resolved.length; i++) {
            String arg = args.get(i);
            String known = arg == null ? null : source.getMessage(arg, null, null, locale);
            resolved[i] = known == null ? arg : known;
        }
        return source.getMessage(key, resolved, key, locale);
    }

    /**
     * Сообщение, записанное одной колонкой (ключ первой строкой) — итог боя и встречи.
     * <p>
     * Пусто отдаётся пустым: у встречи, которая ещё не кончилась, итога нет вовсе.
     */
    public String text(List<String> packed) {
        if (packed == null || packed.isEmpty()) {
            return null;
        }
        return event(packed.get(0), packed.subList(1, packed.size()));
    }

    /** Подстановка: ключ, отказ и перечисление переводятся, остальное отдаётся как есть. */
    private Object resolve(Locale locale, Object arg) {
        if (arg instanceof MessageKey nested) {
            return get(locale, nested.key(), nested.args());
        }
        if (arg instanceof ApiException nested) {
            return get(locale, nested.key(), nested.args());
        }
        if (arg instanceof Throwable failure) {
            return failure.getMessage();
        }
        if (arg instanceof Enum<?> value) {
            String key = "enum." + value.getDeclaringClass().getSimpleName() + "." + value.name();
            return source.getMessage(key, null, value.name(), locale);
        }
        return arg;
    }
}
