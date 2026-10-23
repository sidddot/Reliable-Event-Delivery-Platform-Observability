package com.eventplatform.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttemptEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error")
    private String error;

    @Column(name = "response_snippet", length = 1024)
    private String responseSnippet;

    @Column(name = "worker_id", nullable = false)
    private String workerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public DeliveryAttemptEntity() {
    }

    public DeliveryAttemptEntity(UUID deliveryId, int attemptNo, Instant startedAt, long durationMs,
                                 Integer httpStatus, String error, String responseSnippet, String workerId) {
        this.deliveryId = deliveryId;
        this.attemptNo = attemptNo;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.httpStatus = httpStatus;
        this.error = error;
        this.responseSnippet = truncateSnippet(responseSnippet);
        this.workerId = workerId;
    }

    private static String truncateSnippet(String snippet) {
        if (snippet == null) {
            return null;
        }
        return snippet.length() > 1024 ? snippet.substring(0, 1024) : snippet;
    }

    @PrePersist
    public void onPrePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(UUID deliveryId) {
        this.deliveryId = deliveryId;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getResponseSnippet() {
        return responseSnippet;
    }

    public void setResponseSnippet(String responseSnippet) {
        this.responseSnippet = truncateSnippet(responseSnippet);
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeliveryAttemptEntity that = (DeliveryAttemptEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
