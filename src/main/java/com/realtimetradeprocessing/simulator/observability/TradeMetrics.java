package com.realtimetradeprocessing.simulator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

@Component
public class TradeMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter orderSubmissions;
    private final Counter orderRejections;
    private final Counter executionReportsCreated;
    private final Counter tradesCreated;
    private final Counter messageProcessingFailures;
    private final Timer messageProcessingDuration;

    public TradeMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.orderSubmissions = Counter.builder("trade.orders.submitted")
            .description("Accepted orders submitted through the REST API")
            .register(meterRegistry);
        this.orderRejections = Counter.builder("trade.orders.rejected")
            .description("Order submissions rejected before acceptance")
            .register(meterRegistry);
        this.executionReportsCreated = Counter.builder("trade.execution_reports.created")
            .description("Execution reports created by asynchronous processing")
            .register(meterRegistry);
        this.tradesCreated = Counter.builder("trade.trades.created")
            .description("Trades created by asynchronous processing")
            .register(meterRegistry);
        this.messageProcessingFailures = Counter.builder("trade.messages.processing.failures")
            .description("Failures while processing inbound JMS messages")
            .register(meterRegistry);
        this.messageProcessingDuration = Timer.builder("trade.messages.processing.duration")
            .description("Duration of inbound order-submitted message processing")
            .publishPercentileHistogram(false)
            .register(meterRegistry);
    }

    public void orderSubmitted() {
        orderSubmissions.increment();
    }

    public void orderRejected() {
        orderRejections.increment();
    }

    public void executionReportCreated() {
        executionReportsCreated.increment();
    }

    public void tradeCreated() {
        tradesCreated.increment();
    }

    public void messageProcessingFailed() {
        messageProcessingFailures.increment();
    }

    public Timer.Sample startMessageProcessing() {
        return Timer.start(meterRegistry);
    }

    public void stopMessageProcessing(Timer.Sample sample) {
        sample.stop(messageProcessingDuration);
    }
}
