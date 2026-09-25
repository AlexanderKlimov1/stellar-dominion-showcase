package com.moo3.server.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * Текст справочника на двух языках — п. 3.5.
 * <p>
 * В файле справочника поле пишется либо одной строкой, либо парой:
 * <pre>
 *   "name": "Frigate"
 *   "name": { "en": "Automated Factory", "ru": "Автоматический завод" }
 * </pre>
 * Одна строка значит «на обоих языках одинаково» — так остаются написанными коды, числа и
 * названия, которые не переводят (имена звёзд, обозначения корпусов). Поэтому справочник
 * переводится ПО ЗАПИСИ: непереведённая запись читается по-прежнему и ничего не ломает.
 * <p>
 * <b>Язык выбирается при обращении, а не при чтении файла.</b> Справочник разбирается один
 * раз и лежит в памяти общий для всех запросов — разбери его на языке первого читателя, и
 * этот язык достался бы всем. Поэтому {@link #text()} смотрит на язык текущего запроса
 * ({@code LocaleContextHolder}), а вне запроса — на язык по умолчанию, английский, который
 * ставит {@link com.moo3.server.service.Messages}.
 * <p>
 * <b>Опознаватель берётся из английского.</b> Код технологии выводится из её названия и
 * лежит в базе у каждого изученного уровня, а названия зданий и компонентов сверяются со
 * ссылками внутри самих справочников. Всё это читает {@link #en()} напрямую: перевод не
 * вправе менять то, чем вещь опознаётся.
 */
public record LocalizedText(String en, String ru) {

    /**
     * Разбор поля справочника: строка или пара {@code {en, ru}}.
     * <p>
     * Отсутствующий перевод — не ошибка: запись просто ещё не переведена, и на обоих языках
     * покажется английское.
     */
    @JsonCreator
    public static LocalizedText of(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            String en = node.path("en").asText(null);
            String ru = node.path("ru").asText(null);
            // Пары без английского не бывает: именно он опознаватель и он же запасной.
            return new LocalizedText(en == null ? ru : en, ru);
        }
        return of(node.asText());
    }

    /** Текст, одинаковый на обоих языках: коды, обозначения, имена собственные. */
    public static LocalizedText of(String text) {
        return text == null ? null : new LocalizedText(text, text);
    }

    /**
     * Текст на языке текущего читателя.
     * <p>
     * {@code @JsonValue} — застава на будущее: сегодня пару не несёт ни один DTO (они берут
     * готовую строку через {@code name()}), но стоит положить её в ответ — и на клиент
     * уехал бы объект с двумя языками, то есть выбор языка достался бы клиенту. Выбирает
     * сервер, и выбирает в тот миг, когда ответ собирается для конкретного запроса.
     */
    @JsonValue
    public String text() {
        return text(LocaleContextHolder.getLocale());
    }

    /** Текст на заданном языке; перевода нет — английский. */
    public String text(Locale locale) {
        return "ru".equals(locale.getLanguage()) && ru != null && !ru.isBlank() ? ru : en;
    }
}
