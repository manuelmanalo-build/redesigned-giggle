package com.realtimetradeprocessing.simulator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventStatus;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration",
    "trade.messaging.jms-listener-enabled=false",
    "trade.outbox.scheduling-enabled=false",
    "trade.outbox.max-attempts=2",
    "trade.outbox.initial-backoff-ms=1000"
})
@Testcontainers
class OutboxRelayServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("trade_simulator_outbox_test")
        .withUsername("trade_user")
        .withPassword("trade_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private OutboxEventJpaRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @Test
    void publishesPendingEventAndMarksItPublished() throws Exception {
        clearInvocations(orderEventPublisher);
        UUID eventId = saveOutboxEvent("order-published", OutboxEventStatus.PENDING, 0);

        int processed = outboxRelayService.publishDueEvents();

        assertThat(processed).isEqualTo(1);
        ArgumentCaptor<OrderSubmittedEvent> eventCaptor = ArgumentCaptor.forClass(OrderSubmittedEvent.class);
        verify(orderEventPublisher).publishOrderSubmitted(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventId()).isEqualTo(eventId.toString());

        OutboxEventEntity stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(stored.getPublishedAt()).isNotNull();
        assertThat(stored.getNextAttemptAt()).isNull();
        assertThat(stored.getLastError()).isNull();
    }

    @Test
    void failedPublishIncrementsAttemptsAndSchedulesRetry() throws Exception {
        clearInvocations(orderEventPublisher);
        doThrow(new MessagePublicationException("broker unavailable", new RuntimeException("down")))
            .when(orderEventPublisher)
            .publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
        UUID eventId = saveOutboxEvent("order-retry", OutboxEventStatus.PENDING, 0);

        int processed = outboxRelayService.publishDueEvents();

        assertThat(processed).isEqualTo(1);
        OutboxEventEntity stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        assertThat(stored.getLastError()).isEqualTo("broker unavailable");
        assertThat(stored.getNextAttemptAt()).isNotNull();
    }

    @Test
    void maxRetryFailureMarksEventFailed() throws Exception {
        clearInvocations(orderEventPublisher);
        doThrow(new MessagePublicationException("broker unavailable", new RuntimeException("down")))
            .when(orderEventPublisher)
            .publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
        UUID eventId = saveOutboxEvent("order-failed", OutboxEventStatus.PENDING, 1);

        int processed = outboxRelayService.publishDueEvents();

        assertThat(processed).isEqualTo(1);
        OutboxEventEntity stored = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(stored.getAttemptCount()).isEqualTo(2);
        assertThat(stored.getLastError()).isEqualTo("broker unavailable");
        assertThat(stored.getNextAttemptAt()).isNull();
    }

    @Test
    void publishedEventsAreNotRelayedAgain() throws Exception {
        clearInvocations(orderEventPublisher);
        saveOutboxEvent("order-already-published", OutboxEventStatus.PUBLISHED, 0);

        int processed = outboxRelayService.publishDueEvents();

        assertThat(processed).isZero();
        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
    }

    private UUID saveOutboxEvent(String orderId, OutboxEventStatus status, int attemptCount) throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-11T12:00:00Z");
        OrderSubmittedEvent event = new OrderSubmittedEvent(
            eventId.toString(),
            orderId,
            "CLIENT-" + orderId,
            "ACC-1",
            "AAPL",
            OrderSide.BUY,
            OrderType.MARKET,
            100,
            null,
            "corr-" + orderId,
            now
        );
        outboxEventRepository.saveAndFlush(new OutboxEventEntity(
            eventId,
            "ORDER",
            orderId,
            OrderSubmittedEvent.EVENT_TYPE,
            objectMapper.writeValueAsString(event),
            status,
            event.correlationId(),
            now,
            status == OutboxEventStatus.PUBLISHED ? now : null,
            null,
            attemptCount,
            null
        ));
        return eventId;
    }
}
