package com.realtimetradeprocessing.simulator.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.ExecutionReport;
import com.realtimetradeprocessing.simulator.domain.ExecutionReportId;
import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;
import com.realtimetradeprocessing.simulator.domain.Trade;
import com.realtimetradeprocessing.simulator.domain.TradeId;
import com.realtimetradeprocessing.simulator.messaging.OrderSubmittedEvent;
import com.realtimetradeprocessing.simulator.observability.TradeMetrics;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@Service
public class OrderExecutionProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderExecutionProcessor.class);

    private final OrderJpaRepository orderRepository;
    private final ExecutionReportJpaRepository executionReportRepository;
    private final TradeJpaRepository tradeRepository;
    private final ExecutionSimulator executionSimulator;
    private final TradeMetrics tradeMetrics;
    private final Clock clock;

    @Autowired
    public OrderExecutionProcessor(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository,
        ExecutionSimulator executionSimulator,
        TradeMetrics tradeMetrics
    ) {
        this(orderRepository, executionReportRepository, tradeRepository, executionSimulator, tradeMetrics, Clock.systemUTC());
    }

    OrderExecutionProcessor(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository,
        ExecutionSimulator executionSimulator,
        TradeMetrics tradeMetrics,
        Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.executionReportRepository = executionReportRepository;
        this.tradeRepository = tradeRepository;
        this.executionSimulator = executionSimulator;
        this.tradeMetrics = tradeMetrics;
        this.clock = clock;
    }

    @Transactional
    public void process(OrderSubmittedEvent event) {
        String executionReportId = deterministicId("exec", event.eventId());
        if (executionReportRepository.existsById(executionReportId)) {
            LOGGER.info("Skipping duplicate order submitted event eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        Optional<OrderEntity> orderOptional = orderRepository.findByIdForUpdate(event.orderId());
        if (orderOptional.isEmpty()) {
            LOGGER.warn("Skipping order submitted event for missing order eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        if (executionReportRepository.existsById(executionReportId)) {
            LOGGER.info("Skipping duplicate order submitted event eventId={} orderId={}", event.eventId(), event.orderId());
            return;
        }

        OrderEntity order = orderOptional.orElseThrow();
        if (order.getStatus() != OrderStatus.ACCEPTED) {
            LOGGER.info(
                "Skipping order submitted event for non-processable order eventId={} orderId={} status={}",
                event.eventId(),
                event.orderId(),
                order.getStatus()
            );
            return;
        }

        ExecutionSimulation simulation = executionSimulator.simulate(order);
        Instant now = clock.instant();
        if (simulation.filled()) {
            recordFill(order, executionReportId, simulation, now);
            return;
        }

        recordNoFill(order, executionReportId, now);
    }

    private void recordFill(
        OrderEntity order,
        String executionReportId,
        ExecutionSimulation simulation,
        Instant now
    ) {
        ExecutionReport report = ExecutionReport.fill(
            ExecutionReportId.of(executionReportId),
            OrderId.of(order.getId()),
            Quantity.of(simulation.executedQuantity()),
            Price.of(simulation.executionPrice())
        );
        executionReportRepository.save(ExecutionReportEntity.fromDomain(report, now));
        tradeMetrics.executionReportCreated();

        Trade trade = Trade.fromExecutionReport(
            TradeId.of(deterministicId("trade", executionReportId)),
            AccountId.of(order.getAccountId()),
            InstrumentSymbol.of(order.getSymbol()),
            order.getSide(),
            report
        );
        tradeRepository.save(TradeEntity.fromDomain(trade, now));
        tradeMetrics.tradeCreated();
        order.markFilled(simulation.executedQuantity(), now);
    }

    private void recordNoFill(OrderEntity order, String executionReportId, Instant now) {
        ExecutionReport report = new ExecutionReport(
            ExecutionReportId.of(executionReportId),
            OrderId.of(order.getId()),
            ExecutionType.ACCEPTED,
            OrderStatus.ACCEPTED,
            Optional.empty(),
            Optional.empty(),
            Optional.of("No fill at simulated market price")
        );
        executionReportRepository.save(ExecutionReportEntity.fromDomain(report, now));
        tradeMetrics.executionReportCreated();
        order.touch(now);
    }

    private static String deterministicId(String prefix, String sourceId) {
        String source = sourceId == null || sourceId.isBlank() ? UUID.randomUUID().toString() : sourceId.trim();
        UUID id = UUID.nameUUIDFromBytes((prefix + ":" + source).getBytes(StandardCharsets.UTF_8));
        return prefix + "-" + id;
    }
}
