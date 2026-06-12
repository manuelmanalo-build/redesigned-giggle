package com.realtimetradeprocessing.simulator.application;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.messaging.OrderSubmittedEvent;
import com.realtimetradeprocessing.simulator.persistence.entity.ProcessedMessageEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.ProcessedMessageStatus;
import com.realtimetradeprocessing.simulator.persistence.repository.ProcessedMessageJpaRepository;

@Service
public class OrderSubmittedMessageInboxProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderSubmittedMessageInboxProcessor.class);

    static final String CONSUMER_NAME = "order-submitted-consumer";

    private final ProcessedMessageJpaRepository processedMessageRepository;
    private final OrderExecutionProcessor orderExecutionProcessor;
    private final Clock clock;

    @Autowired
    public OrderSubmittedMessageInboxProcessor(
        ProcessedMessageJpaRepository processedMessageRepository,
        OrderExecutionProcessor orderExecutionProcessor
    ) {
        this(processedMessageRepository, orderExecutionProcessor, Clock.systemUTC());
    }

    OrderSubmittedMessageInboxProcessor(
        ProcessedMessageJpaRepository processedMessageRepository,
        OrderExecutionProcessor orderExecutionProcessor,
        Clock clock
    ) {
        this.processedMessageRepository = processedMessageRepository;
        this.orderExecutionProcessor = orderExecutionProcessor;
        this.clock = clock;
    }

    @Transactional
    public void process(OrderSubmittedEvent event) {
        ProcessedMessageEntity message = claimForProcessing(event);
        if (message.isTerminal()) {
            message.markDuplicateSeen(clock.instant());
            LOGGER.info(
                "Skipping duplicate processed message messageId={} eventType={} orderId={} status={}",
                event.eventId(),
                event.eventType(),
                event.orderId(),
                message.getStatus()
            );
            return;
        }

        orderExecutionProcessor.process(event);
        message.markProcessed(clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OrderSubmittedEvent event, RuntimeException exception) {
        Instant now = clock.instant();
        ProcessedMessageEntity message = processedMessageRepository.findByMessageIdForUpdate(event.eventId())
            .orElseGet(() -> new ProcessedMessageEntity(
                event.eventId(),
                event.eventType(),
                event.orderId(),
                CONSUMER_NAME,
                ProcessedMessageStatus.RECEIVED,
                now,
                now,
                null,
                0,
                null,
                event.correlationId()
            ));

        message.markFailed(errorMessage(exception), now);
        processedMessageRepository.save(message);
        LOGGER.warn(
            "processed_message_failed messageId={} eventType={} orderId={} attemptCount={} error={}",
            event.eventId(),
            event.eventType(),
            event.orderId(),
            message.getAttemptCount(),
            message.getLastError()
        );
    }

    private ProcessedMessageEntity claimForProcessing(OrderSubmittedEvent event) {
        Instant now = clock.instant();
        int inserted = processedMessageRepository.claimReceived(
            event.eventId(),
            event.eventType(),
            event.orderId(),
            CONSUMER_NAME,
            event.correlationId(),
            now
        );
        ProcessedMessageEntity message = processedMessageRepository.findByMessageIdForUpdate(event.eventId()).orElseThrow();
        if (inserted == 0 && !message.isTerminal()) {
            message.markReceived(now);
        }
        return message;
    }

    private static String errorMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}
