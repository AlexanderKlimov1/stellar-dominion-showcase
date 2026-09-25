package com.moo3.server.repository.history;

import com.moo3.server.domain.entity.history.AccountSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Сеансы входа — п. 3.1. */
public interface AccountSessionRepository extends JpaRepository<AccountSessionEntity, String> {

    /**
     * Убирает просроченные пропуска.
     * <p>
     * Чистится при входе, а не по расписанию: отдельный планировщик ради строки в таблице
     * заводить незачем, а вход — единственный момент, когда сеансов становится больше.
     */
    @Modifying
    @Query("DELETE FROM AccountSessionEntity s WHERE s.expiresAt < :now")
    void deleteExpired(OffsetDateTime now);

    /**
     * Гасит все пропуска записи. Нужен удалению записи администратором: пропуск живёт в
     * своей таблице и пережил бы её, оставшись действующим ключом от того, чего уже нет.
     */
    @Modifying
    @Query("DELETE FROM AccountSessionEntity s WHERE s.accountId = :accountId")
    void deleteByAccountId(UUID accountId);
}
