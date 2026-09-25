package com.moo3.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Связка сторон, которую стоит проверить, и что о ней известно — этап 2, п. 2.16
 * (`balance-metrics-works.txt`).
 * <p>
 * <b>Связок больше двадцати тысяч, и перебирать их незачем.</b> Смысл имеют не все, а
 * считанные — те, о которых есть догадка, зачем игрок берёт эти стороны вместе: киборги с
 * промышленниками, литоворы с плохими фермерами, объединение с плодовитыми. Догадка
 * рождается в голове хозяина проекта, а не в замере, и живёт ДО прогона — поэтому у неё
 * своя строка с объяснением словами.
 * <p>
 * Результат сюда же и возвращается: последний прогон, проверявший связку, записывает свой
 * ответ. Так через месяц видно не только «что проверяли», но и «что вышло» — а без этого
 * проверка повторяется по кругу.
 */
@Entity
@Table(name = "balance_combination")
public class BalanceCombinationEntity {

    @Id
    private UUID id;

    /** Коды сторон: две или три. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "traits", nullable = false)
    private String traits;

    /** Зачем проверяем — словами. Это единственное, чего замер не восстановит сам. */
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "verdict", length = 20)
    private String verdict;

    @Column(name = "extra")
    private Double extra;

    @Column(name = "error")
    private Double error;

    @Column(name = "carriers")
    private Integer carriers;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTraits() {
        return traits;
    }

    public void setTraits(String traits) {
        this.traits = traits;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(OffsetDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }

    public UUID getRunId() {
        return runId;
    }

    public void setRunId(UUID runId) {
        this.runId = runId;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public Double getExtra() {
        return extra;
    }

    public void setExtra(Double extra) {
        this.extra = extra;
    }

    public Double getError() {
        return error;
    }

    public void setError(Double error) {
        this.error = error;
    }

    public Integer getCarriers() {
        return carriers;
    }

    public void setCarriers(Integer carriers) {
        this.carriers = carriers;
    }
}
