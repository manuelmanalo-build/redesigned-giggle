package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to amend an open limit order in place.")
public record ReplaceOrderRequest(
    @Schema(example = "150", description = "New total order quantity. Must be positive and cannot be below filled quantity.")
    @NotNull @Min(1) Long newQuantity,
    @Schema(example = "186.25", description = "Optional new limit price. If omitted, the current limit price is preserved.")
    @DecimalMin(value = "0.0", inclusive = false) BigDecimal newLimitPrice,
    @Schema(example = "Client amended order", description = "Optional replace reason retained on the execution report.")
    @Size(max = 500) String reason
) {
}
