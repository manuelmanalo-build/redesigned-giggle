package com.realtimetradeprocessing.simulator.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.realtimetradeprocessing.simulator.application.OrderApplicationService;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderStatus;
import com.realtimetradeprocessing.simulator.domain.OrderType;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderApplicationService orderApplicationService;

    @Test
    void rejectsOrderSubmissionWithoutIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validLimitOrderJson()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.correlationId").exists())
            .andExpect(jsonPath("$.path").value("/api/v1/orders"));
    }

    @Test
    void rejectsInvalidOrderBody() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", "idem-validation")
                .header("X-Correlation-Id", "corr-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientOrderId": "CLIENT-123",
                      "accountId": "ACC-001",
                      "symbol": "AAPL",
                      "side": "BUY",
                      "type": "LIMIT",
                      "quantity": 0,
                      "limitPrice": 185.50
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.correlationId").value("corr-validation"));
    }

    @Test
    void returnsNotFoundErrorForMissingOrder() throws Exception {
        when(orderApplicationService.getOrder("missing-order"))
            .thenThrow(new ResourceNotFoundException("Order not found: missing-order"));

        mockMvc.perform(get("/api/v1/orders/missing-order")
                .header("X-Correlation-Id", "corr-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
            .andExpect(jsonPath("$.correlationId").value("corr-not-found"));
    }

    @Test
    void submitsValidOrder() throws Exception {
        OrderResponse response = new OrderResponse(
            "order-1",
            "CLIENT-123",
            "ACC-001",
            "AAPL",
            OrderSide.BUY,
            OrderType.LIMIT,
            OrderStatus.ACCEPTED,
            100,
            new BigDecimal("185.50"),
            0,
            Instant.parse("2026-06-09T17:00:00Z"),
            Instant.parse("2026-06-09T17:00:00Z")
        );
        when(orderApplicationService.submitOrder(any(), eq("idem-submit"), any())).thenReturn(new OrderSubmissionResult(201, response));

        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", "idem-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validLimitOrderJson()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value("order-1"))
            .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void returnsExecutionReportList() throws Exception {
        when(orderApplicationService.getExecutionReports("order-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/orders/order-1/execution-reports"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray());
    }

    private static String validLimitOrderJson() {
        return """
            {
              "clientOrderId": "CLIENT-123",
              "accountId": "ACC-001",
              "symbol": "AAPL",
              "side": "BUY",
              "type": "LIMIT",
              "quantity": 100,
              "limitPrice": 185.50
            }
            """;
    }
}
