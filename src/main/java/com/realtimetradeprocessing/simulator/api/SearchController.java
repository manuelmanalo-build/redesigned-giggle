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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Validated
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operational Search", description = "Paginated operational search endpoints for orders, execution reports, and trades.")
public class SearchController {

    private final SearchApplicationService searchApplicationService;

    public SearchController(SearchApplicationService searchApplicationService) {
        this.searchApplicationService = searchApplicationService;
    }

    @Operation(
        summary = "Search orders",
        description = "Returns a paginated order view. Filters are combined with AND. Default sort is createdAt DESC with id as a deterministic tie-breaker.",
        parameters = {
            @Parameter(name = "accountId", in = ParameterIn.QUERY, example = "ACC-001"),
            @Parameter(name = "symbol", in = ParameterIn.QUERY, example = "AAPL"),
            @Parameter(name = "status", in = ParameterIn.QUERY, example = "ACCEPTED"),
            @Parameter(name = "side", in = ParameterIn.QUERY, example = "BUY"),
            @Parameter(name = "type", in = ParameterIn.QUERY, example = "LIMIT"),
            @Parameter(name = "clientOrderId", in = ParameterIn.QUERY, example = "CLIENT-123"),
            @Parameter(name = "createdFrom", in = ParameterIn.QUERY, example = "2026-06-09T00:00:00Z"),
            @Parameter(name = "createdTo", in = ParameterIn.QUERY, example = "2026-06-10T00:00:00Z"),
            @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, example = "20", description = "Maximum 100."),
            @Parameter(name = "sortBy", in = ParameterIn.QUERY, example = "createdAt"),
            @Parameter(name = "sortDirection", in = ParameterIn.QUERY, example = "desc")
        }
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order search results.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(
            schema = @Schema(implementation = PageResponse.class),
            examples = @ExampleObject(name = "Order search", value = """
                {
                  "items": [
                    {
                      "orderId": "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f",
                      "clientOrderId": "CLIENT-123",
                      "accountId": "ACC-001",
                      "symbol": "AAPL",
                      "side": "BUY",
                      "type": "LIMIT",
                      "status": "ACCEPTED",
                      "quantity": 100,
                      "limitPrice": 185.50,
                      "filledQuantity": 0,
                      "createdAt": "2026-06-09T15:30:00Z",
                      "updatedAt": "2026-06-09T15:30:00Z"
                    }
                  ],
                  "page": 0,
                  "size": 20,
                  "totalElements": 1,
                  "totalPages": 1
                }
                """)
        )),
        @ApiResponse(responseCode = "400", description = "Invalid filter, pagination, date, enum, or sort parameter.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
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

    @Operation(
        summary = "Search execution reports",
        description = "Returns a paginated execution report view. Filters are combined with AND.",
        parameters = {
            @Parameter(name = "orderId", in = ParameterIn.QUERY, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f"),
            @Parameter(name = "executionType", in = ParameterIn.QUERY, example = "FILL"),
            @Parameter(name = "orderStatus", in = ParameterIn.QUERY, example = "FILLED"),
            @Parameter(name = "createdFrom", in = ParameterIn.QUERY, example = "2026-06-09T00:00:00Z"),
            @Parameter(name = "createdTo", in = ParameterIn.QUERY, example = "2026-06-10T00:00:00Z"),
            @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, example = "20", description = "Maximum 100."),
            @Parameter(name = "sortBy", in = ParameterIn.QUERY, example = "createdAt"),
            @Parameter(name = "sortDirection", in = ParameterIn.QUERY, example = "desc")
        }
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Execution report search results.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid filter, pagination, date, enum, or sort parameter.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
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

    @Operation(
        summary = "Search trades",
        description = "Returns a paginated trade view. Filters are combined with AND.",
        parameters = {
            @Parameter(name = "orderId", in = ParameterIn.QUERY, example = "b19a2c07-4cd8-4f39-bd2a-5a785dd4697f"),
            @Parameter(name = "accountId", in = ParameterIn.QUERY, example = "ACC-001"),
            @Parameter(name = "symbol", in = ParameterIn.QUERY, example = "AAPL"),
            @Parameter(name = "side", in = ParameterIn.QUERY, example = "BUY"),
            @Parameter(name = "createdFrom", in = ParameterIn.QUERY, example = "2026-06-09T00:00:00Z"),
            @Parameter(name = "createdTo", in = ParameterIn.QUERY, example = "2026-06-10T00:00:00Z"),
            @Parameter(name = "page", in = ParameterIn.QUERY, example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, example = "20", description = "Maximum 100."),
            @Parameter(name = "sortBy", in = ParameterIn.QUERY, example = "createdAt"),
            @Parameter(name = "sortDirection", in = ParameterIn.QUERY, example = "desc")
        }
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Trade search results.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid filter, pagination, date, enum, or sort parameter.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
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
