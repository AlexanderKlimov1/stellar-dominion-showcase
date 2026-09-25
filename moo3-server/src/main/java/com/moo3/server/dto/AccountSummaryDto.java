package com.moo3.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Учётная запись в списке администратора — п. 3.1.
 * <p>
 * Того, чем запись открывают, здесь нет и быть не может: ни пароля (он и в базе лежит
 * хешем), ни ссылки подтверждения. Список нужен ради одного действия — подтвердить
 * застрявшую регистрацию, — и показывает ровно то, по чему её узнают.
 *
 * @param login     он же почта: отдельного логина у записи нет (кроме администратора)
 * @param name      имя для показа в интерфейсе игры
 * @param confirmed почта подтверждена; неподтверждённая запись в игру не пускает
 */
public record AccountSummaryDto(
        UUID id,
        String login,
        String email,
        String name,
        String role,
        String roleLabel,
        Boolean confirmed,
        OffsetDateTime createdAt
) {
}
