package com.realtimetradeprocessing.simulator.api;

import java.time.Instant;

public record ApiErrorResponse(
    Instant timestamp,
    int status,
    String errorCode,
    String message,
    String path,
    String correlationId
) {
}
