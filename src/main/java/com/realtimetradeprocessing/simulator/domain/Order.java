package com.realtimetradeprocessing.simulator.domain;

import java.util.Objects;
import java.util.Optional;

public record Order(
    OrderId orderId,
    AccountId accountId,
    InstrumentSymbol symbol,
    OrderSide side,
    OrderType orderType,
    Quantity quantity,
    Optional<Price> limitPrice,
    OrderStatus status
) {

    public Order {
        Objects.requireNonNull(orderId, "Order ID must not be null");
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(symbol, "Instrument symbol must not be null");
        Objects.requireNonNull(side, "Order side must not be null");
        Objects.requireNonNull(orderType, "Order type must not be null");
        Objects.requireNonNull(quantity, "Quantity must not be null");
        limitPrice = limitPrice == null ? Optional.empty() : limitPrice;
        Objects.requireNonNull(status, "Order status must not be null");

        if (orderType == OrderType.LIMIT && limitPrice.isEmpty()) {
            throw new DomainException("Limit orders require price");
        }
        if (orderType == OrderType.MARKET && limitPrice.isPresent()) {
            throw new DomainException("Market orders must not include price");
        }
    }

    public static Order create(
        OrderId orderId,
        AccountId accountId,
        InstrumentSymbol symbol,
        OrderSide side,
        OrderType orderType,
        Quantity quantity,
        Price limitPrice
    ) {
        return new Order(
            orderId,
            accountId,
            symbol,
            side,
            orderType,
            quantity,
            Optional.ofNullable(limitPrice),
            OrderStatus.NEW
        );
    }

    public static Order market(
        OrderId orderId,
        AccountId accountId,
        InstrumentSymbol symbol,
        OrderSide side,
        Quantity quantity
    ) {
        return create(orderId, accountId, symbol, side, OrderType.MARKET, quantity, null);
    }

    public static Order limit(
        OrderId orderId,
        AccountId accountId,
        InstrumentSymbol symbol,
        OrderSide side,
        Quantity quantity,
        Price limitPrice
    ) {
        return create(orderId, accountId, symbol, side, OrderType.LIMIT, quantity, limitPrice);
    }

    public Order accept() {
        return transitionTo(OrderStatus.ACCEPTED);
    }

    public Order reject(String reason) {
        Text.requireNonBlank(reason, "Reject reason must not be blank");
        return transitionTo(OrderStatus.REJECTED);
    }

    public Order partiallyFill() {
        return transitionTo(OrderStatus.PARTIALLY_FILLED);
    }

    public Order fill() {
        return transitionTo(OrderStatus.FILLED);
    }

    public Order cancel() {
        return transitionTo(OrderStatus.CANCELLED);
    }

    public Order replaceLimit(Quantity newQuantity, Price newLimitPrice) {
        Objects.requireNonNull(newQuantity, "New quantity must not be null");
        Objects.requireNonNull(newLimitPrice, "New limit price must not be null");
        if (orderType != OrderType.LIMIT) {
            throw new DomainException("Only limit orders can be replaced");
        }
        if (status != OrderStatus.ACCEPTED && status != OrderStatus.PARTIALLY_FILLED) {
            throw new DomainException("Order cannot be replaced when status is " + status);
        }
        return new Order(orderId, accountId, symbol, side, orderType, newQuantity, Optional.of(newLimitPrice), status);
    }

    public Order transitionTo(OrderStatus nextStatus) {
        Objects.requireNonNull(nextStatus, "Next order status must not be null");
        if (!canTransition(status, nextStatus)) {
            throw new DomainException("Invalid order status transition: " + status + " -> " + nextStatus);
        }
        return new Order(orderId, accountId, symbol, side, orderType, quantity, limitPrice, nextStatus);
    }

    private static boolean canTransition(OrderStatus currentStatus, OrderStatus nextStatus) {
        return switch (currentStatus) {
            case NEW -> nextStatus == OrderStatus.ACCEPTED || nextStatus == OrderStatus.REJECTED;
            case ACCEPTED -> nextStatus == OrderStatus.PARTIALLY_FILLED
                || nextStatus == OrderStatus.FILLED
                || nextStatus == OrderStatus.CANCELLED;
            case PARTIALLY_FILLED -> nextStatus == OrderStatus.FILLED || nextStatus == OrderStatus.CANCELLED;
            case REJECTED, FILLED, CANCELLED -> false;
        };
    }
}
