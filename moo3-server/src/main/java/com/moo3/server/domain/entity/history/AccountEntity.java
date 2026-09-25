package com.moo3.server.domain.entity.history;

import com.moo3.server.domain.enums.AccountRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Учётная запись игрока — п. 3.1.
 * <p>
 * Живёт между партиями, в отличие от игрока партии ({@link PlayerEntity}): один человек
 * играет много партий, а сыгранные партии переживают его запись. Поэтому это отдельная
 * таблица, а не поля у игрока.
 * <p>
 * Пароль хранится хешем: в открытом виде его нет ни в базе, ни в журналах. Единственное
 * исключение — файл администратора, который создаётся один раз при первом запуске.
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
@Table(name = "account",
    indexes = {
        @Index(name = "ix_account_confirm_token", columnList = "confirm_token")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_account_email", columnNames = {"email"}),
        @UniqueConstraint(name = "uq_account_login", columnNames = {"login"})
    })
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Логин: им входят, его же видно другим игрокам в лобби. */
    @Column(name = "login", nullable = false)
    private String login;

    /** Почта, на которую ушла ссылка подтверждения; у администратора её нет. */
    @Column(name = "email")
    private String email;

    /**
     * Имя игрока для интерфейса — п. 3.1.
     * <p>
     * Логином теперь служит почта, а называться ею в игре незачем: в главном меню и в
     * списке записей стоит это имя. На вход оно не влияет и уникальным быть не обязано —
     * двух Игроков в галактике игра переживёт.
     */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private AccountRole role = AccountRole.PLAYER;

    /** Подтверждена ли почта: до подтверждения вход закрыт — п. 3.1. */
    @Column(name = "confirmed", nullable = false)
    private Boolean confirmed = Boolean.FALSE;

    /**
     * Одноразовая ссылка из письма. Хранится до подтверждения и стирается вместе с ним:
     * ссылка, которой уже воспользовались, вторым входом быть не должна.
     */
    @Column(name = "confirm_token")
    private String confirmToken;

    @Column(name = "confirm_expires_at")
    private OffsetDateTime confirmExpiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * Язык игрока — п. 3.5: {@code en}, {@code ru} или пусто.
     * <p>
     * Пусто значит «игрок языка не называл», и тогда решает браузер. Язык живёт ЗДЕСЬ, а не
     * только в {@code localStorage}, потому что запись переживает и браузер, и машину: сев
     * за другую, игрок получал бы английский заново.
     */
    @Column(name = "locale", length = 8)
    private String locale;

    /**
     * Размер текста интерфейса в процентах — п. 11.1: 100, 115, 130 или 150; пусто значит
     * «игрок размера не называл», то есть сотня.
     * <p>
     * Живёт здесь по той же причине, что и язык: настройка из {@code localStorage} теряется
     * на второй машине. Проценты, а не кегль: кеглей в игре два десятка, а множитель у них
     * общий, и он же стоит в корне стилей.
     */
    @Column(name = "text_scale")
    private Integer textScale;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Integer getTextScale() {
        return textScale;
    }

    public void setTextScale(Integer textScale) {
        this.textScale = textScale;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public AccountRole getRole() {
        return role;
    }

    public void setRole(AccountRole role) {
        this.role = role;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String getConfirmToken() {
        return confirmToken;
    }

    public void setConfirmToken(String confirmToken) {
        this.confirmToken = confirmToken;
    }

    public OffsetDateTime getConfirmExpiresAt() {
        return confirmExpiresAt;
    }

    public void setConfirmExpiresAt(OffsetDateTime confirmExpiresAt) {
        this.confirmExpiresAt = confirmExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
