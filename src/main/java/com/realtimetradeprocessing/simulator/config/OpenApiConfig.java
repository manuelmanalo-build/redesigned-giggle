package com.realtimetradeprocessing.simulator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI tradeProcessingOpenApi() {
        Schema<?> apiErrorSchema = new ObjectSchema()
            .addProperty("timestamp", new StringSchema().format("date-time").example("2026-06-13T15:30:00Z"))
            .addProperty("status", new IntegerSchema().example(400))
            .addProperty("errorCode", new StringSchema().example("VALIDATION_ERROR"))
            .addProperty("message", new StringSchema().example("quantity must be greater than 0"))
            .addProperty("path", new StringSchema().example("/api/v1/orders"))
            .addProperty("correlationId", new StringSchema().example("corr-123"));

        return new OpenAPI()
            .info(new Info()
                .title("Realtime Trade Processing Simulator API")
                .version("v1.0.0-backend-mvp")
                .description("""
                    Backend MVP REST API for order submission, lifecycle operations, reference data, and operational search.
                    Accepted orders are persisted with a transactional outbox event and processed asynchronously through JMS.
                    Actuator health and metrics are available at `/actuator/health`, `/actuator/info`, and `/actuator/metrics`.
                    Consumer retry/DLQ diagnostics are currently persisted in `processed_messages`; no diagnostics REST endpoint is implemented yet.
                    """)
                .contact(new Contact().name("realtime-trade-processing-simulator")))
            .addServersItem(new Server().url("http://localhost:8080").description("Local development"))
            .components(new Components()
                .addSchemas("ApiErrorResponse", apiErrorSchema)
                .addHeaders("X-Correlation-Id", new Header()
                    .description("Correlation ID used for request logs, error responses, and order-submitted events.")
                    .schema(new StringSchema().example("corr-123"))));
    }
}
