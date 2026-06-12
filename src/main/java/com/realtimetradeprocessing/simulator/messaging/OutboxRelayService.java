package com.realtimetradeprocessing.simulator.messaging;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventStatus;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;

@Service
public class OutboxRelayService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxEventJpaRepository outboxEventRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration initialBackoff;

    @Autowired
    public OutboxRelayService(
        OutboxEventJpaRepository outboxEventRepository,
        OrderEventPublisher orderEventPublisher,
        ObjectMapper objectMapper,
        @Value("${trade.outbox.batch-size:25}") int batchSize,
        @Value("${trade.outbox.max-attempts:5}") int maxAttempts,
        @Value("${trade.outbox.initial-backoff-ms:1000}") long initialBackoffMs
    ) {
        this(
            outboxEventRepository,
            orderEventPublisher,
            objectMapper,
            Clock.systemUTC(),
            batchSize,
            maxAttempts,
            Duration.ofMillis(initialBackoffMs)
        );
    }

    OutboxRelayService(
        OutboxEventJpaRepository outboxEventRepository,
        OrderEventPublisher orderEventPublisher,
        ObjectMapper objectMapper,
        Clock clock,
        int batchSize,
        int maxAttempts,
        Duration initialBackoff
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff.isNegative() || initialBackoff.isZero()
            ? Duration.ofMillis(1)
            : initialBackoff;
    }

    @Transactional
    public int publishDueEvents() {
        Instant now = clock.instant();
        List<OutboxEventEntity> events = outboxEventRepository.findDuePendingEventsForUpdate(now, batchSize);
        for (OutboxEventEntity event : events) {
            publishOne(event, now);
        }
        return events.size();
    }

    private void publishOne(OutboxEventEntity event, Instant now) {
        if (event.getStatus() != OutboxEventStatus.PENDING) {
            return;
        }

        try {
            publishPayload(event);
            event.markPublished(now);
            LOGGER.info(
                "outbox_event_published eventId={} aggregateType={} aggregateId={} eventType={}",
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType()
            );
        } catch (RuntimeException exception) {
            Instant nextAttemptAt = now.plus(backoff(event.getAttemptCount() + 1));
            event.recordFailure(errorMessage(exception), nextAttemptAt, maxAttempts);
            LOGGER.warn(
                "outbox_event_publish_failed eventId={} aggregateType={} aggregateId={} eventType={} attemptCount={} status={}",
                event.getId(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                event.getAttemptCount(),
                event.getStatus()
            );
        }
    }

    private void publishPayload(OutboxEventEntity event) {
        if (!OrderSubmittedEvent.EVENT_TYPE.equals(event.getEventType())) {
            throw new IllegalArgumentException("Unsupported outbox event type: " + event.getEventType());
        }

        try {
            OrderSubmittedEvent orderSubmittedEvent = objectMapper.readValue(event.getPayload(), OrderSubmittedEvent.class);
            orderEventPublisher.publishOrderSubmitted(orderSubmittedEvent);
        } catch (JsonProcessingException exception) {
            throw new MessagePublicationException("Failed to deserialize outbox event payload", exception);
        }
    }

    private Duration backoff(int attemptNumber) {
        return initialBackoff.multipliedBy(Math.max(1, attemptNumber));
    }

    private static String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}
