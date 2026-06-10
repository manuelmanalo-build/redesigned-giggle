package com.realtimetradeprocessing.simulator.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.jms.TextMessage;

@Component
@Primary
@ConditionalOnBean(JmsTemplate.class)
public class JmsOrderEventPublisher implements OrderEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(JmsOrderEventPublisher.class);

    public static final String EVENT_TYPE = "OrderSubmittedEvent";

    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;
    private final String destinationName;

    public JmsOrderEventPublisher(
        JmsTemplate jmsTemplate,
        ObjectMapper objectMapper,
        @Value("${trade.messaging.order-submitted-queue:order.submitted}") String destinationName
    ) {
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
        this.destinationName = destinationName;
    }

    @Override
    public void publishOrderSubmitted(OrderSubmittedEvent event) {
        String payload = serialize(event);
        jmsTemplate.send(destinationName, session -> {
            TextMessage message = session.createTextMessage(payload);
            message.setStringProperty("eventType", EVENT_TYPE);
            message.setStringProperty("eventId", event.eventId());
            message.setStringProperty("orderId", event.orderId());
            message.setStringProperty("correlationId", event.correlationId());
            message.setJMSCorrelationID(event.correlationId());
            return message;
        });
        LOGGER.info(
            "order_submitted_event_published destination={} eventId={} orderId={} correlationId={}",
            destinationName,
            event.eventId(),
            event.orderId(),
            event.correlationId()
        );
    }

    private String serialize(OrderSubmittedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new MessagePublicationException("Failed to serialize order submitted event", exception);
        }
    }

    public String destinationName() {
        return destinationName;
    }
}
