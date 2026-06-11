package com.realtimetradeprocessing.simulator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@SpringBootTest(properties = {
    "trade.execution.simulated-market-price=100.00",
    "spring.jms.listener.concurrency=1",
    "spring.jms.listener.max-concurrency=1"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderJmsEndToEndIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("trade_simulator_jms_e2e_test")
        .withUsername("trade_user")
        .withPassword("trade_password");

    @Container
    static final GenericContainer<?> ARTEMIS = new GenericContainer<>(
        DockerImageName.parse("apache/activemq-artemis:2.33.0")
    )
        .withEnv("ARTEMIS_USER", "artemis")
        .withEnv("ARTEMIS_PASSWORD", "artemis")
        .withEnv("ANONYMOUS_LOGIN", "false")
        .withExposedPorts(61616)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofSeconds(90));

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.artemis.broker-url", () -> "tcp://localhost:" + ARTEMIS.getMappedPort(61616));
        registry.add("spring.artemis.user", () -> "artemis");
        registry.add("spring.artemis.password", () -> "artemis");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderJpaRepository orderRepository;

    @Autowired
    private ExecutionReportJpaRepository executionReportRepository;

    @Autowired
    private TradeJpaRepository tradeRepository;

    @Test
    void submittedMarketOrderIsConsumedAndFilledThroughArtemis() throws Exception {
        JsonNode response = objectMapper.readTree(mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", "idem-jms-e2e-market")
                .header("X-Correlation-Id", "corr-jms-e2e-market")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientOrderId": "CLIENT-JMS-E2E-1",
                      "accountId": "ACC-001",
                      "symbol": "AAPL",
                      "side": "BUY",
                      "type": "MARKET",
                      "quantity": 100
                    }
                    """))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
        String orderId = response.get("orderId").asText();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).hasSize(1);
            assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc(orderId)).hasSize(1);
        });

        List<ExecutionReportEntity> reports = executionReportRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        List<TradeEntity> trades = tradeRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        assertThat(trades.getFirst().getExecutionReportId()).isEqualTo(reports.getFirst().getId());
    }
}
