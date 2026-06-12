package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplaceOrderRequest(
    @NotNull @Min(1) Long newQuantity,
    @DecimalMin(value = "0.0", inclusive = false) BigDecimal newLimitPrice,
    @Size(max = 500) String reason
) {
}
