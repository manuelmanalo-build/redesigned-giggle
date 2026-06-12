package com.realtimetradeprocessing.simulator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.messaging.OrderSubmittedEvent;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventStatus;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;

class OutboxEventWriterTest {

    @Test
    void createsPendingOrderSubmittedOutboxEvent() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        when(repository.save(any(OutboxEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        OutboxEventWriter writer = new OutboxEventWriter(repository, new ObjectMapper().findAndRegisterModules());
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        OrderSubmittedEvent event = new OrderSubmittedEvent(
            eventId,
            "order-1",
            "CLIENT-1",
            "ACC-1",
            "AAPL",
            OrderSide.BUY,
            OrderType.LIMIT,
            100,
            new BigDecimal("101.25"),
            "corr-1",
            now
        );

        OutboxEventEntity saved = writer.writeOrderSubmitted(event, now);

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(saved).isSameAs(captor.getValue());
        assertThat(saved.getId()).isEqualTo(UUID.fromString(eventId));
        assertThat(saved.getAggregateType()).isEqualTo("ORDER");
        assertThat(saved.getAggregateId()).isEqualTo("order-1");
        assertThat(saved.getEventType()).isEqualTo(OrderSubmittedEvent.EVENT_TYPE);
        assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-1");
        assertThat(saved.getCreatedAt()).isEqualTo(now);
        assertThat(saved.getPayload()).contains("\"eventId\":\"" + eventId + "\"");
        assertThat(saved.getPayload()).contains("\"orderId\":\"order-1\"");
    }
}
