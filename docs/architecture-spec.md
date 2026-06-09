# Architecture Spec

## Overview

The intended architecture is a modular Spring Boot 3.x Java 21 service with synchronous REST APIs and asynchronous JMS processing. PostgreSQL is the source of truth. ActiveMQ Artemis provides local and test messaging. The architecture favors clear boundaries over microservice complexity.

This document describes the target design, not completed implementation.

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
7. The service publishes an `ORDER_SUBMITTED` JMS message after the order is stored.
8. The API returns `202 Accepted`.
9. `OrderSubmittedConsumer` receives the message.
10. The consumer checks message-consumption idempotency.
11. The consumer loads and locks or conditionally updates the order.
12. The execution simulator returns an execution outcome.
13. The consumer writes an `ExecutionReport`.
14. The consumer writes a `Trade` when quantity is filled.
15. The consumer updates `OrderStatus`.
16. Query APIs read current state and history from PostgreSQL.

## Transaction Boundaries

### Order Submission Transaction

The submission transaction should:

- Create or validate the `IdempotencyRecord`.
- Insert the `Order`.
- Persist the response body or enough data to rebuild the response for duplicates.
- Commit before the system exposes the order as accepted.

Message publication should be designed to avoid lost messages. The preferred implementation path is a transactional outbox table or a clearly documented best-effort publisher for the MVP. If direct JMS publish is used initially, the tradeoff must be documented and tested around failure behavior.

### Message Consumption Transaction

The consumer transaction should:

- Record message processing start or claim the message idempotency key.
- Load the order with optimistic locking or guarded status transitions.
- Create execution report and trade records.
- Update order state.
- Mark the message as processed.

If any step fails, the transaction should roll back so JMS redelivery can retry safely.

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

The consumer must record processed message IDs. Duplicate message IDs must be acknowledged without repeating side effects.

## Concurrency Model

The system should support multiple REST requests and multiple JMS consumer threads.

Concurrency requirements:

- Order submission idempotency must be protected with a unique database constraint.
- Consumer processing must prevent duplicate execution for the same order.
- Order updates should use optimistic locking or conditional SQL updates.
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

- Queue: `orders.submitted.v1`

Initial event:

- `ORDER_SUBMITTED`

Payload fields:

- `eventId`
- `messageId`
- `orderId`
- `accountId`
- `instrumentId`
- `correlationId`
- `occurredAt`
- `schemaVersion`

Retry and DLQ behavior:

- The consumer must throw on retryable failures so the broker can redeliver.
- Poison-message behavior should be DLQ-ready even if the first version only documents broker defaults.
- Non-retryable domain failures should create a rejected execution report and update order state.

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
