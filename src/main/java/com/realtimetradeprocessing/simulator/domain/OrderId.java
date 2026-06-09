package com.realtimetradeprocessing.simulator.domain;

public record OrderId(String value) {

    public OrderId {
        value = Text.requireNonBlank(value, "Order ID must not be blank");
    }

    public static OrderId of(String value) {
        return new OrderId(value);
    }
}

