package com.realtimetradeprocessing.simulator.domain;

public record Quantity(long value) {

    public Quantity {
        if (value <= 0) {
            throw new DomainException("Quantity must be positive");
        }
    }

    public static Quantity of(long value) {
        return new Quantity(value);
    }
}

