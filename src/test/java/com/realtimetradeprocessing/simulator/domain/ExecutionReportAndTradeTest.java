package com.realtimetradeprocessing.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExecutionReportAndTradeTest {

    @Test
    void createsAcceptedExecutionReport() {
        ExecutionReport report = ExecutionReport.accepted(
            ExecutionReportId.of("exec-1"),
            OrderId.of("order-1")
        );

        assertThat(report.executionType()).isEqualTo(ExecutionType.ACCEPTED);
        assertThat(report.orderStatus()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(report.lastQuantity()).isEmpty();
        assertThat(report.lastPrice()).isEmpty();
    }

    @Test
    void createsRejectedExecutionReportWithMessage() {
        ExecutionReport report = ExecutionReport.rejected(
            ExecutionReportId.of("exec-2"),
            OrderId.of("order-1"),
            "limit price not marketable"
        );

        assertThat(report.executionType()).isEqualTo(ExecutionType.REJECTED);
        assertThat(report.orderStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(report.message()).contains("limit price not marketable");
    }

    @Test
    void rejectedExecutionReportRequiresMessage() {
        assertThatThrownBy(() -> ExecutionReport.rejected(
            ExecutionReportId.of("exec-3"),
            OrderId.of("order-1"),
            " "
        ))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Rejected execution reports require a message");
    }

    @Test
    void createsFillExecutionReportAndTrade() {
        ExecutionReport report = ExecutionReport.fill(
            ExecutionReportId.of("exec-4"),
            OrderId.of("order-1"),
            Quantity.of(100),
            Price.of("101.25")
        );

        Trade trade = Trade.fromExecutionReport(
            TradeId.of("trade-1"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            report
        );

        assertThat(report.executionType()).isEqualTo(ExecutionType.FILL);
        assertThat(report.orderStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(trade.executionReportId()).isEqualTo(report.executionReportId());
        assertThat(trade.quantity()).isEqualTo(Quantity.of(100));
        assertThat(trade.price()).isEqualTo(Price.of("101.25"));
    }

    @Test
    void createsPartialFillExecutionReport() {
        ExecutionReport report = ExecutionReport.partialFill(
            ExecutionReportId.of("exec-5"),
            OrderId.of("order-1"),
            Quantity.of(40),
            Price.of("101.25")
        );

        assertThat(report.executionType()).isEqualTo(ExecutionType.PARTIAL_FILL);
        assertThat(report.orderStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
    }

    @Test
    void tradeRequiresFillExecutionReport() {
        ExecutionReport accepted = ExecutionReport.accepted(
            ExecutionReportId.of("exec-6"),
            OrderId.of("order-1")
        );

        assertThatThrownBy(() -> Trade.fromExecutionReport(
            TradeId.of("trade-2"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            accepted
        ))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Trade requires a fill execution report");
    }
}

