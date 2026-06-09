package com.realtimetradeprocessing.simulator.domain;

public record TradeId(String value) {

    public TradeId {
        value = Text.requireNonBlank(value, "Trade ID must not be blank");
    }

    public static TradeId of(String value) {
        return new TradeId(value);
    }
}

