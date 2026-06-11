package com.realtimetradeprocessing.simulator.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;

import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.ExecutionReportId;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;
import com.realtimetradeprocessing.simulator.domain.Trade;
import com.realtimetradeprocessing.simulator.domain.TradeId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "trades")
public class TradeEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "execution_report_id", nullable = false, length = 64)
    private String executionReportId;

    @Column(name = "account_id", nullable = false, length = 128)
    private String accountId;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private OrderSide side;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "price", nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TradeEntity() {
    }

    public TradeEntity(
        String id,
        String orderId,
        String executionReportId,
        String accountId,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal price,
        Instant createdAt
    ) {
        this.id = id;
        this.orderId = orderId;
        this.executionReportId = executionReportId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.createdAt = createdAt;
    }

    public static TradeEntity fromDomain(Trade trade, Instant createdAt) {
        return new TradeEntity(
            trade.tradeId().value(),
            trade.orderId().value(),
            trade.executionReportId().value(),
            trade.accountId().value(),
            trade.symbol().value(),
            trade.side(),
            trade.quantity().value(),
            trade.price().amount(),
            createdAt
        );
    }

    public Trade toDomain() {
        return new Trade(
            TradeId.of(id),
            OrderId.of(orderId),
            ExecutionReportId.of(executionReportId),
            AccountId.of(accountId),
            InstrumentSymbol.of(symbol),
            side,
            Quantity.of(quantity),
            Price.of(price)
        );
    }

    public String getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getExecutionReportId() {
        return executionReportId;
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

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
