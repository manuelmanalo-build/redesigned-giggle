package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Booked trade created from a fill execution report.")
public record TradeResponse(
    @Schema(example = "trade-123")
    String tradeId,
    @Schema(example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f")
    String orderId,
    @Schema(example = "er-123")
    String executionReportId,
    @Schema(example = "ACC-001")
    String accountId,
    @Schema(example = "AAPL")
    String symbol,
    @Schema(example = "BUY")
    OrderSide side,
    @Schema(example = "100")
    long quantity,
    @Schema(example = "100.00")
    BigDecimal price,
    @Schema(example = "2026-06-09T15:30:01Z")
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
