package com.realtimetradeprocessing.simulator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.domain.ExecutionReport;
import com.realtimetradeprocessing.simulator.domain.ExecutionReportId;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;
import com.realtimetradeprocessing.simulator.domain.Trade;
import com.realtimetradeprocessing.simulator.domain.TradeId;
import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.messaging.OrderEventPublisher;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventStatus;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration",
    "trade.messaging.jms-publisher-enabled=false",
    "trade.messaging.jms-listener-enabled=false",
    "trade.outbox.scheduling-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class OrderApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("trade_simulator_api_test")
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
    private ExecutionReportJpaRepository executionReportRepository;

    @Autowired
    private TradeJpaRepository tradeRepository;

    @Autowired
    private OutboxEventJpaRepository outboxEventRepository;

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @Test
    void submitsAndRetrievesOrder() throws Exception {
        clearInvocations(orderEventPublisher);

        MvcResult result = submitOrder("idem-api-submit", validLimitOrderJson())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clientOrderId").value("CLIENT-123"))
            .andExpect(jsonPath("$.accountId").value("ACC-001"))
            .andExpect(jsonPath("$.symbol").value("AAPL"))
            .andExpect(jsonPath("$.side").value("BUY"))
            .andExpect(jsonPath("$.type").value("LIMIT"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.quantity").value(100))
            .andExpect(jsonPath("$.limitPrice").value(185.50))
            .andExpect(jsonPath("$.filledQuantity").value(0))
            .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String orderId = body.get("orderId").asText();
        assertThat(orderId).isNotBlank();

        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
        assertOutboxEventCreated(orderId, "corr-idem-api-submit");

        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(orderId))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void returnsSameLogicalResponseForSameIdempotencyKeyAndRequest() throws Exception {
        clearInvocations(orderEventPublisher);

        JsonNode first = objectMapper.readTree(submitOrder("idem-api-replay", validLimitOrderJson())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());

        JsonNode second = objectMapper.readTree(submitOrder("idem-api-replay", validLimitOrderJson())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());

        assertThat(second.get("orderId").asText()).isEqualTo(first.get("orderId").asText());
        assertThat(second.get("clientOrderId").asText()).isEqualTo("CLIENT-123");
        assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(first.get("orderId").asText())).hasSize(1);
        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentSubmissionsWithSameIdempotencyKeyReturnSameLogicalResponse() throws Exception {
        clearInvocations(orderEventPublisher);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> task = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return submitOrder("idem-api-concurrent", validLimitOrderJson())
                .andExpect(status().isCreated())
                .andReturn();
        };

        try {
            Future<MvcResult> firstFuture = executor.submit(task);
            Future<MvcResult> secondFuture = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<JsonNode> responses = List.of(
                objectMapper.readTree(firstFuture.get(10, TimeUnit.SECONDS).getResponse().getContentAsString()),
                objectMapper.readTree(secondFuture.get(10, TimeUnit.SECONDS).getResponse().getContentAsString())
            );

            assertThat(responses.get(1).get("orderId").asText()).isEqualTo(responses.get(0).get("orderId").asText());
            assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(responses.get(0).get("orderId").asText())).hasSize(1);
            verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void returnsConflictForSameIdempotencyKeyAndDifferentRequest() throws Exception {
        clearInvocations(orderEventPublisher);

        submitOrder("idem-api-conflict", validLimitOrderJson())
            .andExpect(status().isCreated());

        submitOrder("idem-api-conflict", """
                {
                  "clientOrderId": "CLIENT-123",
                  "accountId": "ACC-001",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "type": "LIMIT",
                  "quantity": 200,
                  "limitPrice": 185.50
                }
                """)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotPublishEventForInvalidOrderRequest() throws Exception {
        clearInvocations(orderEventPublisher);
        long outboxCount = outboxEventRepository.count();

        submitOrder("idem-api-invalid", """
                {
                  "clientOrderId": "CLIENT-123",
                  "accountId": "ACC-001",
                  "symbol": "AAPL",
                  "side": "BUY",
                  "type": "LIMIT",
                  "quantity": 0,
                  "limitPrice": 185.50
                }
                """)
            .andExpect(status().isBadRequest());

        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);
        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsOverlongIdempotencyKeyBeforePersistence() throws Exception {
        clearInvocations(orderEventPublisher);
        long outboxCount = outboxEventRepository.count();
        String overlongKey = "x".repeat(129);

        submitOrder(overlongKey, validLimitOrderJson())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);
        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retrievesExecutionReportsAndTradesForOrder() throws Exception {
        clearInvocations(orderEventPublisher);

        JsonNode order = objectMapper.readTree(submitOrder("idem-api-history", validLimitOrderJson())
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
        String orderId = order.get("orderId").asText();
        Instant now = Instant.parse("2026-06-09T18:30:00Z");
        ExecutionReport report = ExecutionReport.fill(
            ExecutionReportId.of("exec-api-1"),
            OrderId.of(orderId),
            Quantity.of(100),
            Price.of("185.5000")
        );
        executionReportRepository.saveAndFlush(ExecutionReportEntity.fromDomain(report, now));
        Trade trade = Trade.fromExecutionReport(
            TradeId.of("trade-api-1"),
            AccountId.of("ACC-001"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            report
        );
        tradeRepository.saveAndFlush(TradeEntity.fromDomain(trade, now));

        mockMvc.perform(get("/api/v1/orders/{orderId}/execution-reports", orderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].executionReportId").value("exec-api-1"))
            .andExpect(jsonPath("$[0].executionType").value("FILL"))
            .andExpect(jsonPath("$[0].executedQuantity").value(100))
            .andExpect(jsonPath("$[0].executionPrice").value(185.5000));

        mockMvc.perform(get("/api/v1/orders/{orderId}/trades", orderId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tradeId").value("trade-api-1"))
            .andExpect(jsonPath("$[0].executionReportId").value("exec-api-1"))
            .andExpect(jsonPath("$[0].quantity").value(100))
            .andExpect(jsonPath("$[0].price").value(185.5000));
    }

    @Test
    void exposesHealthAndCustomMetrics() throws Exception {
        clearInvocations(orderEventPublisher);

        submitOrder("idem-api-metrics", validLimitOrderJson())
            .andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/metrics/trade.orders.submitted"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("trade.orders.submitted"));
    }

    private org.springframework.test.web.servlet.ResultActions submitOrder(String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Correlation-Id", "corr-" + idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private static String validLimitOrderJson() {
        return """
            {
              "clientOrderId": "CLIENT-123",
              "accountId": "ACC-001",
              "symbol": "AAPL",
              "side": "BUY",
              "type": "LIMIT",
              "quantity": 100,
              "limitPrice": 185.50
            }
            """;
    }

    private void assertOutboxEventCreated(String orderId, String correlationId) {
        assertThat(outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(orderId))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.getAggregateType()).isEqualTo("ORDER");
                assertThat(event.getEventType()).isEqualTo("OrderSubmittedEvent");
                assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
                assertThat(event.getCorrelationId()).isEqualTo(correlationId);
                assertThat(event.getPayload()).contains("\"orderId\":\"" + orderId + "\"");
            });
    }
}
