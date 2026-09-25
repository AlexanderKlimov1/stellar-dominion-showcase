package com.moo3.server.web.error;

/**
 * Отказ API, который увидит игрок, — п. 3.5 (локализация).
 * <p>
 * Исключение несёт не текст, а <b>ключ</b> из {@code messages.properties} и подстановки
 * к нему: на каком языке отвечать, известно только в момент ответа — по заголовку
 * {@code Accept-Language} запроса, — а бросается отказ глубоко в сервисе, где о языке
 * игрока не знают и знать не должны. Переводит ключ в текст обработчик ошибок
 * ({@code GlobalExceptionHandler}) через {@code Messages}.
 * <p>
 * В {@code getMessage()} лежит ключ с подстановками: этого хватает журналу и тестам, а
 * игроку он не показывается. Подстановки переводятся тоже: перечисление — по ключу
 * {@code enum.<Тип>.<ИМЯ>}, вложенный отказ — своим же ключом, остальное — как есть.
 */
public abstract class ApiException extends RuntimeException {

    private final String key;
    private final Object[] args;

    protected ApiException(String key, Object... args) {
        super(args.length == 0 ? key : key + " " + java.util.Arrays.toString(args));
        this.key = key;
        this.args = args;
    }

    /** Ключ сообщения в {@code messages.properties}. */
    public String key() {
        return key;
    }

    /** Подстановки в сообщение — {0}, {1}… в порядке объявления. */
    public Object[] args() {
        return args;
    }
}
