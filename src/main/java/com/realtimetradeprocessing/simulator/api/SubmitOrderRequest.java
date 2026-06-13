package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to submit a new order for validation, persistence, outbox publication, and asynchronous execution.")
public record SubmitOrderRequest(
    @Schema(example = "CLIENT-123", description = "Client-provided order identifier.")
    @NotBlank @Size(max = 128) String clientOrderId,
    @Schema(example = "ACC-001", description = "Trading account identifier. Must reference an ACTIVE account.")
    @NotBlank @Size(max = 128) String accountId,
    @Schema(example = "AAPL", description = "Instrument symbol. Must reference an ACTIVE instrument.")
    @NotBlank @Size(max = 32) String symbol,
    @Schema(example = "BUY", description = "Order side.")
    @NotNull OrderSide side,
    @Schema(example = "LIMIT", description = "Order type.")
    @NotNull OrderType type,
    @Schema(example = "100", description = "Positive order quantity.")
    @NotNull @Positive Long quantity,
    @Schema(example = "185.50", description = "Required for LIMIT orders and omitted for MARKET orders.")
    @Positive BigDecimal limitPrice
) {
}
