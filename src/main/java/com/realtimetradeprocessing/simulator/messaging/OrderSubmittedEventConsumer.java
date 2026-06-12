package com.realtimetradeprocessing.simulator.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.application.OrderSubmittedMessageInboxProcessor;
import com.realtimetradeprocessing.simulator.observability.TradeMetrics;

import io.micrometer.core.instrument.Timer;

@Component
public class OrderSubmittedEventConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderSubmittedEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final OrderSubmittedMessageInboxProcessor inboxProcessor;
    private final TradeMetrics tradeMetrics;

    public OrderSubmittedEventConsumer(
        ObjectMapper objectMapper,
        OrderSubmittedMessageInboxProcessor inboxProcessor,
        TradeMetrics tradeMetrics
    ) {
        this.objectMapper = objectMapper;
        this.inboxProcessor = inboxProcessor;
        this.tradeMetrics = tradeMetrics;
    }

    @JmsListener(destination = "${trade.messaging.order-submitted-queue:order.submitted}")
    public void receive(String payload) {
        Timer.Sample sample = tradeMetrics.startMessageProcessing();
        OrderSubmittedEvent event = null;
        try {
            event = deserialize(payload);
            if (event.correlationId() == null || event.correlationId().isBlank()) {
                process(event);
                return;
            }

            try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", event.correlationId())) {
                process(event);
            }
        } catch (RuntimeException exception) {
            tradeMetrics.messageProcessingFailed();
            if (event != null) {
                inboxProcessor.recordFailure(event, exception);
            }
            LOGGER.error("Failed to process order submitted message", exception);
            throw exception;
        } finally {
            tradeMetrics.stopMessageProcessing(sample);
        }
    }

    private void process(OrderSubmittedEvent event) {
        LOGGER.info("Received order submitted event eventId={} orderId={}", event.eventId(), event.orderId());
        inboxProcessor.process(event);
    }

    private OrderSubmittedEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, OrderSubmittedEvent.class);
        } catch (JsonProcessingException exception) {
            throw new MessageConsumptionException("Failed to deserialize order submitted event", exception);
        }
    }
}
