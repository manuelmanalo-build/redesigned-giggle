package com.realtimetradeprocessing.simulator.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OutboxEventStatus status;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OutboxEventStatus status,
        String correlationId,
        Instant createdAt,
        Instant publishedAt,
        Instant nextAttemptAt,
        int attemptCount,
        String lastError
    ) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = status;
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.nextAttemptAt = nextAttemptAt;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
    }

    public static OutboxEventEntity pending(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        String correlationId,
        Instant createdAt
    ) {
        return new OutboxEventEntity(
            id,
            aggregateType,
            aggregateId,
            eventType,
            payload,
            OutboxEventStatus.PENDING,
            correlationId,
            createdAt,
            null,
            null,
            0,
            null
        );
    }

    public void markPublished(Instant publishedAt) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    public void recordFailure(String lastError, Instant nextAttemptAt, int maxAttempts) {
        this.attemptCount++;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
        if (this.attemptCount >= maxAttempts) {
            this.status = OutboxEventStatus.FAILED;
            this.nextAttemptAt = null;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxEventStatus getStatus() {
        return status;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }
}
