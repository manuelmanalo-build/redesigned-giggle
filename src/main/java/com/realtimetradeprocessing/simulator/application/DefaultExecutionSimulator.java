package com.realtimetradeprocessing.simulator.application;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;

@Service
public class DefaultExecutionSimulator implements ExecutionSimulator {

    private final BigDecimal simulatedMarketPrice;

    public DefaultExecutionSimulator(
        @Value("${trade.execution.simulated-market-price:100.00}") BigDecimal simulatedMarketPrice
    ) {
        this.simulatedMarketPrice = simulatedMarketPrice;
    }

    @Override
    public ExecutionSimulation simulate(OrderEntity order) {
        if (order.getType() == OrderType.MARKET) {
            return ExecutionSimulation.filled(order.getQuantity(), simulatedMarketPrice);
        }

        if (order.getSide() == OrderSide.BUY && order.getLimitPrice().compareTo(simulatedMarketPrice) >= 0) {
            return ExecutionSimulation.filled(order.getQuantity(), simulatedMarketPrice);
        }

        if (order.getSide() == OrderSide.SELL && order.getLimitPrice().compareTo(simulatedMarketPrice) <= 0) {
            return ExecutionSimulation.filled(order.getQuantity(), simulatedMarketPrice);
        }

        return ExecutionSimulation.noFill();
    }
}
