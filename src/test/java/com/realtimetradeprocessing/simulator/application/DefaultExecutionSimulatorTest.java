package com.realtimetradeprocessing.simulator.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;

class DefaultExecutionSimulatorTest {

    private final DefaultExecutionSimulator simulator = new DefaultExecutionSimulator(new BigDecimal("100.00"));

    @Test
    void fillsMarketOrdersAtSimulatedMarketPrice() {
        ExecutionSimulation simulation = simulator.simulate(order(OrderSide.BUY, OrderType.MARKET, null));

        assertThat(simulation.filled()).isTrue();
        assertThat(simulation.executedQuantity()).isEqualTo(100);
        assertThat(simulation.executionPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void fillsLimitBuyWhenLimitIsAtOrAboveMarketPrice() {
        ExecutionSimulation simulation = simulator.simulate(order(OrderSide.BUY, OrderType.LIMIT, "100.00"));

        assertThat(simulation.filled()).isTrue();
        assertThat(simulation.executionPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void leavesLimitBuyAcceptedWhenLimitIsBelowMarketPrice() {
        ExecutionSimulation simulation = simulator.simulate(order(OrderSide.BUY, OrderType.LIMIT, "99.99"));

        assertThat(simulation.filled()).isFalse();
    }

    @Test
    void fillsLimitSellWhenLimitIsAtOrBelowMarketPrice() {
        ExecutionSimulation simulation = simulator.simulate(order(OrderSide.SELL, OrderType.LIMIT, "100.00"));

        assertThat(simulation.filled()).isTrue();
        assertThat(simulation.executionPrice()).isEqualByComparingTo("100.00");
    }

    @Test
    void leavesLimitSellAcceptedWhenLimitIsAboveMarketPrice() {
        ExecutionSimulation simulation = simulator.simulate(order(OrderSide.SELL, OrderType.LIMIT, "100.01"));

        assertThat(simulation.filled()).isFalse();
    }

    private static OrderEntity order(OrderSide side, OrderType type, String limitPrice) {
        Instant now = Instant.parse("2026-06-09T20:00:00Z");
        return new OrderEntity(
            "order-1",
            "CLIENT-1",
            "ACC-1",
            "AAPL",
            side,
            type,
            OrderStatus.ACCEPTED,
            100,
            limitPrice == null ? null : new BigDecimal(limitPrice),
            0,
            now,
            now
        );
    }
}
