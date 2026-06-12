package com.realtimetradeprocessing.simulator.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventStatus;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);

    long countByStatus(OutboxEventStatus status);

    @Query(value = """
        SELECT *
        FROM outbox_events
        WHERE status = 'PENDING'
          AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEventEntity> findDuePendingEventsForUpdate(
        @Param("now") Instant now,
        @Param("batchSize") int batchSize
    );
}
