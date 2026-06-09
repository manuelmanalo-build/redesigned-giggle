package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;

public record OrderResponse(
    String orderId,
    String clientOrderId,
    String accountId,
    String symbol,
    OrderSide side,
    OrderType type,
    OrderStatus status,
    long quantity,
    BigDecimal limitPrice,
    long filledQuantity,
    Instant createdAt,
    Instant updatedAt
) {

    public static OrderResponse fromEntity(OrderEntity order) {
        return new OrderResponse(
            order.getId(),
            order.getClientOrderId(),
            order.getAccountId(),
            order.getSymbol(),
            order.getSide(),
            order.getType(),
            order.getStatus(),
            order.getQuantity(),
            order.getLimitPrice(),
            order.getFilledQuantity(),
            order.getCreatedAt(),
            order.getUpdatedAt()
        );
    }
}
