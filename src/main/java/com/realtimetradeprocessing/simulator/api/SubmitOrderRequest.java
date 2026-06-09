package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;

import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SubmitOrderRequest(
    @NotBlank @Size(max = 128) String clientOrderId,
    @NotBlank @Size(max = 128) String accountId,
    @NotBlank @Size(max = 32) String symbol,
    @NotNull OrderSide side,
    @NotNull OrderType type,
    @NotNull @Positive Long quantity,
    @Positive BigDecimal limitPrice
) {
}
