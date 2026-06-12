package com.realtimetradeprocessing.simulator.persistence.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.realtimetradeprocessing.simulator.persistence.entity.IdempotencyRecordEntity;

public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    @Modifying
    @Query(value = """
        INSERT INTO idempotency_records (
            idempotency_key,
            request_hash,
            response_status,
            created_at
        )
        VALUES (
            :idempotencyKey,
            :requestHash,
            :responseStatus,
            :createdAt
        )
        ON CONFLICT (idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int claimRequest(
        @Param("idempotencyKey") String idempotencyKey,
        @Param("requestHash") String requestHash,
        @Param("responseStatus") int responseStatus,
        @Param("createdAt") Instant createdAt
    );

    @Modifying
    @Query(value = """
        UPDATE idempotency_records
        SET order_id = :orderId,
            response_status = :responseStatus
        WHERE idempotency_key = :idempotencyKey
        """, nativeQuery = true)
    int completeRequest(
        @Param("idempotencyKey") String idempotencyKey,
        @Param("orderId") String orderId,
        @Param("responseStatus") int responseStatus
    );
}
