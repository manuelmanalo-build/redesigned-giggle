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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.api.CancelOrderRequest;
import com.realtimetradeprocessing.simulator.api.ExecutionReportResponse;
import com.realtimetradeprocessing.simulator.api.IdempotencyConflictException;
import com.realtimetradeprocessing.simulator.api.OrderResponse;
import com.realtimetradeprocessing.simulator.api.OrderSubmissionResult;
import com.realtimetradeprocessing.simulator.api.ReplaceOrderRequest;
import com.realtimetradeprocessing.simulator.api.ResourceConflictException;
import com.realtimetradeprocessing.simulator.api.ResourceNotFoundException;
import com.realtimetradeprocessing.simulator.api.SubmitOrderRequest;
import com.realtimetradeprocessing.simulator.api.TradeResponse;
import com.realtimetradeprocessing.simulator.domain.AccountId;
import com.realtimetradeprocessing.simulator.domain.ExecutionReport;
import com.realtimetradeprocessing.simulator.domain.ExecutionReportId;
import com.realtimetradeprocessing.simulator.domain.InstrumentSymbol;
import com.realtimetradeprocessing.simulator.domain.Order;
import com.realtimetradeprocessing.simulator.domain.OrderId;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.domain.Price;
import com.realtimetradeprocessing.simulator.domain.Quantity;
import com.realtimetradeprocessing.simulator.messaging.OrderSubmittedEvent;
import com.realtimetradeprocessing.simulator.observability.TradeMetrics;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.IdempotencyRecordEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.IdempotencyRecordJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@Service
public class OrderApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderApplicationService.class);
    private static final int CREATED = 201;
    private static final int OK = 200;

    private final OrderJpaRepository orderRepository;
    private final ExecutionReportJpaRepository executionReportRepository;
    private final TradeJpaRepository tradeRepository;
    private final IdempotencyRecordJpaRepository idempotencyRecordRepository;
    private final OutboxEventWriter outboxEventWriter;
    private final ReferenceDataValidationService referenceDataValidationService;
    private final TradeMetrics tradeMetrics;
    private final Clock clock;

    @Autowired
    public OrderApplicationService(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository,
        IdempotencyRecordJpaRepository idempotencyRecordRepository,
        OutboxEventWriter outboxEventWriter,
        ReferenceDataValidationService referenceDataValidationService,
        TradeMetrics tradeMetrics
    ) {
        this(
            orderRepository,
            executionReportRepository,
            tradeRepository,
            idempotencyRecordRepository,
            outboxEventWriter,
            referenceDataValidationService,
            tradeMetrics,
            Clock.systemUTC()
        );
    }

    OrderApplicationService(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository,
        IdempotencyRecordJpaRepository idempotencyRecordRepository,
        OutboxEventWriter outboxEventWriter,
        ReferenceDataValidationService referenceDataValidationService,
        TradeMetrics tradeMetrics,
        Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.executionReportRepository = executionReportRepository;
        this.tradeRepository = tradeRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.outboxEventWriter = outboxEventWriter;
        this.referenceDataValidationService = referenceDataValidationService;
        this.tradeMetrics = tradeMetrics;
        this.clock = clock;
    }

    @Transactional
    public OrderSubmissionResult submitOrder(SubmitOrderRequest request, String idempotencyKey, String correlationId) {
        String normalizedIdempotencyKey = requireNonBlank(idempotencyKey, "Idempotency key must not be blank");
        String requestHash = fingerprint(request);
        String resolvedCorrelationId = resolveCorrelationId(correlationId);
        Order acceptedOrder = toDomainOrder(request).accept();
        referenceDataValidationService.validateOrderReferenceData(acceptedOrder);
        Instant now = clock.instant();

        int claimed = idempotencyRecordRepository.claimRequest(normalizedIdempotencyKey, requestHash, CREATED, now);
        if (claimed == 0) {
            return idempotencyRecordRepository.findById(normalizedIdempotencyKey)
                .map(record -> replayOrConflict(record, requestHash))
                .orElseThrow(() -> new IllegalStateException("Idempotency claim was not visible after conflict"));
        }

        return createAcceptedOrder(request, acceptedOrder, normalizedIdempotencyKey, resolvedCorrelationId, now);
    }

    @Transactional
    public OrderSubmissionResult cancelOrder(String orderId, CancelOrderRequest request, String idempotencyKey) {
        String normalizedIdempotencyKey = requireNonBlank(idempotencyKey, "Idempotency key must not be blank");
        String requestHash = fingerprintCancel(orderId, request);
        Instant now = clock.instant();

        int claimed = idempotencyRecordRepository.claimRequest(normalizedIdempotencyKey, requestHash, OK, now);
        if (claimed == 0) {
            return idempotencyRecordRepository.findById(normalizedIdempotencyKey)
                .map(record -> replayOrConflict(record, requestHash))
                .orElseThrow(() -> new IllegalStateException("Idempotency claim was not visible after conflict"));
        }

        OrderEntity order = findOrderForUpdate(orderId);
        if (order.getStatus() != OrderStatus.ACCEPTED && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new ResourceConflictException("Order cannot be cancelled when status is " + order.getStatus());
        }

        order.toDomain().cancel();
        order.markCancelled(now);
        ExecutionReport report = ExecutionReport.cancelled(
            ExecutionReportId.of(UUID.randomUUID().toString()),
            OrderId.of(order.getId()),
            reasonOrDefault(request.reason(), "Client requested cancel")
        );
        executionReportRepository.save(ExecutionReportEntity.fromDomain(report, now));
        tradeMetrics.executionReportCreated();
        completeIdempotency(normalizedIdempotencyKey, order.getId(), OK);
        return new OrderSubmissionResult(OK, OrderResponse.fromEntity(order));
    }

    @Transactional
    public OrderSubmissionResult replaceOrder(String orderId, ReplaceOrderRequest request, String idempotencyKey) {
        String normalizedIdempotencyKey = requireNonBlank(idempotencyKey, "Idempotency key must not be blank");
        String requestHash = fingerprintReplace(orderId, request);
        Instant now = clock.instant();

        int claimed = idempotencyRecordRepository.claimRequest(normalizedIdempotencyKey, requestHash, OK, now);
        if (claimed == 0) {
            return idempotencyRecordRepository.findById(normalizedIdempotencyKey)
                .map(record -> replayOrConflict(record, requestHash))
                .orElseThrow(() -> new IllegalStateException("Idempotency claim was not visible after conflict"));
        }

        OrderEntity order = findOrderForUpdate(orderId);
        if (order.getStatus() != OrderStatus.ACCEPTED && order.getStatus() != OrderStatus.PARTIALLY_FILLED) {
            throw new ResourceConflictException("Order cannot be replaced when status is " + order.getStatus());
        }
        if (order.getType() != OrderType.LIMIT) {
            throw new ResourceConflictException("Only limit orders can be replaced");
        }
        long newQuantity = requirePositiveQuantity(request.newQuantity(), "Replacement quantity must be positive");
        if (newQuantity < order.getFilledQuantity()) {
            throw new ResourceConflictException("Replacement quantity must be greater than or equal to filled quantity");
        }

        BigDecimal replacementPrice = request.newLimitPrice() == null ? order.getLimitPrice() : request.newLimitPrice();
        Order replaced = order.toDomain().replaceLimit(Quantity.of(newQuantity), Price.of(replacementPrice));
        order.replaceLimit(replaced.quantity().value(), replaced.limitPrice().orElseThrow().amount(), now);
        ExecutionReport report = ExecutionReport.replaced(
            ExecutionReportId.of(UUID.randomUUID().toString()),
            OrderId.of(order.getId()),
            order.getStatus(),
            replaceMessage(request, order)
        );
        executionReportRepository.save(ExecutionReportEntity.fromDomain(report, now));
        tradeMetrics.executionReportCreated();
        completeIdempotency(normalizedIdempotencyKey, order.getId(), OK);
        return new OrderSubmissionResult(OK, OrderResponse.fromEntity(order));
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
            LOGGER.warn("idempotency_conflict idempotencyKey={}", record.getIdempotencyKey());
            throw new IdempotencyConflictException("Idempotency key was already used with a different request");
        }
        String orderId = record.getOrderId();
        OrderResponse response = orderRepository.findById(orderId)
            .map(OrderResponse::fromEntity)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found for idempotency key: " + record.getIdempotencyKey()));
        return new OrderSubmissionResult(record.getResponseStatus(), response);
    }

    private OrderEntity findOrderForUpdate(String orderId) {
        return orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    private void completeIdempotency(String idempotencyKey, String orderId, int responseStatus) {
        int completed = idempotencyRecordRepository.completeRequest(idempotencyKey, orderId, responseStatus);
        if (completed != 1) {
            throw new IllegalStateException("Failed to complete idempotency record: " + idempotencyKey);
        }
    }

    private OrderSubmissionResult createAcceptedOrder(
        SubmitOrderRequest request,
        Order order,
        String idempotencyKey,
        String correlationId,
        Instant now
    ) {
        OrderEntity savedOrder = orderRepository.saveAndFlush(OrderEntity.fromDomain(order, normalizedClientOrderId(request), 0, now));
        completeIdempotency(idempotencyKey, savedOrder.getId(), CREATED);
        tradeMetrics.orderSubmitted();
        LOGGER.info(
            "order_submission_accepted orderId={} clientOrderId={} accountId={} symbol={} side={} type={}",
            savedOrder.getId(),
            savedOrder.getClientOrderId(),
            savedOrder.getAccountId(),
            savedOrder.getSymbol(),
            savedOrder.getSide(),
            savedOrder.getType()
        );
        outboxEventWriter.writeOrderSubmitted(toOrderSubmittedEvent(savedOrder, correlationId, now), now);
        return new OrderSubmissionResult(CREATED, OrderResponse.fromEntity(savedOrder));
    }

    private OrderSubmittedEvent toOrderSubmittedEvent(OrderEntity order, String correlationId, Instant createdAt) {
        return new OrderSubmittedEvent(
            UUID.randomUUID().toString(),
            order.getId(),
            order.getClientOrderId(),
            order.getAccountId(),
            order.getSymbol(),
            order.getSide(),
            order.getType(),
            order.getQuantity(),
            order.getLimitPrice(),
            correlationId,
            createdAt
        );
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
        return sha256(canonical);
    }

    private static String fingerprintCancel(String orderId, CancelOrderRequest request) {
        String canonical = String.join("|",
            "cancel",
            requireNonBlank(orderId, "Order ID must not be blank"),
            reasonOrDefault(request.reason(), "Client requested cancel")
        );
        return sha256(canonical);
    }

    private static String fingerprintReplace(String orderId, ReplaceOrderRequest request) {
        String canonical = String.join("|",
            "replace",
            requireNonBlank(orderId, "Order ID must not be blank"),
            Long.toString(requirePositiveQuantity(request.newQuantity(), "Replacement quantity must be positive")),
            normalizedPrice(request.newLimitPrice()),
            reasonOrDefault(request.reason(), "Client amended order")
        );
        return sha256(canonical);
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

    private static String replaceMessage(ReplaceOrderRequest request, OrderEntity order) {
        return reasonOrDefault(request.reason(), "Client amended order")
            + "; newQuantity=" + order.getQuantity()
            + "; newLimitPrice=" + normalizedPrice(order.getLimitPrice());
    }

    private static String reasonOrDefault(String reason, String defaultReason) {
        if (reason == null || reason.isBlank()) {
            return defaultReason;
        }
        return reason.trim();
    }

    private static long requirePositiveQuantity(Long quantity, String message) {
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException(message);
        }
        return quantity;
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String resolveCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId.trim();
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
