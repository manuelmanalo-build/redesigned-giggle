package com.realtimetradeprocessing.simulator.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.ExecutionReport;
import com.realtimetradeprocessing.simulator.domain.ExecutionReportId;
import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "execution_reports")
public class ExecutionReportEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_type", nullable = false, length = 32)
    private ExecutionType executionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 32)
    private OrderStatus orderStatus;

    @Column(name = "executed_quantity")
    private Long executedQuantity;

    @Column(name = "execution_price", precision = 19, scale = 4)
    private BigDecimal executionPrice;

    @Column(name = "message")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExecutionReportEntity() {
    }

    public ExecutionReportEntity(
        String id,
        String orderId,
        ExecutionType executionType,
        OrderStatus orderStatus,
        Long executedQuantity,
        BigDecimal executionPrice,
        String message,
        Instant createdAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.executionType = executionType;
        this.orderStatus = orderStatus;
        this.executedQuantity = executedQuantity;
        this.executionPrice = executionPrice;
        this.message = message;
        this.createdAt = createdAt;
    }

    public static ExecutionReportEntity fromDomain(ExecutionReport report, Instant createdAt) {
        return new ExecutionReportEntity(
            report.executionReportId().value(),
            report.orderId().value(),
            report.executionType(),
            report.orderStatus(),
            report.lastQuantity().map(Quantity::value).orElse(null),
            report.lastPrice().map(Price::amount).orElse(null),
            report.message().orElse(null),
            createdAt
        );
    }

    public ExecutionReport toDomain() {
        return new ExecutionReport(
            ExecutionReportId.of(id),
            OrderId.of(orderId),
            executionType,
            orderStatus,
            executedQuantity == null ? java.util.Optional.empty() : java.util.Optional.of(Quantity.of(executedQuantity)),
            executionPrice == null ? java.util.Optional.empty() : java.util.Optional.of(Price.of(executionPrice)),
            java.util.Optional.ofNullable(message)
        );
    }

    public String getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public ExecutionType getExecutionType() {
        return executionType;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public Long getExecutedQuantity() {
        return executedQuantity;
    }

    public BigDecimal getExecutionPrice() {
        return executionPrice;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

