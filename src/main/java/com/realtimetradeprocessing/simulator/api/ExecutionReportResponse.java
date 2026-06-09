package com.realtimetradeprocessing.simulator.api;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;

public record ExecutionReportResponse(
    String executionReportId,
    String orderId,
    ExecutionType executionType,
    OrderStatus orderStatus,
    Long executedQuantity,
    BigDecimal executionPrice,
    String message,
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
