package com.realtimetradeprocessing.simulator.api;

import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to cancel an open order.")
public record CancelOrderRequest(
    @Schema(example = "Client requested cancel", description = "Optional cancel reason retained on the execution report.")
    @Size(max = 500) String reason
) {
}
