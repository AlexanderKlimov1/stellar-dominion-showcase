package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Шаблон стройки — п. 10: порядок развития, который закладывают колонии одним движением.
 *
 * @param tier     стадия развития, назначенная игроком (1, 2, 3…). Игра её не толкует —
 *                 это ярлык, по которому шаблоны разложены в списке
 * @param projects коды проектов ПО ПОРЯДКУ: те же, что в списке стройки колонии
 */
public record BuildTemplateDto(
        UUID id,
        String name,
        Integer tier,
        List<String> projects
) {
}
