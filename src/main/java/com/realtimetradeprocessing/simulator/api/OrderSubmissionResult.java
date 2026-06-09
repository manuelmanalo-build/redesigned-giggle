package com.realtimetradeprocessing.simulator.api;

public record OrderSubmissionResult(int responseStatus, OrderResponse order) {
}
