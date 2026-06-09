package com.realtimetradeprocessing.simulator.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.application.OrderExecutionProcessor;

@Component
public class OrderSubmittedEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderSubmittedEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final OrderExecutionProcessor orderExecutionProcessor;

    public OrderSubmittedEventConsumer(ObjectMapper objectMapper, OrderExecutionProcessor orderExecutionProcessor) {
        this.objectMapper = objectMapper;
        this.orderExecutionProcessor = orderExecutionProcessor;
    }

    @JmsListener(destination = "${trade.messaging.order-submitted-queue:order.submitted}")
    public void receive(String payload) {
        OrderSubmittedEvent event = deserialize(payload);
        if (event.correlationId() == null || event.correlationId().isBlank()) {
            process(event);
            return;
        }

        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", event.correlationId())) {
            process(event);
        }
    }

    private void process(OrderSubmittedEvent event) {
        LOGGER.info("Received order submitted event eventId={} orderId={}", event.eventId(), event.orderId());
        orderExecutionProcessor.process(event);
    }

    private OrderSubmittedEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, OrderSubmittedEvent.class);
        } catch (JsonProcessingException exception) {
            throw new MessageConsumptionException("Failed to deserialize order submitted event", exception);
        }
    }
}
