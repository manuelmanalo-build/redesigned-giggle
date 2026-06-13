package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Current order state returned by write and read APIs.")
public record OrderResponse(
    @Schema(example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f")
    String orderId,
    @Schema(example = "CLIENT-123")
    String clientOrderId,
    @Schema(example = "ACC-001")
    String accountId,
    @Schema(example = "AAPL")
    String symbol,
    @Schema(example = "BUY")
    OrderSide side,
    @Schema(example = "LIMIT")
    OrderType type,
    @Schema(example = "ACCEPTED")
    OrderStatus status,
    @Schema(example = "100")
    long quantity,
    @Schema(example = "185.50", nullable = true)
    BigDecimal limitPrice,
    @Schema(example = "0")
    long filledQuantity,
    @Schema(example = "2026-06-09T15:30:00Z")
    Instant createdAt,
    @Schema(example = "2026-06-09T15:30:00Z")
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
