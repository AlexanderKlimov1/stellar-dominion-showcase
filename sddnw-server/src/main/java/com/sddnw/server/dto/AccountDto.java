package com.sddnw.server.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Учётная запись для клиента — п. 3.1. Пароля здесь нет и быть не может: наружу уходит
 * только то, чем игрок представляется другим.
 *
 * @param name      имя для показа в интерфейсе игры; логином служит почта
 * @param roleLabel роль на языке запроса, для интерфейса
 * @param locale    язык игрока — п. 3.5: {@code en}, {@code ru} или пусто, если игрок его
 *                  не называл. Клиент ставит его сразу после входа: язык записи переживает
 *                  и браузер, и машину, а {@code localStorage} — нет
 * @param textScale размер текста интерфейса в процентах — п. 11.1: 100, 115, 130, 150 или
 *                  пусто, если игрок его не называл. Едет за игроком по той же причине,
 *                  что и язык
 * @param hotkeys   раскладка горячих клавиш карты — п. 11.1: «действие: клавиша»
 *                  ({@code {"turn": "KeyT"}}) или пусто, если игрок клавиш не
 *                  переназначал. Что значат эти пары, знает только клиент: сервер по
 *                  горячей клавише не делает ничего, он её хранит и отдаёт
 */
public record AccountDto(
        UUID id,
        String login,
        String email,
        String name,
        String role,
        String roleLabel,
        String locale,
        Integer textScale,
        Map<String, String> hotkeys
) {
}
