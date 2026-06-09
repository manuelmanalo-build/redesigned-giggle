package com.realtimetradeprocessing.simulator.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.domain.Order;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "client_order_id", length = 128)
    private String clientOrderId;

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private OrderType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "limit_price", precision = 19, scale = 4)
    private BigDecimal limitPrice;

    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderEntity() {
    }

    public OrderEntity(
        String id,
        String clientOrderId,
        String accountId,
        String symbol,
        OrderSide side,
        OrderType type,
        OrderStatus status,
        long quantity,
        BigDecimal limitPrice,
        long filledQuantity,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.clientOrderId = clientOrderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.status = status;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.filledQuantity = filledQuantity;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static OrderEntity fromDomain(Order order, String clientOrderId, long filledQuantity, Instant now) {
        return new OrderEntity(
            order.orderId().value(),
            clientOrderId,
            order.accountId().value(),
            order.symbol().value(),
            order.side(),
            order.orderType(),
            order.status(),
            order.quantity().value(),
            order.limitPrice().map(Price::amount).orElse(null),
            filledQuantity,
            now,
            now
        );
    }

    public Order toDomain() {
        return new Order(
            OrderId.of(id),
            AccountId.of(accountId),
            InstrumentSymbol.of(symbol),
            side,
            type,
            Quantity.of(quantity),
            Optional.ofNullable(limitPrice).map(Price::of),
            status
        );
    }

    public void markFilled(long filledQuantity, Instant updatedAt) {
        this.status = OrderStatus.FILLED;
        this.filledQuantity = filledQuantity;
        this.updatedAt = updatedAt;
    }

    public void touch(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public OrderType getType() {
        return type;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getLimitPrice() {
        return limitPrice;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
