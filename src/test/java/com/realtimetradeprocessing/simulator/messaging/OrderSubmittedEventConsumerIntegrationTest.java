package com.realtimetradeprocessing.simulator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration",
    "trade.messaging.jms-listener-enabled=false",
    "trade.execution.simulated-market-price=100.00"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderSubmittedEventConsumerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("trade_simulator_consumer_test")
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderSubmittedEventConsumer consumer;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private ExecutionReportJpaRepository executionReportRepository;

    @Autowired
    private TradeJpaRepository tradeRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @Test
    void fillsMarketOrderAndCreatesExecutionReportAndTrade() throws Exception {
        OrderSubmittedEvent event = submitAndCaptureEvent("idem-consumer-market", request(OrderSide.BUY, OrderType.MARKET, null));

        consumer.receive(objectMapper.writeValueAsString(event));

        OrderEntity order = orderRepository.findById(event.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(order.getFilledQuantity()).isEqualTo(100);
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId()))
            .singleElement()
            .satisfies(report -> assertFillReport(report, event.orderId()));
        assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId()))
            .singleElement()
            .satisfies(trade -> {
                assertThat(trade.getOrderId()).isEqualTo(event.orderId());
                assertThat(trade.getQuantity()).isEqualTo(100);
                assertThat(trade.getPrice()).isEqualByComparingTo("100.00");
            });
    }

    @Test
    void fillsLimitBuyWhenLimitAllowsExecution() throws Exception {
        OrderSubmittedEvent event = submitAndCaptureEvent("idem-consumer-limit-fill", request(
            OrderSide.BUY,
            OrderType.LIMIT,
            new BigDecimal("100.00")
        ));

        consumer.receive(objectMapper.writeValueAsString(event));

        assertThat(orderRepository.findById(event.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId()))
            .singleElement()
            .satisfies(report -> assertFillReport(report, event.orderId()));
        assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId())).hasSize(1);
    }

    @Test
    void leavesLimitBuyAcceptedWhenLimitDoesNotAllowExecution() throws Exception {
        OrderSubmittedEvent event = submitAndCaptureEvent("idem-consumer-limit-no-fill", request(
            OrderSide.BUY,
            OrderType.LIMIT,
            new BigDecimal("99.99")
        ));

        consumer.receive(objectMapper.writeValueAsString(event));

        OrderEntity order = orderRepository.findById(event.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(order.getFilledQuantity()).isZero();
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId()))
            .singleElement()
            .satisfies(report -> {
                assertThat(report.getExecutionType()).isEqualTo(ExecutionType.ACCEPTED);
                assertThat(report.getOrderStatus()).isEqualTo(OrderStatus.ACCEPTED);
                assertThat(report.getExecutedQuantity()).isNull();
                assertThat(report.getExecutionPrice()).isNull();
            });
        assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId())).isEmpty();
    }

    @Test
    void duplicateMessageDoesNotCreateDuplicateTerminalReportOrTrade() throws Exception {
        OrderSubmittedEvent event = submitAndCaptureEvent("idem-consumer-duplicate", request(OrderSide.BUY, OrderType.MARKET, null));
        String payload = objectMapper.writeValueAsString(event);

        consumer.receive(payload);
        consumer.receive(payload);

        assertThat(orderRepository.findById(event.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId())).hasSize(1);
        assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc(event.orderId())).hasSize(1);
    }

    @Test
    void missingOrderEventIsHandledSafely() {
        OrderSubmittedEvent event = new OrderSubmittedEvent(
            "missing-event",
            "missing-order",
            "CLIENT-MISSING",
            "ACC-001",
            "AAPL",
            OrderSide.BUY,
            OrderType.MARKET,
            100,
            null,
            "corr-missing",
            Instant.parse("2026-06-09T21:00:00Z")
        );

        assertThatCode(() -> consumer.receive(objectMapper.writeValueAsString(event))).doesNotThrowAnyException();
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc("missing-order")).isEmpty();
        assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc("missing-order")).isEmpty();
    }

    @Test
    void invalidMessageIncrementsProcessingFailureMetric() {
        double before = meterRegistry.counter("trade.messages.processing.failures").count();

        assertThatThrownBy(() -> consumer.receive("{not-json"))
            .isInstanceOf(MessageConsumptionException.class);

        assertThat(meterRegistry.counter("trade.messages.processing.failures").count()).isEqualTo(before + 1.0);
    }

    private OrderSubmittedEvent submitAndCaptureEvent(String idempotencyKey, String requestBody) throws Exception {
        clearInvocations(orderEventPublisher);

        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Correlation-Id", "corr-" + idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated());

        ArgumentCaptor<OrderSubmittedEvent> eventCaptor = ArgumentCaptor.forClass(OrderSubmittedEvent.class);
        verify(orderEventPublisher).publishOrderSubmitted(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private static void assertFillReport(ExecutionReportEntity report, String orderId) {
        assertThat(report.getOrderId()).isEqualTo(orderId);
        assertThat(report.getExecutionType()).isEqualTo(ExecutionType.FILL);
        assertThat(report.getOrderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(report.getExecutedQuantity()).isEqualTo(100);
        assertThat(report.getExecutionPrice()).isEqualByComparingTo("100.00");
    }

    private static String request(OrderSide side, OrderType type, BigDecimal limitPrice) {
        String limitPriceLine = limitPrice == null ? "" : "  \"limitPrice\": " + limitPrice + "\n";
        String commaAfterQuantity = limitPrice == null ? "" : ",";
        return """
            {
              "clientOrderId": "CLIENT-%s-%s-%s",
              "accountId": "ACC-001",
              "symbol": "AAPL",
              "side": "%s",
              "type": "%s",
              "quantity": 100%s
            %s}
            """.formatted(side, type, limitPrice == null ? "MARKET" : limitPrice, side, type, commaAfterQuantity, limitPriceLine);
    }
}
