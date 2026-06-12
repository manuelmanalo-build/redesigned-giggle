package com.realtimetradeprocessing.simulator.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "processed_messages")
public class ProcessedMessageEntity {

    @Id
    @Column(name = "message_id", nullable = false, length = 128)
    private String messageId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "aggregate_id", length = 128)
    private String aggregateId;

    @Column(name = "consumer_name", nullable = false, length = 128)
    private String consumerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProcessedMessageStatus status;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    protected ProcessedMessageEntity() {
    }

    public ProcessedMessageEntity(
        String messageId,
        String eventType,
        String aggregateId,
        String consumerName,
        ProcessedMessageStatus status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant processedAt,
        int attemptCount,
        String lastError,
        String correlationId
    ) {
        this.messageId = messageId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.consumerName = consumerName;
        this.status = status;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.processedAt = processedAt;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.correlationId = correlationId;
    }

    public boolean isTerminal() {
        return status == ProcessedMessageStatus.PROCESSED
            || status == ProcessedMessageStatus.DUPLICATE
            || status == ProcessedMessageStatus.DEAD_LETTERED;
    }

    public void markReceived(Instant now) {
        this.status = ProcessedMessageStatus.RECEIVED;
        this.lastSeenAt = now;
        this.attemptCount++;
        this.lastError = null;
    }

    public void markProcessed(Instant now) {
        this.status = ProcessedMessageStatus.PROCESSED;
        this.lastSeenAt = now;
        this.processedAt = now;
        this.lastError = null;
    }

    public void markDuplicateSeen(Instant now) {
        this.status = ProcessedMessageStatus.DUPLICATE;
        this.lastSeenAt = now;
    }

    public void markFailed(String lastError, Instant now) {
        this.status = ProcessedMessageStatus.FAILED;
        this.lastSeenAt = now;
        this.attemptCount++;
        this.lastError = lastError;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public ProcessedMessageStatus getStatus() {
        return status;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
