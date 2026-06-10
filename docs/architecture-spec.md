# Architecture Spec

## Overview

The intended architecture is a modular Spring Boot 3.x Java 21 service with synchronous REST APIs and asynchronous JMS processing. PostgreSQL is the source of truth. ActiveMQ Artemis provides local and test messaging. The architecture favors clear boundaries over microservice complexity.

This document describes the current MVP design and planned extensions.

## MVP Components

- REST API layer: order submission and query endpoints.
- Application service layer: command orchestration, idempotency, transactions, and messaging coordination.
- Domain layer: order validation, state transitions, execution decisions, and trade creation rules.
- Persistence layer: JPA entities, repositories, SQL constraints, and indexes.
- Messaging producer: publishes `ORDER_SUBMITTED` events.
- Messaging consumer: receives order events, simulates execution, writes execution reports and trades, and updates order state.
- Observability layer: correlation ID filter/interceptor and structured logging.
- Configuration layer: Spring profiles, database, JMS, listener concurrency, and retry settings.
- Test infrastructure: unit, slice, integration, container-backed, and CI tests.

## Package Boundary Target

The first implementation should use a package structure similar to:

- `api`: controllers, request DTOs, response DTOs, API errors.
- `application`: use cases, transaction scripts, idempotency coordination.
- `domain`: entities or domain objects, enums, validation, state transition policies.
- `persistence`: JPA entities, repositories, mappers, database-specific query support.
- `messaging`: JMS payloads, producer, consumer, destination constants.
- `config`: Spring configuration and properties.
- `observability`: correlation ID and logging helpers.

The exact package names may evolve, but framework concerns should not leak into domain rules unnecessarily.

## Core Data Flow

1. `OrderController` receives `POST /api/v1/orders`.
2. Correlation middleware resolves `X-Correlation-Id` or creates one.
3. API validation checks JSON shape and simple constraints.
4. `SubmitOrderService` validates domain rules.
5. The service checks or creates an `IdempotencyRecord`.
6. Within a database transaction, the service stores the order and records the intended response.
7. The service registers an after-commit publication of `OrderSubmittedEvent`.
8. After the database transaction commits, `OrderEventPublisher` sends the event to the `order.submitted` JMS queue.
9. The API returns `201 Created`.
10. `OrderSubmittedEventConsumer` receives the message.
11. The consumer checks message-consumption idempotency.
12. The consumer loads and locks or conditionally updates the order.
13. The execution simulator returns an execution outcome.
14. The consumer writes an `ExecutionReport`.
15. The consumer writes a `Trade` when quantity is filled.
16. The consumer updates `OrderStatus`.
17. Query APIs read current state and history from PostgreSQL.

## Transaction Boundaries

### Order Submission Transaction

The submission transaction should:

- Create or validate the `IdempotencyRecord`.
- Insert the `Order`.
- Persist the response body or enough data to rebuild the response for duplicates.
- Commit before the system exposes the order as accepted.

Current MVP behavior:

- A valid order is persisted as `ACCEPTED`.
- The order and idempotency record are committed in one database transaction.
- `OrderSubmittedEvent` publication is registered with Spring transaction synchronization and runs after commit.
- The application service depends on `OrderEventPublisher`, not `JmsTemplate`, so messaging can be tested and replaced independently.

Tradeoff:

- The MVP does not use a transactional outbox.
- Publishing after commit avoids emitting events for rolled-back database work.
- A process crash or broker outage after the database commit but before/during JMS send can leave an accepted order without a published event.
- A production-grade design should add an outbox table and relay process so database state and event publication can be retried reliably.

### Message Consumption Transaction

The current consumer transaction:

- Deserializes `OrderSubmittedEvent` outside the database transaction.
- Loads the order with a pessimistic write lock.
- Uses a deterministic execution-report ID derived from the event ID as the current message idempotency key.
- Skips duplicate events that already produced an execution report.
- Creates execution report and trade records when the simulated execution fills.
- Leaves non-marketable limit orders in `ACCEPTED` status with a non-fill execution report and no trade.
- Updates `orders.status`, `orders.filled_quantity`, and `orders.updated_at` atomically with the execution report/trade writes.

If any database step fails, the transaction rolls back so JMS redelivery can retry safely.

## Idempotency Design

### REST Submission

Clients must send `Idempotency-Key` for `POST /api/v1/orders`.

Rules:

- First use creates a record with request fingerprint and response reference.
- Repeated use with the same fingerprint returns the original logical response.
- Repeated use with a different fingerprint returns `409 Conflict`.
- Idempotency records should have a creation timestamp and final status.

### JMS Consumption

Each message must include:

- `messageId`
- `eventId`
- `eventType`
- `orderId`
- `correlationId`
- `occurredAt`

Current MVP behavior uses deterministic execution-report IDs derived from event IDs to make duplicate delivery safe. Duplicate event IDs are acknowledged without creating duplicate trades or duplicate terminal execution reports.

A later production hardening step should add an explicit `processed_messages` inbox table so message claims, retry metadata, and DLQ diagnostics are queryable independently of execution-report IDs.

## Concurrency Model

The system should support multiple REST requests and multiple JMS consumer threads.

Concurrency requirements:

- Order submission idempotency must be protected with a unique database constraint.
- Consumer processing must prevent duplicate execution for the same order.
- Current order execution processing uses a pessimistic row lock on `orders` plus deterministic report/trade IDs to handle duplicate delivery.
- Later high-throughput versions can evaluate optimistic locking or conditional SQL updates.
- Domain services should avoid mutable shared state.
- Listener concurrency should be configurable.

## Persistence Model

Current MVP tables:

- `orders`
- `execution_reports`
- `trades`
- `idempotency_records`

Planned later tables:

- `accounts`
- `instruments`
- `processed_messages`

Current constraints:

- `orders.quantity` must be positive.
- `orders.filled_quantity` must be non-negative and cannot exceed `orders.quantity`.
- Price columns must be positive when present.
- `idempotency_records.idempotency_key` is the primary key.
- Foreign keys from execution reports, trades, and idempotency records protect references to orders.

Current indexes:

- `orders(client_order_id)`
- `orders(account_id)`
- `orders(symbol)`
- `orders(status)`
- `execution_reports(order_id)`
- `trades(order_id)`
- `idempotency_records(idempotency_key)`

Future query-oriented indexes should add `created_at` to support pagination and account/status history lookups once list endpoints exist.

## Messaging Design

Initial destination:

- Queue: `order.submitted`

Initial event:

- `OrderSubmittedEvent`

Payload fields:

- `eventId`
- `orderId`
- `clientOrderId`
- `accountId`
- `symbol`
- `side`
- `type`
- `quantity`
- `limitPrice`
- `correlationId`
- `createdAt`

Serialization:

- `JmsOrderEventPublisher` serializes the event explicitly to a JSON text message with Jackson.
- JMS message properties include `eventType`, `eventId`, `orderId`, and `correlationId`.
- `JMSCorrelationID` is set from the request correlation ID.

Current tests:

- The JMS publisher is unit-tested with a mocked `JmsTemplate`.
- REST integration tests mock `OrderEventPublisher` to verify accepted orders publish an event and invalid/conflicting submissions do not create duplicate publications.
- Consumer integration tests currently invoke the consumer directly against PostgreSQL to verify market fills, limit fills, limit no-fills, duplicate delivery, and missing-order safety.
- Broker-backed publish/consume tests are still deferred as a future hardening step.

Retry and DLQ behavior:

- The consumer must throw on retryable failures so the broker can redeliver.
- Poison-message behavior should be DLQ-ready even if the first version only documents broker defaults.
- Non-retryable domain failures should create a rejected execution report and update order state.

## Observability and Operations

REST correlation behavior:

- `CorrelationIdFilter` resolves `X-Correlation-Id` or generates a UUID.
- The correlation ID is written to SLF4J MDC, returned as the `X-Correlation-Id` response header, included in error responses, and passed into `OrderSubmittedEvent`.
- JMS consumers restore the event correlation ID into MDC while processing.

Current custom Micrometer meters:

- `trade.orders.submitted`
- `trade.orders.rejected`
- `trade.execution_reports.created`
- `trade.trades.created`
- `trade.messages.processing.failures`
- `trade.messages.processing.duration`

Operational endpoints:

- `/actuator/health`: application health with database and broker contributors when configured.
- `/actuator/info`: application info.
- `/actuator/metrics`: available metrics.
- `/actuator/metrics/{name}`: individual metric samples.

## Error Handling

API errors should use a consistent JSON structure and avoid leaking implementation details.

Expected categories:

- `400 Bad Request`: malformed JSON or validation failure.
- `404 Not Found`: missing order, execution report, trade, account, or instrument.
- `409 Conflict`: idempotency key conflict or invalid state transition.
- `422 Unprocessable Entity`: syntactically valid request that violates domain rules, if the project chooses to distinguish it from `400`.
- `500 Internal Server Error`: unexpected server failure.

## Local Deployment Model

Initial local runtime:

- Spring Boot application.
- PostgreSQL via Docker Compose.
- ActiveMQ Artemis via Docker Compose or embedded Artemis for tests.

CI runtime:

- Maven.
- JUnit 5.
- Testcontainers for PostgreSQL and JMS integration tests.
- GitHub Actions running `mvn clean verify`.
