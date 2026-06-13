package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Execution report or lifecycle audit record for an order.")
public record ExecutionReportResponse(
    @Schema(example = "er-123")
    String executionReportId,
    @Schema(example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f")
    String orderId,
    @Schema(example = "FILL")
    ExecutionType executionType,
    @Schema(example = "FILLED")
    OrderStatus orderStatus,
    @Schema(example = "100", nullable = true)
    Long executedQuantity,
    @Schema(example = "100.00", nullable = true)
    BigDecimal executionPrice,
    @Schema(example = "Filled at simulated market price", nullable = true)
    String message,
    @Schema(example = "2026-06-09T15:30:01Z")
    Instant createdAt
) {

    public static ExecutionReportResponse fromEntity(ExecutionReportEntity report) {
        return new ExecutionReportResponse(
            report.getId(),
            report.getOrderId(),
            report.getExecutionType(),
            report.getOrderStatus(),
            report.getExecutedQuantity(),
            report.getExecutionPrice(),
            report.getMessage(),
            report.getCreatedAt()
        );
    }
}
