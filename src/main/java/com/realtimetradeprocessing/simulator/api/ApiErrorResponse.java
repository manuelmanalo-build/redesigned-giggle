package com.realtimetradeprocessing.simulator.api;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Consistent API error response returned by the global exception handler.")
public record ApiErrorResponse(
    @Schema(example = "2026-06-09T15:30:00Z")
    Instant timestamp,
    @Schema(example = "400")
    int status,
    @Schema(example = "VALIDATION_ERROR")
    String errorCode,
    @Schema(example = "quantity must be greater than 0")
    String message,
    @Schema(example = "/api/v1/orders")
    String path,
    @Schema(example = "corr-123")
    String correlationId
) {
}
