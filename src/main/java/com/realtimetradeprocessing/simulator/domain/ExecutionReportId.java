package com.realtimetradeprocessing.simulator.domain;

public record ExecutionReportId(String value) {

    public ExecutionReportId {
        value = Text.requireNonBlank(value, "Execution report ID must not be blank");
    }

    public static ExecutionReportId of(String value) {
        return new ExecutionReportId(value);
    }
}

