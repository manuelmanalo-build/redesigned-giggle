package com.realtimetradeprocessing.simulator.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.api.ExecutionReportResponse;
import com.realtimetradeprocessing.simulator.api.IdempotencyConflictException;
import com.realtimetradeprocessing.simulator.api.OrderResponse;
import com.realtimetradeprocessing.simulator.api.OrderSubmissionResult;
import com.realtimetradeprocessing.simulator.api.ResourceNotFoundException;
import com.realtimetradeprocessing.simulator.api.SubmitOrderRequest;
import com.realtimetradeprocessing.simulator.api.TradeResponse;
import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.domain.Order;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;
import com.realtimetradeprocessing.simulator.persistence.entity.IdempotencyRecordEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.IdempotencyRecordJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@Service
public class OrderApplicationService {

    private static final int CREATED = 201;

    private final OrderJpaRepository orderRepository;
    private final ExecutionReportJpaRepository executionReportRepository;
    private final TradeJpaRepository tradeRepository;
    private final IdempotencyRecordJpaRepository idempotencyRecordRepository;
    private final Clock clock;

    @Autowired
    public OrderApplicationService(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository,
        IdempotencyRecordJpaRepository idempotencyRecordRepository
    ) {
        this(orderRepository, executionReportRepository, tradeRepository, idempotencyRecordRepository, Clock.systemUTC());
    }

    OrderApplicationService(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository,
        IdempotencyRecordJpaRepository idempotencyRecordRepository,
        Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.executionReportRepository = executionReportRepository;
        this.tradeRepository = tradeRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.clock = clock;
    }

    @Transactional
    public OrderSubmissionResult submitOrder(SubmitOrderRequest request, String idempotencyKey) {
        String normalizedIdempotencyKey = requireNonBlank(idempotencyKey, "Idempotency key must not be blank");
        String requestHash = fingerprint(request);

        return idempotencyRecordRepository.findById(normalizedIdempotencyKey)
            .map(record -> replayOrConflict(record, requestHash))
            .orElseGet(() -> createAcceptedOrder(request, normalizedIdempotencyKey, requestHash));
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId) {
        return orderRepository.findById(orderId)
            .map(OrderResponse::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public List<ExecutionReportResponse> getExecutionReports(String orderId) {
        ensureOrderExists(orderId);
        return executionReportRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
            .stream()
            .map(ExecutionReportResponse::fromEntity)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<TradeResponse> getTrades(String orderId) {
        ensureOrderExists(orderId);
        return tradeRepository.findByOrderIdOrderByCreatedAtAsc(orderId)
            .stream()
            .map(TradeResponse::fromEntity)
            .toList();
    }

    private OrderSubmissionResult replayOrConflict(IdempotencyRecordEntity record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency key was already used with a different request");
        }
        String orderId = record.getOrderId();
        OrderResponse response = orderRepository.findById(orderId)
            .map(OrderResponse::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found for idempotency key: " + record.getIdempotencyKey()));
        return new OrderSubmissionResult(record.getResponseStatus(), response);
    }

    private OrderSubmissionResult createAcceptedOrder(SubmitOrderRequest request, String idempotencyKey, String requestHash) {
        Instant now = clock.instant();
        Order order = toDomainOrder(request).accept();
        OrderEntity savedOrder = orderRepository.save(OrderEntity.fromDomain(order, normalizedClientOrderId(request), 0, now));
        idempotencyRecordRepository.save(new IdempotencyRecordEntity(
            idempotencyKey,
            requestHash,
            savedOrder.getId(),
            CREATED,
            now
        ));
        return new OrderSubmissionResult(CREATED, OrderResponse.fromEntity(savedOrder));
    }

    private Order toDomainOrder(SubmitOrderRequest request) {
        Price limitPrice = request.limitPrice() == null ? null : Price.of(request.limitPrice());
        return Order.create(
            OrderId.of(UUID.randomUUID().toString()),
            AccountId.of(request.accountId()),
            InstrumentSymbol.of(request.symbol()),
            request.side(),
            request.type(),
            Quantity.of(request.quantity()),
            limitPrice
        );
    }

    private void ensureOrderExists(String orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new ResourceNotFoundException("Order not found: " + orderId);
        }
    }

    private static String fingerprint(SubmitOrderRequest request) {
        String canonical = String.join("|",
            normalizedClientOrderId(request),
            requireNonBlank(request.accountId(), "Account ID must not be blank"),
            requireNonBlank(request.symbol(), "Instrument symbol must not be blank").toUpperCase(Locale.ROOT),
            request.side().name(),
            request.type().name(),
            Long.toString(request.quantity()),
            normalizedPrice(request.limitPrice())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String normalizedClientOrderId(SubmitOrderRequest request) {
        return requireNonBlank(request.clientOrderId(), "Client order ID must not be blank");
    }

    private static String normalizedPrice(BigDecimal price) {
        if (price == null) {
            return "";
        }
        return price.stripTrailingZeros().toPlainString();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
