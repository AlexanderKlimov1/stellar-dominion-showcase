package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Шаблон стройки — п. 10, миграция 079: порядок развития, который игрок закладывает
 * колонии одним движением.
 * <p>
 * <b>Принадлежит учётной записи, а не партии.</b> Порядок развития — привычка игрока: он
 * переживает партию, годится для следующей и нужен в главном меню, где партии ещё нет.
 * Поэтому шаблон живёт рядом с языком, размером текста и раскладкой клавиш — в источнике
 * данных истории.
 * <p>
 * <b>{@link #tier} — стадия развития, и назначает её игрок.</b> Ранняя колония, зрелая,
 * столица: игра это число не толкует и ничего по нему не решает. Это ярлык, отвечающий на
 * вопрос «а этот шаблон для чего», и по нему шаблоны разложены в списке.
 */
@Entity
@Table(name = "build_template", indexes = @Index(name = "idx_build_template_account",
        columnList = "account_id"))
public class BuildTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Чей шаблон. Внешнего ключа нет: см. миграцию 079. */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    /** Стадия развития, назначенная игроком: 1, 2, 3… Игра её не толкует. */
    @Column(name = "tier", nullable = false)
    private Integer tier = 1;

    /**
     * Порядок стройки одной строкой через запятую.
     * <p>
     * Строкой, а не своей таблицей: строк в шаблоне полдесятка, меняются они все разом
     * одним «сохранить», и выбирать из них по одной незачем. Так же хранится очередь
     * колонии и раскладка клавиш.
     */
    @Column(name = "projects", nullable = false, length = 1000)
    private String projects = "";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getTier() {
        return tier;
    }

    public void setTier(Integer tier) {
        this.tier = tier == null ? 1 : tier;
    }

    /** Коды проектов по порядку; пустая строка — пустой шаблон, а не строка из пустоты. */
    public List<String> getProjectCodes() {
        if (projects == null || projects.isBlank()) {
            return List.of();
        }
        return Arrays.stream(projects.split(",")).map(String::trim).filter(code -> !code.isBlank())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public void setProjectCodes(List<String> codes) {
        this.projects = codes == null ? "" : String.join(",", codes);
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
