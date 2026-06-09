package com.realtimetradeprocessing.simulator.application;

import java.math.BigDecimal;

public record ExecutionSimulation(
    boolean filled,
    long executedQuantity,
    BigDecimal executionPrice
) {

    public static ExecutionSimulation filled(long executedQuantity, BigDecimal executionPrice) {
        return new ExecutionSimulation(true, executedQuantity, executionPrice);
    }

    public static ExecutionSimulation noFill() {
        return new ExecutionSimulation(false, 0, null);
    }
}
