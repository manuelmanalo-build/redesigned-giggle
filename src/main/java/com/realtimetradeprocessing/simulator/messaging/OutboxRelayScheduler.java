package com.realtimetradeprocessing.simulator.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "trade.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelayService outboxRelayService;

    public OutboxRelayScheduler(OutboxRelayService outboxRelayService) {
        this.outboxRelayService = outboxRelayService;
    }

    @Scheduled(fixedDelayString = "${trade.outbox.relay-interval-ms:1000}")
    void publishDueEvents() {
        outboxRelayService.publishDueEvents();
    }
}
