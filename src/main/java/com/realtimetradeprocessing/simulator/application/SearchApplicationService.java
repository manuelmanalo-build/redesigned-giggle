package com.realtimetradeprocessing.simulator.application;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.realtimetradeprocessing.simulator.api.ExecutionReportResponse;
import com.realtimetradeprocessing.simulator.api.OrderResponse;
import com.realtimetradeprocessing.simulator.api.PageResponse;
import com.realtimetradeprocessing.simulator.api.TradeResponse;
import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;
import com.realtimetradeprocessing.simulator.persistence.entity.ExecutionReportEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.OrderEntity;
import com.realtimetradeprocessing.simulator.persistence.entity.TradeEntity;
import com.realtimetradeprocessing.simulator.persistence.repository.ExecutionReportJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.OrderJpaRepository;
import com.realtimetradeprocessing.simulator.persistence.repository.TradeJpaRepository;

@Service
public class SearchApplicationService {

    private static final Set<String> ORDER_SORT_FIELDS = Set.of(
        "createdAt",
        "updatedAt",
        "clientOrderId",
        "accountId",
        "symbol",
        "status"
    );
    private static final Set<String> EXECUTION_REPORT_SORT_FIELDS = Set.of(
        "createdAt",
        "executionType",
        "orderStatus"
    );
    private static final Set<String> TRADE_SORT_FIELDS = Set.of(
        "createdAt",
        "accountId",
        "symbol",
        "side"
    );
    private static final Map<String, String> SORT_FIELD_ALIASES = Map.of(
        "created_at", "createdAt",
        "updated_at", "updatedAt",
        "client_order_id", "clientOrderId",
        "account_id", "accountId",
        "execution_type", "executionType",
        "order_status", "orderStatus"
    );

    private final OrderJpaRepository orderRepository;
    private final ExecutionReportJpaRepository executionReportRepository;
    private final TradeJpaRepository tradeRepository;

    public SearchApplicationService(
        OrderJpaRepository orderRepository,
        ExecutionReportJpaRepository executionReportRepository,
        TradeJpaRepository tradeRepository
    ) {
        this.orderRepository = orderRepository;
        this.executionReportRepository = executionReportRepository;
        this.tradeRepository = tradeRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> searchOrders(
        String accountId,
        String symbol,
        OrderStatus status,
        OrderSide side,
        OrderType type,
        Instant createdFrom,
        Instant createdTo,
        String clientOrderId,
        int page,
        int size,
        String sortBy,
        String sortDirection
    ) {
        validateDateRange(createdFrom, createdTo);
        Specification<OrderEntity> specification = Specification.allOf(
            equal("accountId", trimToNull(accountId)),
            equal("symbol", normalizeSymbol(symbol)),
            equal("status", status),
            equal("side", side),
            equal("type", type),
            equal("clientOrderId", trimToNull(clientOrderId)),
            createdRange(createdFrom, createdTo)
        );
        return PageResponse.fromPage(orderRepository.findAll(
            specification,
            pageRequest(page, size, sortBy, sortDirection, ORDER_SORT_FIELDS)
        ).map(OrderResponse::fromEntity));
    }

    @Transactional(readOnly = true)
    public PageResponse<ExecutionReportResponse> searchExecutionReports(
        String orderId,
        ExecutionType executionType,
        OrderStatus orderStatus,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size,
        String sortBy,
        String sortDirection
    ) {
        validateDateRange(createdFrom, createdTo);
        Specification<ExecutionReportEntity> specification = Specification.allOf(
            equal("orderId", trimToNull(orderId)),
            equal("executionType", executionType),
            equal("orderStatus", orderStatus),
            createdRange(createdFrom, createdTo)
        );
        return PageResponse.fromPage(executionReportRepository.findAll(
            specification,
            pageRequest(page, size, sortBy, sortDirection, EXECUTION_REPORT_SORT_FIELDS)
        ).map(ExecutionReportResponse::fromEntity));
    }

    @Transactional(readOnly = true)
    public PageResponse<TradeResponse> searchTrades(
        String orderId,
        String accountId,
        String symbol,
        OrderSide side,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size,
        String sortBy,
        String sortDirection
    ) {
        validateDateRange(createdFrom, createdTo);
        Specification<TradeEntity> specification = Specification.allOf(
            equal("orderId", trimToNull(orderId)),
            equal("accountId", trimToNull(accountId)),
            equal("symbol", normalizeSymbol(symbol)),
            equal("side", side),
            createdRange(createdFrom, createdTo)
        );
        return PageResponse.fromPage(tradeRepository.findAll(
            specification,
            pageRequest(page, size, sortBy, sortDirection, TRADE_SORT_FIELDS)
        ).map(TradeResponse::fromEntity));
    }

    private static PageRequest pageRequest(
        int page,
        int size,
        String sortBy,
        String sortDirection,
        Set<String> allowedSortFields
    ) {
        String resolvedSortBy = resolveSortBy(sortBy, allowedSortFields);
        Sort.Direction direction = resolveSortDirection(sortDirection);
        return PageRequest.of(page, size, Sort.by(direction, resolvedSortBy));
    }

    private static String resolveSortBy(String sortBy, Set<String> allowedSortFields) {
        String requested = sortBy == null || sortBy.isBlank() ? "createdAt" : sortBy.trim();
        String resolved = SORT_FIELD_ALIASES.getOrDefault(requested, requested);
        if (!allowedSortFields.contains(resolved)) {
            throw new IllegalArgumentException("Unsupported sort field: " + requested);
        }
        return resolved;
    }

    private static Sort.Direction resolveSortDirection(String sortDirection) {
        if (sortDirection == null || sortDirection.isBlank()) {
            return Sort.Direction.DESC;
        }
        String normalized = sortDirection.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ASC") && !normalized.equals("DESC")) {
            throw new IllegalArgumentException("Unsupported sort direction: " + sortDirection);
        }
        return Sort.Direction.fromString(normalized);
    }

    private static void validateDateRange(Instant createdFrom, Instant createdTo) {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("createdFrom must be before or equal to createdTo");
        }
    }

    private static <T> Specification<T> equal(String field, Object value) {
        if (value == null) {
            return null;
        }
        return (root, query, builder) -> builder.equal(root.get(field), value);
    }

    private static <T> Specification<T> createdRange(Instant createdFrom, Instant createdTo) {
        if (createdFrom == null && createdTo == null) {
            return null;
        }
        return (root, query, builder) -> {
            if (createdFrom != null && createdTo != null) {
                return builder.between(root.get("createdAt"), createdFrom, createdTo);
            }
            if (createdFrom != null) {
                return builder.greaterThanOrEqualTo(root.get("createdAt"), createdFrom);
            }
            return builder.lessThanOrEqualTo(root.get("createdAt"), createdTo);
        };
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeSymbol(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
