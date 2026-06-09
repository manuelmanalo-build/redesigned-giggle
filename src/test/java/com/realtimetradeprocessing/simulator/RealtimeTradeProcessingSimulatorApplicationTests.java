package com.realtimetradeprocessing.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.IdempotencyRecordJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration"
})
class RealtimeTradeProcessingSimulatorApplicationTests {

    @MockBean
    private OrderJpaRepository orderRepository;

    @MockBean
    private ExecutionReportJpaRepository executionReportRepository;

    @MockBean
    private TradeJpaRepository tradeRepository;

    @MockBean
    private IdempotencyRecordJpaRepository idempotencyRecordRepository;

    @Test
    void contextLoads() {
    }
}
