package com.realtimetradeprocessing.simulator.api;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.realtimetradeprocessing.simulator.application.SearchApplicationService;
import com.realtimetradeprocessing.simulator.domain.ExecutionType;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Validated
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchApplicationService searchApplicationService;

    public SearchController(SearchApplicationService searchApplicationService) {
        this.searchApplicationService = searchApplicationService;
    }

    @GetMapping("/orders")
    PageResponse<OrderResponse> searchOrders(
        @RequestParam(required = false) String accountId,
        @RequestParam(required = false) String symbol,
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) OrderSide side,
        @RequestParam(required = false) OrderType type,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
        @RequestParam(required = false) String clientOrderId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDirection
    ) {
        return searchApplicationService.searchOrders(
            accountId,
            symbol,
            status,
            side,
            type,
            createdFrom,
            createdTo,
            clientOrderId,
            page,
            size,
            sortBy,
            sortDirection
        );
    }

    @GetMapping("/execution-reports")
    PageResponse<ExecutionReportResponse> searchExecutionReports(
        @RequestParam(required = false) String orderId,
        @RequestParam(required = false) ExecutionType executionType,
        @RequestParam(required = false) OrderStatus orderStatus,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDirection
    ) {
        return searchApplicationService.searchExecutionReports(
            orderId,
            executionType,
            orderStatus,
            createdFrom,
            createdTo,
            page,
            size,
            sortBy,
            sortDirection
        );
    }

    @GetMapping("/trades")
    PageResponse<TradeResponse> searchTrades(
        @RequestParam(required = false) String orderId,
        @RequestParam(required = false) String accountId,
        @RequestParam(required = false) String symbol,
        @RequestParam(required = false) OrderSide side,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDirection
    ) {
        return searchApplicationService.searchTrades(
            orderId,
            accountId,
            symbol,
            side,
            createdFrom,
            createdTo,
            page,
            size,
            sortBy,
            sortDirection
        );
    }
}
