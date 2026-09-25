package com.moo3.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сеанс входа — п. 3.1: пропуск, который клиент держит у себя и шлёт заголовком.
 * <p>
 * Отдельной таблицей, а не колонкой у записи: человек играет с двух окон, и вход со
 * второго не должен выбрасывать из первого. Срок у пропуска есть — забытая на чужой
 * машине вкладка не должна пускать в игру вечно.
 */
/*
 * Индексы и уникальности объявлены ЗДЕСЬ, а не только в миграции.
 *
 * Объявленные в changelog'е, они существуют лишь там, где changelog прошёл. На H2, где
 * схему строит Hibernate по сущностям (режим балансового прогона), их не было вовсе — и
 * та же партия в пятьсот ходов шла 236 секунд вместо 159: каждая выборка внутри хода
 * перебирала таблицу целиком. Вторая причина проще: глядя на сущность, видно, по каким
 * полям её ищут, а лишний индекс заметен рядом с полем, а не в файле миграции
 * трёхмесячной давности.
 *
 * В Postgres они уже созданы, и Hibernate их не трогает: ddl-auto: validate сверяет
 * таблицы и колонки, но не индексы.
 */
@Entity
@Table(name = "account_session",
    indexes = {
        @Index(name = "ix_account_session_account", columnList = "account_id")
    })
public class AccountSessionEntity {

    /** Сам пропуск: он и первичный ключ — искать сеанс всегда по нему. */
    @Id
    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
