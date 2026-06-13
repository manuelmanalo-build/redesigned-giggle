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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Validated
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order submission, lifecycle operations, and order-specific reads.")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @Operation(
        summary = "Submit a new order",
        description = "Validates reference data, persists an accepted order, stores the idempotency response snapshot, and writes a transactional outbox event for asynchronous execution.",
        parameters = {
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = "idem-CLIENT-123", description = "Deduplicates equivalent submit retries. Max 128 characters."),
            @Parameter(name = "X-Correlation-Id", in = ParameterIn.HEADER, example = "corr-CLIENT-123", description = "Optional caller-provided correlation ID.")
        },
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = SubmitOrderRequest.class),
                examples = @ExampleObject(name = "Limit order", value = """
                    {
                      "clientOrderId": "CLIENT-123",
                      "accountId": "ACC-001",
                      "symbol": "AAPL",
                      "side": "BUY",
                      "type": "LIMIT",
                      "quantity": 100,
                      "limitPrice": 185.50
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Order accepted and persisted.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request, validation failure, or inactive/missing reference data.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "409", description = "Idempotency key conflict.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PostMapping
    ResponseEntity<OrderResponse> submitOrder(
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
        @Parameter(hidden = true)
        @RequestAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) String correlationId,
        @Valid @RequestBody SubmitOrderRequest request
    ) {
        OrderSubmissionResult result = orderApplicationService.submitOrder(request, idempotencyKey, correlationId);
        return ResponseEntity.status(result.responseStatus()).body(result.order());
    }

    @Operation(
        summary = "Cancel an open order",
        description = "Cancels an ACCEPTED or PARTIALLY_FILLED order, preserves existing fills, and writes a CANCELLED execution report.",
        parameters = {
            @Parameter(name = "orderId", in = ParameterIn.PATH, required = true, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f"),
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = "idem-cancel-CLIENT-123", description = "Deduplicates equivalent cancel retries. Max 128 characters."),
            @Parameter(name = "X-Correlation-Id", in = ParameterIn.HEADER, example = "corr-cancel-CLIENT-123", description = "Optional caller-provided correlation ID.")
        },
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = CancelOrderRequest.class),
                examples = @ExampleObject(name = "Cancel request", value = """
                    {
                      "reason": "Client requested cancel"
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order cancelled.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or validation failure.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "404", description = "Order not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "409", description = "Idempotency conflict or invalid lifecycle state.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PostMapping("/{orderId}/cancel")
    ResponseEntity<OrderResponse> cancelOrder(
        @PathVariable String orderId,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
        @Valid @RequestBody CancelOrderRequest request
    ) {
        OrderSubmissionResult result = orderApplicationService.cancelOrder(orderId, request, idempotencyKey);
        return ResponseEntity.status(result.responseStatus()).body(result.order());
    }

    @Operation(
        summary = "Replace an open limit order",
        description = "Amends an ACCEPTED or PARTIALLY_FILLED limit order in place, writes a REPLACED execution report, and re-evaluates accepted orders asynchronously through the outbox.",
        parameters = {
            @Parameter(name = "orderId", in = ParameterIn.PATH, required = true, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f"),
            @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true, example = "idem-replace-CLIENT-123", description = "Deduplicates equivalent replace retries. Max 128 characters."),
            @Parameter(name = "X-Correlation-Id", in = ParameterIn.HEADER, example = "corr-replace-CLIENT-123", description = "Optional caller-provided correlation ID.")
        },
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = ReplaceOrderRequest.class),
                examples = @ExampleObject(name = "Replace request", value = """
                    {
                      "newQuantity": 150,
                      "newLimitPrice": 186.25,
                      "reason": "Client amended order"
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order replaced.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or validation failure.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "404", description = "Order not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "409", description = "Idempotency conflict or invalid lifecycle state.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PostMapping("/{orderId}/replace")
    ResponseEntity<OrderResponse> replaceOrder(
        @PathVariable String orderId,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
        @Parameter(hidden = true)
        @RequestAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) String correlationId,
        @Valid @RequestBody ReplaceOrderRequest request
    ) {
        OrderSubmissionResult result = orderApplicationService.replaceOrder(orderId, request, idempotencyKey, correlationId);
        return ResponseEntity.status(result.responseStatus()).body(result.order());
    }

    @Operation(
        summary = "Get order by ID",
        parameters = @Parameter(name = "orderId", in = ParameterIn.PATH, required = true, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order found.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = OrderResponse.class))),
        @ApiResponse(responseCode = "404", description = "Order not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/{orderId}")
    OrderResponse getOrder(@PathVariable String orderId) {
        return orderApplicationService.getOrder(orderId);
    }

    @Operation(
        summary = "List execution reports for an order",
        parameters = @Parameter(name = "orderId", in = ParameterIn.PATH, required = true, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Execution reports for the order.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(array = @ArraySchema(schema = @Schema(implementation = ExecutionReportResponse.class)))),
        @ApiResponse(responseCode = "404", description = "Order not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/{orderId}/execution-reports")
    List<ExecutionReportResponse> getExecutionReports(@PathVariable String orderId) {
        return orderApplicationService.getExecutionReports(orderId);
    }

    @Operation(
        summary = "List trades for an order",
        parameters = @Parameter(name = "orderId", in = ParameterIn.PATH, required = true, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Trades for the order.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(array = @ArraySchema(schema = @Schema(implementation = TradeResponse.class)))),
        @ApiResponse(responseCode = "404", description = "Order not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/{orderId}/trades")
    List<TradeResponse> getTrades(@PathVariable String orderId) {
        return orderApplicationService.getTrades(orderId);
    }
}
