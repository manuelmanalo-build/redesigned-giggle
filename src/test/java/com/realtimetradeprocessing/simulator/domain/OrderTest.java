package com.realtimetradeprocessing.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void createsNewLimitOrderWithRequiredPrice() {
        Order order = Order.limit(
            OrderId.of("order-1"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            Quantity.of(100),
            Price.of("185.25")
        );

        assertThat(order.status()).isEqualTo(OrderStatus.NEW);
        assertThat(order.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(order.limitPrice()).contains(Price.of(new BigDecimal("185.25")));
    }

    @Test
    void createsNewMarketOrderWithoutPrice() {
        Order order = Order.market(
            OrderId.of("order-2"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("MSFT"),
            OrderSide.SELL,
            Quantity.of(25)
        );

        assertThat(order.status()).isEqualTo(OrderStatus.NEW);
        assertThat(order.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(order.limitPrice()).isEmpty();
    }

    @Test
    void rejectsLimitOrderWithoutPrice() {
        assertThatThrownBy(() -> Order.create(
            OrderId.of("order-3"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            OrderType.LIMIT,
            Quantity.of(100),
            null
        ))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Limit orders require price");
    }

    @Test
    void rejectsMarketOrderWithPrice() {
        assertThatThrownBy(() -> Order.create(
            OrderId.of("order-4"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            OrderType.MARKET,
            Quantity.of(100),
            Price.of("185.25")
        ))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Market orders must not include price");
    }

    @Test
    void supportsValidStateTransitions() {
        Order accepted = validMarketOrder().accept();
        Order partiallyFilled = accepted.partiallyFill();
        Order filled = partiallyFilled.fill();

        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(partiallyFilled.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(filled.status()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    void supportsRejectingNewOrder() {
        Order rejected = validMarketOrder().reject("invalid instrument");

        assertThat(rejected.status()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void supportsCancellingAcceptedAndPartiallyFilledOrders() {
        Order cancelledFromAccepted = validMarketOrder().accept().cancel();
        Order cancelledFromPartial = validMarketOrder().accept().partiallyFill().cancel();

        assertThat(cancelledFromAccepted.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledFromPartial.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void rejectsInvalidStateTransitions() {
        assertThatThrownBy(() -> validMarketOrder().fill())
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Invalid order status transition");

        assertThatThrownBy(() -> validMarketOrder().reject("bad").fill())
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Invalid order status transition");

        assertThatThrownBy(() -> validMarketOrder().accept().fill().cancel())
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Invalid order status transition");
    }

    @Test
    void originalOrderIsUnchangedWhenTransitioning() {
        Order original = validMarketOrder();
        Order accepted = original.accept();

        assertThat(original.status()).isEqualTo(OrderStatus.NEW);
        assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);
    }

    private static Order validMarketOrder() {
        return Order.market(
            OrderId.of("order-1"),
            AccountId.of("account-1"),
            InstrumentSymbol.of("AAPL"),
            OrderSide.BUY,
            Quantity.of(100)
        );
    }
}

