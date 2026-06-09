package com.realtimetradeprocessing.simulator.domain;

public record AccountId(String value) {

    public AccountId {
        value = Text.requireNonBlank(value, "Account ID must not be blank");
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }
}

