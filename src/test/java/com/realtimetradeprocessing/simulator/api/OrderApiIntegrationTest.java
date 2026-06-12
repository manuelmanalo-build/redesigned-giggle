package com.realtimetradeprocessing.simulator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.messaging.OrderEventPublisher;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OutboxEventStatus;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.IdempotencyRecordJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
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

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private IdempotencyRecordJpaRepository idempotencyRecordRepository;

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
    void rejectsUnknownAccountBeforePersistence() throws Exception {
        assertReferenceDataRejection(
            "idem-api-unknown-account",
            orderJson("ACC-404", "AAPL"),
            "Unknown account: ACC-404"
        );
    }

    @Test
    void rejectsSuspendedAccountBeforePersistence() throws Exception {
        assertReferenceDataRejection(
            "idem-api-suspended-account",
            orderJson("ACC-002", "AAPL"),
            "Account is not active: ACC-002 (SUSPENDED)"
        );
    }

    @Test
    void rejectsClosedAccountBeforePersistence() throws Exception {
        assertReferenceDataRejection(
            "idem-api-closed-account",
            orderJson("ACC-003", "AAPL"),
            "Account is not active: ACC-003 (CLOSED)"
        );
    }

    @Test
    void rejectsUnknownInstrumentBeforePersistence() throws Exception {
        assertReferenceDataRejection(
            "idem-api-unknown-instrument",
            orderJson("ACC-001", "NOPE"),
            "Unknown instrument: NOPE"
        );
    }

    @Test
    void rejectsHaltedInstrumentBeforePersistence() throws Exception {
        assertReferenceDataRejection(
            "idem-api-halted-instrument",
            orderJson("ACC-001", "HALT1"),
            "Instrument is not active: HALT1 (HALTED)"
        );
    }

    @Test
    void rejectsDelistedInstrumentBeforePersistence() throws Exception {
        assertReferenceDataRejection(
            "idem-api-delisted-instrument",
            orderJson("ACC-001", "OLD1"),
            "Instrument is not active: OLD1 (DELISTED)"
        );
    }

    @Test
    void listsSeededAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].accountId").value("ACC-001"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].accountId").value("ACC-002"))
            .andExpect(jsonPath("$[1].status").value("SUSPENDED"))
            .andExpect(jsonPath("$[2].accountId").value("ACC-003"))
            .andExpect(jsonPath("$[2].status").value("CLOSED"));
    }

    @Test
    void getsSeededAccountById() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}", "ACC-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("ACC-001"))
            .andExpect(jsonPath("$.displayName").value("Demo Active Account"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void returnsNotFoundForUnknownAccount() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{accountId}", "ACC-404"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Account not found: ACC-404"));
    }

    @Test
    void createsAccountReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "ZZZ-ACC-CREATE",
                      "displayName": "Created Account",
                      "status": "ACTIVE"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountId").value("ZZZ-ACC-CREATE"))
            .andExpect(jsonPath("$.displayName").value("Created Account"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}", "ZZZ-ACC-CREATE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("ZZZ-ACC-CREATE"));
    }

    @Test
    void rejectsDuplicateAccountReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "ACC-001",
                      "displayName": "Duplicate Account",
                      "status": "ACTIVE"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
            .andExpect(jsonPath("$.message").value("Account already exists: ACC-001"));
    }

    @Test
    void updatesAccountReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "ZZZ-ACC-UPDATE",
                      "displayName": "Suspended Account",
                      "status": "SUSPENDED"
                    }
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/accounts/{accountId}", "ZZZ-ACC-UPDATE")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "displayName": "Updated Active Account",
                      "status": "ACTIVE"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("ZZZ-ACC-UPDATE"))
            .andExpect(jsonPath("$.displayName").value("Updated Active Account"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void listsSeededInstruments() throws Exception {
        mockMvc.perform(get("/api/v1/instruments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].symbol").value("AAPL"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].symbol").value("HALT1"))
            .andExpect(jsonPath("$[1].status").value("HALTED"))
            .andExpect(jsonPath("$[2].symbol").value("MSFT"))
            .andExpect(jsonPath("$[2].status").value("ACTIVE"))
            .andExpect(jsonPath("$[3].symbol").value("OLD1"))
            .andExpect(jsonPath("$[3].status").value("DELISTED"))
            .andExpect(jsonPath("$[4].symbol").value("TSLA"))
            .andExpect(jsonPath("$[4].status").value("ACTIVE"));
    }

    @Test
    void getsSeededInstrumentBySymbol() throws Exception {
        mockMvc.perform(get("/api/v1/instruments/{symbol}", "AAPL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("AAPL"))
            .andExpect(jsonPath("$.name").value("Apple Inc."))
            .andExpect(jsonPath("$.assetClass").value("EQUITY"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.tickSize").value(0.01));
    }

    @Test
    void returnsNotFoundForUnknownInstrument() throws Exception {
        mockMvc.perform(get("/api/v1/instruments/{symbol}", "NOPE"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Instrument not found: NOPE"));
    }

    @Test
    void createsInstrumentReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/instruments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "symbol": "ZZZC1",
                      "name": "Created Equity",
                      "assetClass": "EQUITY",
                      "status": "ACTIVE",
                      "tickSize": 0.01
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.symbol").value("ZZZC1"))
            .andExpect(jsonPath("$.name").value("Created Equity"))
            .andExpect(jsonPath("$.assetClass").value("EQUITY"))
            .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/instruments/{symbol}", "ZZZC1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("ZZZC1"));
    }

    @Test
    void rejectsDuplicateInstrumentReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/instruments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "symbol": "AAPL",
                      "name": "Duplicate Equity",
                      "assetClass": "EQUITY",
                      "status": "ACTIVE",
                      "tickSize": 0.01
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
            .andExpect(jsonPath("$.message").value("Instrument already exists: AAPL"));
    }

    @Test
    void updatesInstrumentReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/instruments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "symbol": "ZZZU1",
                      "name": "Halted Equity",
                      "assetClass": "EQUITY",
                      "status": "HALTED",
                      "tickSize": 0.01
                    }
                    """))
            .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/instruments/{symbol}", "ZZZU1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Updated Active Equity",
                      "assetClass": "EQUITY",
                      "status": "ACTIVE",
                      "tickSize": 0.05
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("ZZZU1"))
            .andExpect(jsonPath("$.name").value("Updated Active Equity"))
            .andExpect(jsonPath("$.assetClass").value("EQUITY"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.tickSize").value(0.05));
    }

    @Test
    void submitsOrderUsingManagedReferenceData() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "ZZZ-ACC-ORDER",
                      "displayName": "Order Account",
                      "status": "ACTIVE"
                    }
                    """))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/instruments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "symbol": "ZZZO1",
                      "name": "Order Equity",
                      "assetClass": "EQUITY",
                      "status": "ACTIVE",
                      "tickSize": 0.01
                    }
                    """))
            .andExpect(status().isCreated());

        submitOrder("idem-api-managed-reference-data", orderJson("ZZZ-ACC-ORDER", "ZZZO1"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountId").value("ZZZ-ACC-ORDER"))
            .andExpect(jsonPath("$.symbol").value("ZZZO1"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
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
    void cancelsAcceptedOrderAndCreatesExecutionReport() throws Exception {
        OrderEntity order = saveOrder("order-cancel-accepted", OrderStatus.ACCEPTED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-accepted")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "Client requested cancel"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value(order.getId()))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.filledQuantity").value(0));

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()))
            .singleElement()
            .satisfies(report -> {
                assertThat(report.getExecutionType()).isEqualTo(ExecutionType.CANCELLED);
                assertThat(report.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
                assertThat(report.getMessage()).isEqualTo("Client requested cancel");
            });
    }

    @Test
    void cancelsPartiallyFilledOrderAndPreservesFilledQuantity() throws Exception {
        OrderEntity order = saveOrder(
            "order-cancel-partial",
            OrderStatus.PARTIALLY_FILLED,
            OrderType.LIMIT,
            100,
            BigDecimal.valueOf(185.50),
            40
        );

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-partial")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.filledQuantity").value(40));

        OrderEntity cancelled = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getFilledQuantity()).isEqualTo(40);
    }

    @Test
    void rejectsCancelForFilledOrder() throws Exception {
        OrderEntity order = saveOrder("order-cancel-filled", OrderStatus.FILLED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 100);

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-filled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
            .andExpect(jsonPath("$.message").value("Order cannot be cancelled when status is FILLED"));
    }

    @Test
    void cancelReplayIsIdempotentButNewCancelForCancelledOrderFails() throws Exception {
        OrderEntity order = saveOrder("order-cancel-idempotent", OrderStatus.ACCEPTED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);
        String body = """
            {
              "reason": "Client requested cancel"
            }
            """;

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-after-cancelled")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Order cannot be cancelled when status is CANCELLED"));

        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()))
            .filteredOn(report -> report.getExecutionType() == ExecutionType.CANCELLED)
            .hasSize(1);
    }

    @Test
    void cancelReturnsConflictForSameIdempotencyKeyAndDifferentRequest() throws Exception {
        OrderEntity order = saveOrder("order-cancel-conflict", OrderStatus.ACCEPTED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "Client requested cancel"
                    }
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", order.getId())
                .header("Idempotency-Key", "idem-cancel-conflict")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reason": "Different reason"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void replacesAcceptedLimitOrderAndCreatesExecutionReport() throws Exception {
        OrderEntity order = saveOrder("order-replace-accepted", OrderStatus.ACCEPTED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);

        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", order.getId())
                .header("Idempotency-Key", "idem-replace-accepted")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "newQuantity": 150,
                      "newLimitPrice": 186.25,
                      "reason": "Client amended order"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.quantity").value(150))
            .andExpect(jsonPath("$.limitPrice").value(186.25));

        OrderEntity replaced = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(replaced.getQuantity()).isEqualTo(150);
        assertThat(replaced.getLimitPrice()).isEqualByComparingTo("186.25");
        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()))
            .singleElement()
            .satisfies(report -> {
                assertThat(report.getExecutionType()).isEqualTo(ExecutionType.REPLACED);
                assertThat(report.getOrderStatus()).isEqualTo(OrderStatus.ACCEPTED);
                assertThat(report.getMessage()).contains("Client amended order");
            });
    }

    @Test
    void replacesPartiallyFilledLimitOrderWhenQuantityCoversFill() throws Exception {
        OrderEntity order = saveOrder(
            "order-replace-partial",
            OrderStatus.PARTIALLY_FILLED,
            OrderType.LIMIT,
            100,
            BigDecimal.valueOf(185.50),
            40
        );

        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", order.getId())
                .header("Idempotency-Key", "idem-replace-partial")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "newQuantity": 60,
                      "newLimitPrice": 186.25,
                      "reason": "Client amended order"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PARTIALLY_FILLED"))
            .andExpect(jsonPath("$.quantity").value(60))
            .andExpect(jsonPath("$.filledQuantity").value(40));
    }

    @Test
    void rejectsReplaceWhenNewQuantityIsBelowFilledQuantity() throws Exception {
        OrderEntity order = saveOrder(
            "order-replace-below-filled",
            OrderStatus.PARTIALLY_FILLED,
            OrderType.LIMIT,
            100,
            BigDecimal.valueOf(185.50),
            40
        );

        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", order.getId())
                .header("Idempotency-Key", "idem-replace-below-filled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "newQuantity": 20,
                      "newLimitPrice": 186.25,
                      "reason": "Client amended order"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Replacement quantity must be greater than or equal to filled quantity"));
    }

    @Test
    void rejectsReplaceForTerminalOrMarketOrders() throws Exception {
        OrderEntity filled = saveOrder("order-replace-filled", OrderStatus.FILLED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 100);
        OrderEntity cancelled = saveOrder("order-replace-cancelled", OrderStatus.CANCELLED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);
        OrderEntity rejected = saveOrder("order-replace-rejected", OrderStatus.REJECTED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);
        OrderEntity market = saveOrder("order-replace-market", OrderStatus.ACCEPTED, OrderType.MARKET, 100, null, 0);

        assertReplaceConflict(filled.getId(), "idem-replace-filled", "Order cannot be replaced when status is FILLED");
        assertReplaceConflict(cancelled.getId(), "idem-replace-cancelled", "Order cannot be replaced when status is CANCELLED");
        assertReplaceConflict(rejected.getId(), "idem-replace-rejected", "Order cannot be replaced when status is REJECTED");
        assertReplaceConflict(market.getId(), "idem-replace-market", "Only limit orders can be replaced");
    }

    @Test
    void replaceReplayIsIdempotentAndDifferentRequestConflicts() throws Exception {
        OrderEntity order = saveOrder("order-replace-idempotent", OrderStatus.ACCEPTED, OrderType.LIMIT, 100, BigDecimal.valueOf(185.50), 0);
        String body = """
            {
              "newQuantity": 150,
              "newLimitPrice": 186.25,
              "reason": "Client amended order"
            }
            """;

        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", order.getId())
                .header("Idempotency-Key", "idem-replace-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quantity").value(150));

        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", order.getId())
                .header("Idempotency-Key", "idem-replace-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quantity").value(150));

        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", order.getId())
                .header("Idempotency-Key", "idem-replace-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "newQuantity": 175,
                      "newLimitPrice": 186.25,
                      "reason": "Client amended order"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(order.getId()))
            .filteredOn(report -> report.getExecutionType() == ExecutionType.REPLACED)
            .hasSize(1);
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
        return orderJson("ACC-001", "AAPL");
    }

    private static String orderJson(String accountId, String symbol) {
        return """
            {
              "clientOrderId": "CLIENT-123",
              "accountId": "%s",
              "symbol": "%s",
              "side": "BUY",
              "type": "LIMIT",
              "quantity": 100,
              "limitPrice": 185.50
            }
            """.formatted(accountId, symbol);
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

    private void assertReferenceDataRejection(String idempotencyKey, String body, String message) throws Exception {
        clearInvocations(orderEventPublisher);
        long orderCount = orderRepository.count();
        long idempotencyCount = idempotencyRecordRepository.count();
        long outboxCount = outboxEventRepository.count();

        submitOrder(idempotencyKey, body)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.message").value(message));

        assertThat(orderRepository.count()).isEqualTo(orderCount);
        assertThat(idempotencyRecordRepository.count()).isEqualTo(idempotencyCount);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);
        verify(orderEventPublisher, never()).publishOrderSubmitted(org.mockito.ArgumentMatchers.any());
    }

    private OrderEntity saveOrder(
        String orderId,
        OrderStatus status,
        OrderType type,
        long quantity,
        BigDecimal limitPrice,
        long filledQuantity
    ) {
        Instant now = Instant.parse("2026-06-12T12:00:00Z");
        return orderRepository.saveAndFlush(new OrderEntity(
            orderId,
            "CLIENT-" + orderId,
            "ACC-001",
            "AAPL",
            OrderSide.BUY,
            type,
            status,
            quantity,
            limitPrice,
            filledQuantity,
            now,
            now
        ));
    }

    private void assertReplaceConflict(String orderId, String idempotencyKey, String message) throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/replace", orderId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "newQuantity": 150,
                      "newLimitPrice": 186.25,
                      "reason": "Client amended order"
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
            .andExpect(jsonPath("$.message").value(message));
    }
}
