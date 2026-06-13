package com.realtimetradeprocessing.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.IdempotencyRecordJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.AccountJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.InstrumentJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OutboxEventJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.ProcessedMessageJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;
import com.realtimetradeprocessing.simulator.messaging.OrderEventPublisher;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration",
    "trade.messaging.jms-listener-enabled=false",
    "trade.outbox.scheduling-enabled=false"
})
@AutoConfigureMockMvc
class RealtimeTradeProcessingSimulatorApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderJpaRepository orderRepository;

    @MockBean
    private ExecutionReportJpaRepository executionReportRepository;

    @MockBean
    private TradeJpaRepository tradeRepository;

    @MockBean
    private IdempotencyRecordJpaRepository idempotencyRecordRepository;

    @MockBean
    private AccountJpaRepository accountRepository;

    @MockBean
    private InstrumentJpaRepository instrumentRepository;

    @MockBean
    private OutboxEventJpaRepository outboxEventRepository;

    @MockBean
    private ProcessedMessageJpaRepository processedMessageRepository;

    @MockBean
    private OrderEventPublisher orderEventPublisher;

    @Test
    void contextLoads() {
    }

    @Test
    void exposesOpenApiDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").exists())
            .andExpect(jsonPath("$.info.title").value("Realtime Trade Processing Simulator API"))
            .andExpect(jsonPath("$.paths['/api/v1/orders']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/cancel']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/replace']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/execution-reports']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/trades']").exists())
            .andExpect(jsonPath("$.paths['/actuator/health']").exists())
            .andExpect(jsonPath("$.components.schemas.ApiErrorResponse").exists());
    }

    @Test
    void exposesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection());
    }
}
