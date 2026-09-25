package com.moo3.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Корень сервера отдаёт собранный клиент — `moo3-client/dist`.
 * <p>
 * Публиковать наружу dev-сервер Vite нельзя: он раздаёт исходники проекта, ничего ни у
 * кого не спрашивает и вдобавок проксирует API. Наружу смотрит этот сервер и только он —
 * игра и её API приходят тогда с одного источника, поэтому клиенту хватает
 * относительного `/api`, а CORS для публикации не нужен вовсе. Сами файлы сборки
 * раздаются обычным обработчиком статики (`spring.web.resources.static-locations`).
 * <p>
 * Почему одной статики мало и понадобилась пересылка: «страницу приветствия» Spring Boot
 * ищет index.html <b>один раз, при старте</b>. Сервер поднимают до первой сборки клиента —
 * и корень отвечал бы 404 до перезапуска, хотя dist давно собран. Пересылка ищет файл на
 * каждый запрос, и собранная сборка подхватывается сама.
 */
@Controller
public class ClientPageController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
