package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;

public record TradeResponse(
    String tradeId,
    String orderId,
    String executionReportId,
    String accountId,
    String symbol,
    OrderSide side,
    long quantity,
    BigDecimal price,
    Instant createdAt
) {

    public static TradeResponse fromEntity(TradeEntity trade) {
        return new TradeResponse(
            trade.getId(),
            trade.getOrderId(),
            trade.getExecutionReportId(),
            trade.getAccountId(),
            trade.getSymbol(),
            trade.getSide(),
            trade.getQuantity(),
            trade.getPrice(),
            trade.getCreatedAt()
        );
    }
}
