package com.realtimetradeprocessing.simulator.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.messaging.OrderSubmittedEvent;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;

@Component
public class OutboxEventWriter {

    private static final String ORDER_AGGREGATE = "ORDER";

    private final OutboxEventJpaRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventWriter(OutboxEventJpaRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public OutboxEventEntity writeOrderSubmitted(OrderSubmittedEvent event, Instant createdAt) {
        OutboxEventEntity outboxEvent = OutboxEventEntity.pending(
            UUID.fromString(event.eventId()),
            ORDER_AGGREGATE,
            event.orderId(),
            OrderSubmittedEvent.EVENT_TYPE,
            serialize(event),
            event.correlationId(),
            createdAt
        );
        return outboxEventRepository.save(outboxEvent);
    }

    private String serialize(OrderSubmittedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize order submitted outbox event", exception);
        }
    }
}
