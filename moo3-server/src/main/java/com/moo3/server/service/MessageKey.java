package com.moo3.server.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Ключ сообщения с подстановками — текст, который ещё не переведён.
 * <p>
 * Носят его три разных дела: отказ API ({@code ApiException}), счётчик попыток входа и
 * отчёт хода — всюду, где текст рождается раньше, чем становится известен язык.
 * <p>
 * Нужен там, где сообщение собирается из частей: одна часть — подстановка в другую, и обе
 * должны прийти игроку на его языке («Слишком много попыток. Попробуйте через {0}», где
 * {0} — «4 мин» или «40 с»). {@code Messages} переводит такую подстановку рекурсивно.
 */
public record MessageKey(String key, Object... args) {

    /**
     * Подстановки строками — так они и лежат в базе: у колонки типов нет.
     * <p>
     * Перечисление кладётся КЛЮЧОМ своего ярлыка ({@code enum.<Тип>.<ИМЯ>}): показ переведёт
     * его вместе с самим сообщением, а положить сюда готовый ярлык значило бы выбрать язык
     * за читателя.
     */
    public List<String> storedArgs() {
        List<String> stored = new ArrayList<>(args.length);
        for (Object arg : args) {
            /*
              Вложенного ключа С ПОДСТАНОВКАМИ здесь быть не может: в колонке от него
              осталась бы запись объекта, а не текст, — и игрок увидел бы «MessageKey[...]».
              Составное сообщение собирается ОДНИМ ключом со всеми подстановками; ключ БЕЗ
              подстановок передаётся обычной строкой — показ переведёт его сам.
             */
            if (arg instanceof MessageKey nested) {
                throw new IllegalArgumentException("Вложенный ключ в подстановке: " + nested.key());
            }
            stored.add(arg instanceof Enum<?> value
                    ? "enum." + value.getDeclaringClass().getSimpleName() + "." + value.name()
                    : String.valueOf(arg));
        }
        return stored;
    }

    /** Сообщение целиком одной колонкой: ключ первой строкой, за ним подстановки. */
    public List<String> packed() {
        List<String> packed = new ArrayList<>(args.length + 1);
        packed.add(key);
        packed.addAll(storedArgs());
        return packed;
    }

    /**
     * Обратно из колонки.
     * <p>
     * <b>Сообщение, записанное до перехода на ключи, читается как есть:</b> в колонке лежит
     * готовая русская строка, ключом она не окажется, и показ отдаст её без перевода. Так
     * партия, начатая на прошлой версии, доигрывается без пустых мест в итогах боя.
     */
    public static MessageKey unpack(List<String> packed) {
        if (packed == null || packed.isEmpty()) {
            return null;
        }
        return new MessageKey(packed.get(0), packed.subList(1, packed.size()).toArray());
    }
}
