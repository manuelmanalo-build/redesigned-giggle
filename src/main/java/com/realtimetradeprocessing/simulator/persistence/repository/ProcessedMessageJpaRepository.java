package com.realtimetradeprocessing.simulator.persistence.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.realtimetradeprocessing.simulator.persistence.entity.ProcessedMessageEntity;

import jakarta.persistence.LockModeType;

public interface ProcessedMessageJpaRepository extends JpaRepository<ProcessedMessageEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select message from ProcessedMessageEntity message where message.messageId = :messageId")
    Optional<ProcessedMessageEntity> findByMessageIdForUpdate(@Param("messageId") String messageId);

    @Modifying
    @Query(value = """
        INSERT INTO processed_messages (
            message_id,
            event_type,
            aggregate_id,
            consumer_name,
            status,
            first_seen_at,
            last_seen_at,
            processed_at,
            attempt_count,
            last_error,
            correlation_id
        )
        VALUES (
            :messageId,
            :eventType,
            :aggregateId,
            :consumerName,
            'RECEIVED',
            :now,
            :now,
            NULL,
            1,
            NULL,
            :correlationId
        )
        ON CONFLICT (message_id) DO NOTHING
        """, nativeQuery = true)
    int claimReceived(
        @Param("messageId") String messageId,
        @Param("eventType") String eventType,
        @Param("aggregateId") String aggregateId,
        @Param("consumerName") String consumerName,
        @Param("correlationId") String correlationId,
        @Param("now") Instant now
    );
}
