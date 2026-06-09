package com.realtimetradeprocessing.simulator.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ValueObjectTest {

    @Test
    void createsValidIdentifiersAndNormalizesSymbol() {
        assertThat(OrderId.of("order-1").value()).isEqualTo("order-1");
        assertThat(AccountId.of("account-1").value()).isEqualTo("account-1");
        assertThat(InstrumentSymbol.of(" aapl ").value()).isEqualTo("AAPL");
        assertThat(ExecutionReportId.of("exec-1").value()).isEqualTo("exec-1");
        assertThat(TradeId.of("trade-1").value()).isEqualTo("trade-1");
    }

    @Test
    void rejectsBlankIdentifiersAndSymbols() {
        assertThatThrownBy(() -> OrderId.of(" "))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Order ID must not be blank");

        assertThatThrownBy(() -> AccountId.of(""))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Account ID must not be blank");

        assertThatThrownBy(() -> InstrumentSymbol.of(null))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Instrument symbol must not be blank");
    }

    @Test
    void quantityMustBePositive() {
        assertThat(Quantity.of(1).value()).isEqualTo(1);

        assertThatThrownBy(() -> Quantity.of(0))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Quantity must be positive");

        assertThatThrownBy(() -> Quantity.of(-1))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Quantity must be positive");
    }

    @Test
    void priceMustBePositive() {
        assertThat(Price.of("10.50").amount()).isEqualByComparingTo(new BigDecimal("10.50"));

        assertThatThrownBy(() -> Price.of(BigDecimal.ZERO))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Price must be positive");

        assertThatThrownBy(() -> Price.of(new BigDecimal("-1.00")))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Price must be positive");
    }
}

