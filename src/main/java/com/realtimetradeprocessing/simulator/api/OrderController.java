package com.realtimetradeprocessing.simulator.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.realtimetradeprocessing.simulator.application.OrderApplicationService;
import com.realtimetradeprocessing.simulator.observability.CorrelationIdFilter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Validated
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    ResponseEntity<OrderResponse> submitOrder(
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
        @RequestAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) String correlationId,
        @Valid @RequestBody SubmitOrderRequest request
    ) {
        OrderSubmissionResult result = orderApplicationService.submitOrder(request, idempotencyKey, correlationId);
        return ResponseEntity.status(result.responseStatus()).body(result.order());
    }

    @PostMapping("/{orderId}/cancel")
    ResponseEntity<OrderResponse> cancelOrder(
        @PathVariable String orderId,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
        @Valid @RequestBody CancelOrderRequest request
    ) {
        OrderSubmissionResult result = orderApplicationService.cancelOrder(orderId, request, idempotencyKey);
        return ResponseEntity.status(result.responseStatus()).body(result.order());
    }

    @PostMapping("/{orderId}/replace")
    ResponseEntity<OrderResponse> replaceOrder(
        @PathVariable String orderId,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
        @RequestAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) String correlationId,
        @Valid @RequestBody ReplaceOrderRequest request
    ) {
        OrderSubmissionResult result = orderApplicationService.replaceOrder(orderId, request, idempotencyKey, correlationId);
        return ResponseEntity.status(result.responseStatus()).body(result.order());
    }

    @GetMapping("/{orderId}")
    OrderResponse getOrder(@PathVariable String orderId) {
        return orderApplicationService.getOrder(orderId);
    }

    @GetMapping("/{orderId}/execution-reports")
    List<ExecutionReportResponse> getExecutionReports(@PathVariable String orderId) {
        return orderApplicationService.getExecutionReports(orderId);
    }

    @GetMapping("/{orderId}/trades")
    List<TradeResponse> getTrades(@PathVariable String orderId) {
        return orderApplicationService.getTrades(orderId);
    }
}
