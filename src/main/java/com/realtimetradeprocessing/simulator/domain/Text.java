package com.realtimetradeprocessing.simulator.domain;

final class Text {

    private Text() {
    }

    static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new DomainException(message);
        }
        return value.trim();
    }
}

