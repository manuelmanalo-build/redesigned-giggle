package com.realtimetradeprocessing.simulator.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecordEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(
        String idempotencyKey,
        String requestHash,
        String orderId,
        int responseStatus,
        Instant createdAt
    ) {
        this(idempotencyKey, requestHash, orderId, responseStatus, null, createdAt);
    }

    public IdempotencyRecordEntity(
        String idempotencyKey,
        String requestHash,
        String orderId,
        int responseStatus,
        String responseBody,
        Instant createdAt
    ) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.orderId = orderId;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getOrderId() {
        return orderId;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
