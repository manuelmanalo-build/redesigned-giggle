package com.realtimetradeprocessing.simulator.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.core.MessageCreator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;

import jakarta.jms.Session;
import jakarta.jms.TextMessage;

class JmsOrderEventPublisherTest {

    @Test
    void serializesOrderSubmittedEventAsTextMessageWithMetadata() throws Exception {
        JmsTemplate jmsTemplate = mock(JmsTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JmsOrderEventPublisher publisher = new JmsOrderEventPublisher(jmsTemplate, objectMapper, "order.submitted");
        OrderSubmittedEvent event = new OrderSubmittedEvent(
            "event-1",
            "order-1",
            "CLIENT-123",
            "ACC-001",
            "AAPL",
            OrderSide.BUY,
            OrderType.LIMIT,
            100,
            new BigDecimal("185.50"),
            "corr-1",
            Instant.parse("2026-06-09T20:00:00Z")
        );

        publisher.publishOrderSubmitted(event);

        ArgumentCaptor<MessageCreator> creatorCaptor = ArgumentCaptor.forClass(MessageCreator.class);
        verify(jmsTemplate).send(eq("order.submitted"), creatorCaptor.capture());

        Session session = mock(Session.class);
        TextMessage textMessage = mock(TextMessage.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(session.createTextMessage(payloadCaptor.capture())).thenReturn(textMessage);

        creatorCaptor.getValue().createMessage(session);

        assertThat(payloadCaptor.getValue()).contains("\"eventId\":\"event-1\"");
        assertThat(payloadCaptor.getValue()).contains("\"orderId\":\"order-1\"");
        assertThat(payloadCaptor.getValue()).contains("\"correlationId\":\"corr-1\"");
        verify(textMessage).setStringProperty("eventType", JmsOrderEventPublisher.EVENT_TYPE);
        verify(textMessage).setStringProperty("eventId", "event-1");
        verify(textMessage).setStringProperty("orderId", "order-1");
        verify(textMessage).setStringProperty("correlationId", "corr-1");
        verify(textMessage).setJMSCorrelationID("corr-1");
    }
}
