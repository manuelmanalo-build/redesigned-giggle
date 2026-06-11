package com.realtimetradeprocessing.simulator.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.realtimetradeprocessing.simulator.domain.ExecutionReport;
import com.realtimetradeprocessing.simulator.domain.ExecutionReportId;
import com.realtimetradeprocessing.simulator.domain.Order;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;
import com.realtimetradeprocessing.simulator.domain.Trade;
import com.realtimetradeprocessing.simulator.domain.TradeId;
import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.IdempotencyRecordEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.IdempotencyRecordJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CorePersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("trade_simulator_test")
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
    private OrderJpaRepository orderRepository;

    @Autowired
    private ExecutionReportJpaRepository executionReportRepository;

    @Autowired
    private TradeJpaRepository tradeRepository;

    @Autowired
    private IdempotencyRecordJpaRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesAndFindsOrder() {
        Instant now = Instant.parse("2026-06-09T17:00:00Z");
        Order order = Order.limit(
            OrderId.of("order-save-find"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("aapl"),
            OrderSide.BUY,
            Quantity.of(100),
            Price.of("185.2500")
        ).accept();

        orderRepository.saveAndFlush(OrderEntity.fromDomain(order, "client-order-1", 0, now));

        OrderEntity found = orderRepository.findById("order-save-find").orElseThrow();
        assertThat(found.getClientOrderId()).isEqualTo("client-order-1");
        assertThat(found.getAccountId()).isEqualTo("account-1");
        assertThat(found.getSymbol()).isEqualTo("AAPL");
        assertThat(found.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(found.getLimitPrice()).isEqualByComparingTo(new BigDecimal("185.2500"));
        assertThat(found.toDomain()).isEqualTo(order);
    }

    @Test
    void persistsExecutionReportForOrder() {
        Instant now = Instant.parse("2026-06-09T17:05:00Z");
        saveAcceptedMarketOrder("order-execution-report", now);
        ExecutionReport report = ExecutionReport.partialFill(
            ExecutionReportId.of("exec-report-1"),
            OrderId.of("order-execution-report"),
            Quantity.of(40),
            Price.of("101.2500")
        );

        executionReportRepository.saveAndFlush(ExecutionReportEntity.fromDomain(report, now));

        assertThat(executionReportRepository.findByOrderIdOrderByCreatedAtAsc("order-execution-report"))
            .singleElement()
            .satisfies(found -> {
                assertThat(found.getId()).isEqualTo("exec-report-1");
                assertThat(found.getOrderId()).isEqualTo("order-execution-report");
                assertThat(found.getOrderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
                assertThat(found.getExecutedQuantity()).isEqualTo(40);
                assertThat(found.getExecutionPrice()).isEqualByComparingTo(new BigDecimal("101.2500"));
                assertThat(found.toDomain()).isEqualTo(report);
            });
    }

    @Test
    void persistsTradeForOrder() {
        Instant now = Instant.parse("2026-06-09T17:10:00Z");
        saveAcceptedMarketOrder("order-trade", now);
        ExecutionReport report = ExecutionReport.fill(
            ExecutionReportId.of("exec-trade-1"),
            OrderId.of("order-trade"),
            Quantity.of(100),
            Price.of("101.2500")
        );
        executionReportRepository.saveAndFlush(ExecutionReportEntity.fromDomain(report, now));
        Trade trade = Trade.fromExecutionReport(
            TradeId.of("trade-1"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            report
        );

        tradeRepository.saveAndFlush(TradeEntity.fromDomain(trade, now));

        assertThat(tradeRepository.findByOrderIdOrderByCreatedAtAsc("order-trade"))
            .singleElement()
            .satisfies(found -> {
                assertThat(found.getId()).isEqualTo("trade-1");
                assertThat(found.getOrderId()).isEqualTo("order-trade");
                assertThat(found.getExecutionReportId()).isEqualTo("exec-trade-1");
                assertThat(found.getAccountId()).isEqualTo("account-1");
                assertThat(found.getSymbol()).isEqualTo("AAPL");
                assertThat(found.getQuantity()).isEqualTo(100);
                assertThat(found.getPrice()).isEqualByComparingTo(new BigDecimal("101.2500"));
                assertThat(found.toDomain()).isEqualTo(trade);
            });
    }

    @Test
    void enforcesOneTradePerExecutionReport() {
        Instant now = Instant.parse("2026-06-09T17:12:00Z");
        saveAcceptedMarketOrder("order-trade-unique-report", now);
        ExecutionReport report = ExecutionReport.fill(
            ExecutionReportId.of("exec-trade-unique-report"),
            OrderId.of("order-trade-unique-report"),
            Quantity.of(100),
            Price.of("101.2500")
        );
        executionReportRepository.saveAndFlush(ExecutionReportEntity.fromDomain(report, now));
        Trade trade = Trade.fromExecutionReport(
            TradeId.of("trade-unique-report-1"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            report
        );
        tradeRepository.saveAndFlush(TradeEntity.fromDomain(trade, now));

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                INSERT INTO trades (
                    id,
                    order_id,
                    execution_report_id,
                    account_id,
                    symbol,
                    side,
                    quantity,
                    price,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "trade-unique-report-2",
            "order-trade-unique-report",
            "exec-trade-unique-report",
            "account-1",
            "AAPL",
            "BUY",
            100,
            new BigDecimal("101.2500"),
            Timestamp.from(now)
        ))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueIdempotencyKey() {
        Instant now = Instant.parse("2026-06-09T17:15:00Z");
        saveAcceptedMarketOrder("order-idempotency", now);
        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecordEntity(
            "idem-key-1",
            "request-hash-1",
            "order-idempotency",
            202,
            now
        ));

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                INSERT INTO idempotency_records (
                    idempotency_key,
                    request_hash,
                    order_id,
                    response_status,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?)
                """,
            "idem-key-1",
            "request-hash-2",
            "order-idempotency",
            409,
            Timestamp.from(now)
        ))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMarketOrderWithLimitPriceAtDatabaseLevel() {
        Instant now = Instant.parse("2026-06-09T17:20:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                INSERT INTO orders (
                    id, client_order_id, account_id, symbol, side, type, status,
                    quantity, limit_price, filled_quantity, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "order-market-with-price",
            "client-market-with-price",
            "account-1",
            "AAPL",
            "BUY",
            "MARKET",
            "ACCEPTED",
            100,
            new BigDecimal("10.00"),
            0,
            Timestamp.from(now),
            Timestamp.from(now)
        ))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsLimitOrderWithoutLimitPriceAtDatabaseLevel() {
        Instant now = Instant.parse("2026-06-09T17:21:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                INSERT INTO orders (
                    id, client_order_id, account_id, symbol, side, type, status,
                    quantity, limit_price, filled_quantity, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "order-limit-without-price",
            "client-limit-without-price",
            "account-1",
            "AAPL",
            "BUY",
            "LIMIT",
            "ACCEPTED",
            100,
            null,
            0,
            Timestamp.from(now),
            Timestamp.from(now)
        ))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesAllowedEnumValues() {
        Instant now = Instant.parse("2026-06-09T17:25:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
                INSERT INTO orders (
                    id, client_order_id, account_id, symbol, side, type, status,
                    quantity, limit_price, filled_quantity, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "order-invalid-side",
            "client-invalid-side",
            "account-1",
            "AAPL",
            "HOLD",
            "MARKET",
            "ACCEPTED",
            100,
            null,
            0,
            Timestamp.from(now),
            Timestamp.from(now)
        ))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void saveAcceptedMarketOrder(String orderId, Instant now) {
        Order order = Order.market(
            OrderId.of(orderId),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            Quantity.of(100)
        ).accept();
        orderRepository.saveAndFlush(OrderEntity.fromDomain(order, "client-" + orderId, 0, now));
    }
}
