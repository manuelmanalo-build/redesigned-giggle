package com.realtimetradeprocessing.simulator.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Price(BigDecimal amount) {

    public Price {
        Objects.requireNonNull(amount, "Price amount must not be null");
        if (amount.signum() <= 0) {
            throw new DomainException("Price must be positive");
        }
    }

    public static Price of(BigDecimal amount) {
        return new Price(amount);
    }

    public static Price of(String amount) {
        return new Price(new BigDecimal(amount));
    }
}

