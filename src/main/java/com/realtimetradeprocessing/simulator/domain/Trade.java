package com.realtimetradeprocessing.simulator.domain;

import java.util.Objects;

public record Trade(
    TradeId tradeId,
    OrderId orderId,
    ExecutionReportId executionReportId,
    AccountId accountId,
    InstrumentSymbol symbol,
    OrderSide side,
    Quantity quantity,
    Price price
) {

    public Trade {
        Objects.requireNonNull(tradeId, "Trade ID must not be null");
        Objects.requireNonNull(orderId, "Order ID must not be null");
        Objects.requireNonNull(executionReportId, "Execution report ID must not be null");
        Objects.requireNonNull(accountId, "Account ID must not be null");
        Objects.requireNonNull(symbol, "Instrument symbol must not be null");
        Objects.requireNonNull(side, "Order side must not be null");
        Objects.requireNonNull(quantity, "Quantity must not be null");
        Objects.requireNonNull(price, "Price must not be null");
    }

    public static Trade fromExecutionReport(
        TradeId tradeId,
        AccountId accountId,
        InstrumentSymbol symbol,
        OrderSide side,
        ExecutionReport executionReport
    ) {
        Objects.requireNonNull(executionReport, "Execution report must not be null");
        if (!executionReport.isFill()) {
            throw new DomainException("Trade requires a fill execution report");
        }
        return new Trade(
            tradeId,
            executionReport.orderId(),
            executionReport.executionReportId(),
            accountId,
            symbol,
            side,
            executionReport.lastQuantity().orElseThrow(),
            executionReport.lastPrice().orElseThrow()
        );
    }
}

