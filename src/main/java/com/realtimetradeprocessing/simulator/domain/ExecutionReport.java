package com.realtimetradeprocessing.simulator.domain;

import java.util.Objects;
import java.util.Optional;

public record ExecutionReport(
    ExecutionReportId executionReportId,
    OrderId orderId,
    ExecutionType executionType,
    OrderStatus orderStatus,
    Optional<Quantity> lastQuantity,
    Optional<Price> lastPrice,
    Optional<String> message
) {

    public ExecutionReport {
        Objects.requireNonNull(executionReportId, "Execution report ID must not be null");
        Objects.requireNonNull(orderId, "Order ID must not be null");
        Objects.requireNonNull(executionType, "Execution type must not be null");
        Objects.requireNonNull(orderStatus, "Order status must not be null");
        lastQuantity = lastQuantity == null ? Optional.empty() : lastQuantity;
        lastPrice = lastPrice == null ? Optional.empty() : lastPrice;
        message = message == null ? Optional.empty() : message.map(String::trim).filter(value -> !value.isBlank());

        boolean fill = executionType == ExecutionType.FILL || executionType == ExecutionType.PARTIAL_FILL;
        if (fill && (lastQuantity.isEmpty() || lastPrice.isEmpty())) {
            throw new DomainException("Fill execution reports require quantity and price");
        }
        if (!fill && (lastQuantity.isPresent() || lastPrice.isPresent())) {
            throw new DomainException("Non-fill execution reports must not include quantity or price");
        }
        if (executionType == ExecutionType.REJECTED && message.isEmpty()) {
            throw new DomainException("Rejected execution reports require a message");
        }
    }

    public static ExecutionReport accepted(ExecutionReportId executionReportId, OrderId orderId) {
        return new ExecutionReport(
            executionReportId,
            orderId,
            ExecutionType.ACCEPTED,
            OrderStatus.ACCEPTED,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public static ExecutionReport rejected(ExecutionReportId executionReportId, OrderId orderId, String message) {
        return new ExecutionReport(
            executionReportId,
            orderId,
            ExecutionType.REJECTED,
            OrderStatus.REJECTED,
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(message)
        );
    }

    public static ExecutionReport partialFill(
        ExecutionReportId executionReportId,
        OrderId orderId,
        Quantity quantity,
        Price price
    ) {
        return new ExecutionReport(
            executionReportId,
            orderId,
            ExecutionType.PARTIAL_FILL,
            OrderStatus.PARTIALLY_FILLED,
            Optional.of(quantity),
            Optional.of(price),
            Optional.empty()
        );
    }

    public static ExecutionReport fill(
        ExecutionReportId executionReportId,
        OrderId orderId,
        Quantity quantity,
        Price price
    ) {
        return new ExecutionReport(
            executionReportId,
            orderId,
            ExecutionType.FILL,
            OrderStatus.FILLED,
            Optional.of(quantity),
            Optional.of(price),
            Optional.empty()
        );
    }

    public static ExecutionReport cancelled(ExecutionReportId executionReportId, OrderId orderId, String message) {
        return new ExecutionReport(
            executionReportId,
            orderId,
            ExecutionType.CANCELLED,
            OrderStatus.CANCELLED,
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(message)
        );
    }

    public static ExecutionReport replaced(
        ExecutionReportId executionReportId,
        OrderId orderId,
        OrderStatus orderStatus,
        String message
    ) {
        return new ExecutionReport(
            executionReportId,
            orderId,
            ExecutionType.REPLACED,
            orderStatus,
            Optional.empty(),
            Optional.empty(),
            Optional.ofNullable(message)
        );
    }

    public boolean isFill() {
        return executionType == ExecutionType.FILL || executionType == ExecutionType.PARTIAL_FILL;
    }
}
