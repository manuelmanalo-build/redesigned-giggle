package com.realtimetradeprocessing.simulator.messaging;

public interface OrderEventPublisher {

    void publishOrderSubmitted(OrderSubmittedEvent event);
}
