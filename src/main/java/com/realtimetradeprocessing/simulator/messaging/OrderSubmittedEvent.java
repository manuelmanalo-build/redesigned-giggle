package com.realtimetradeprocessing.simulator.messaging;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;

public record OrderSubmittedEvent(
    String eventId,
    String orderId,
    String clientOrderId,
    String accountId,
    String symbol,
    OrderSide side,
    OrderType type,
    long quantity,
    BigDecimal limitPrice,
    String correlationId,
    Instant createdAt
) {
    public static final String EVENT_TYPE = "OrderSubmittedEvent";

    public String eventType() {
        return EVENT_TYPE;
    }
}
