# Architecture Spec

## Overview

The intended architecture is a modular Spring Boot 3.x Java 21 service with synchronous REST APIs and asynchronous JMS processing. PostgreSQL is the source of truth. ActiveMQ Artemis provides local and test messaging. The architecture favors clear boundaries over microservice complexity.

This document describes the current MVP design and planned extensions.

## MVP Components

- REST API layer: order submission and query endpoints.
- Application service layer: command orchestration, idempotency, transactions, and messaging coordination.
- Domain layer: order validation, state transitions, execution decisions, and trade creation rules.
- Persistence layer: JPA entities, repositories, SQL constraints, and indexes.
- Outbox writer and relay: persists integration events atomically with order changes, then publishes pending events to JMS.
- Messaging producer: publishes outbox-backed `ORDER_SUBMITTED` events.
- Messaging consumer: receives order events, records inbox diagnostics, simulates execution, writes execution reports and trades, and updates order state.
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
4. `OrderApplicationService` validates domain rules.
5. `ReferenceDataValidationService` verifies the account exists and is `ACTIVE`, and the instrument exists and is `ACTIVE`.
6. The service claims an `IdempotencyRecord` with a PostgreSQL `ON CONFLICT DO NOTHING` insert.
7. Within one database transaction, the service stores the order, records the intended response, and inserts an `outbox_events` row containing `OrderSubmittedEvent`.
8. The API returns `201 Created` after the transaction commits. It does not publish directly to JMS.
9. `OutboxRelayScheduler` periodically calls `OutboxRelayService`.
10. The relay locks due `PENDING` outbox rows with `FOR UPDATE SKIP LOCKED`.
11. The relay publishes the event payload to the `order.submitted` JMS queue through `OrderEventPublisher`.
12. The relay marks the outbox row `PUBLISHED`, or records retry state on failure.
13. `OrderSubmittedEventConsumer` receives the message.
14. The consumer claims the event ID in `processed_messages`.
15. If the message was already processed, the consumer records a duplicate observation and skips business processing.
16. The consumer loads and locks or conditionally updates the order.
17. The execution simulator returns an execution outcome.
18. The consumer writes an `ExecutionReport`.
19. The consumer writes a `Trade` when quantity is filled.
20. The consumer updates `OrderStatus`.
21. The consumer marks the inbox row `PROCESSED`, or stores failure diagnostics and rethrows for broker redelivery.
22. Query APIs read current state and history from PostgreSQL.

## Transaction Boundaries

### Order Submission Transaction

The submission transaction should:

- Create or validate the `IdempotencyRecord`.
- Insert the `Order`.
- Persist the response body or enough data to rebuild the response for duplicates.
- Insert a `PENDING` `outbox_events` row for accepted orders.
- Commit before the system exposes the order as accepted.

Current MVP behavior:

- A valid order is persisted as `ACCEPTED`.
- Unknown accounts, suspended/closed accounts, unknown symbols, halted instruments, and delisted instruments are rejected before idempotency claim and order persistence.
- The order, idempotency record, and outbox event are committed in one database transaction.
- REST submission idempotency uses a database-backed claim: the first request inserts the idempotency key, and concurrent requests with the same key wait on PostgreSQL uniqueness before replaying or conflicting.
- The REST transaction does not call JMS.
- `OutboxEventWriter` serializes the integration event and stores it in the database.
- `OutboxRelayService` later publishes due outbox rows through `OrderEventPublisher`, not `JmsTemplate` directly, so messaging remains testable and replaceable.

Tradeoff:

- The outbox closes the gap where a database commit succeeds but immediate JMS publication fails.
- The relay is intentionally simple: it polls the database instead of using CDC, Kafka, or Debezium.
- A process crash after JMS send but before marking the row `PUBLISHED` can still cause a duplicate publish on retry.
- Consumers therefore remain idempotent and must treat duplicate message delivery as normal distributed-system behavior.
- Relay failures are observable in `outbox_events.attempt_count`, `last_error`, `next_attempt_at`, and `status`.

### Message Consumption Transaction

The current consumer transaction:

- Deserializes `OrderSubmittedEvent` outside the database transaction.
- Claims the message ID in `processed_messages` using a database-backed `ON CONFLICT DO NOTHING` insert.
- Locks the processed-message row while deciding whether to process or skip.
- Skips rows already marked `PROCESSED`, `DUPLICATE`, or `DEAD_LETTERED`; duplicate observations are marked `DUPLICATE`.
- Loads the order with a pessimistic write lock.
- Still uses a deterministic execution-report ID derived from the event ID as the business idempotency backstop.
- Skips duplicate events that already produced an execution report.
- Creates execution report and trade records when the simulated execution fills.
- Leaves non-marketable limit orders in `ACCEPTED` status with a non-fill execution report and no trade.
- Updates `orders.status`, `orders.filled_quantity`, and `orders.updated_at` atomically with the execution report/trade writes.
- Marks the processed-message row `PROCESSED` after successful processing.

If any database step fails, the message-processing transaction rolls back so JMS redelivery can retry safely. A separate failure-diagnostic transaction records or updates the `processed_messages` row as `FAILED`, increments `attempt_count`, and stores `last_error`.

## Idempotency Design

### REST Submission

Clients must send `Idempotency-Key` for `POST /api/v1/orders`.

Rules:

- First use creates a record with request fingerprint and response reference.
- Concurrent first use is guarded by the `idempotency_records` primary key and an `ON CONFLICT DO NOTHING` claim insert.
- Repeated use with the same fingerprint returns the same order resource and response status. Because the async consumer may have updated the order, the replayed body can reflect current order state.
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

Current MVP behavior uses `processed_messages` as an inbox table for observable consumer idempotency and retry diagnostics. Deterministic execution-report IDs and unique trade constraints remain in place so business correctness does not depend only on the inbox row.

Inbox statuses:

- `RECEIVED`: message has been claimed for processing.
- `PROCESSED`: message completed successfully.
- `FAILED`: processing failed and the consumer rethrew so the broker can redeliver.
- `DUPLICATE`: a terminal message was observed again and skipped.
- `DEAD_LETTERED`: reserved diagnostic status for broker/DLQ integration.

## Concurrency Model

The system should support multiple REST requests and multiple JMS consumer threads.

Concurrency requirements:

- Order submission idempotency must be protected with a unique database constraint.
- Consumer processing must prevent duplicate execution for the same order.
- Current order execution processing uses the `processed_messages` inbox, a pessimistic row lock on `orders`, and deterministic report/trade IDs to handle duplicate delivery.
- Later high-throughput versions can evaluate optimistic locking or conditional SQL updates.
- Domain services should avoid mutable shared state.
- Listener concurrency should be configurable.

## Persistence Model

Current MVP tables:

- `orders`
- `accounts`
- `instruments`
- `execution_reports`
- `trades`
- `idempotency_records`
- `outbox_events`
- `processed_messages`

Current constraints:

- `accounts.status` is constrained to `ACTIVE`, `SUSPENDED`, or `CLOSED`.
- `instruments.asset_class` is constrained to `EQUITY`, `ETF`, `OPTION`, `FUTURE`, or `CRYPTO`.
- `instruments.status` is constrained to `ACTIVE`, `HALTED`, or `DELISTED`.
- `instruments.tick_size` must be positive when present.
- `orders.quantity` must be positive.
- `orders.filled_quantity` must be non-negative and cannot exceed `orders.quantity`.
- Price columns must be positive when present.
- `orders.side`, `orders.type`, `orders.status`, `execution_reports.execution_type`, `execution_reports.order_status`, and `trades.side` are constrained to known enum values.
- `orders.type` and `orders.limit_price` are constrained so market orders have no limit price and limit orders have a limit price.
- Fill execution reports must include executed quantity and execution price; non-fill reports must not.
- `idempotency_records.idempotency_key` is the primary key.
- `idempotency_records.response_status` must be a valid HTTP status code range.
- `outbox_events.status` is constrained to `PENDING`, `PUBLISHED`, or `FAILED`.
- `outbox_events.attempt_count` must be non-negative.
- `processed_messages.status` is constrained to `RECEIVED`, `PROCESSED`, `FAILED`, `DUPLICATE`, or `DEAD_LETTERED`.
- `processed_messages.attempt_count` must be non-negative.
- Foreign keys from execution reports, trades, and idempotency records protect references to orders.
- Each trade references the execution report that created it, and `trades.execution_report_id` is unique so duplicate fill reports cannot create duplicate trades for the same report.

Current indexes:

- `orders(client_order_id)`
- `orders(account_id)`
- `orders(symbol)`
- `orders(status)`
- `accounts(status)`
- `instruments(status)`
- `instruments(asset_class)`
- `execution_reports(order_id)`
- `trades(order_id)`
- `trades(execution_report_id)`
- `idempotency_records(idempotency_key)`
- `outbox_events(status, next_attempt_at, created_at)`
- `outbox_events(aggregate_type, aggregate_id)`
- `processed_messages(status)`
- `processed_messages(aggregate_id)`
- `processed_messages(consumer_name, status)`

Future query-oriented indexes should add `created_at` to support pagination and account/status history lookups once search endpoints exist.

Reference-data validation:

- `ReferenceDataValidationService` is called from the application layer after domain object construction and before idempotency claim.
- Controllers only validate request shape; they do not know account or instrument activity rules.
- The MVP hard rejects invalid reference data with `400 Bad Request` and does not persist rejected order rows.
- This keeps the current order lifecycle focused on accepted orders and asynchronous execution. A future audit-heavy workflow could choose to persist rejected orders and rejection reports instead.
- `ReferenceDataApplicationService` backs simple REST management endpoints for listing, retrieving, creating, and updating accounts and instruments.
- Delete endpoints are intentionally omitted; account and instrument lifecycle is represented by status so historical orders keep stable references.

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

- `OutboxEventWriter` serializes `OrderSubmittedEvent` into `outbox_events.payload`.
- `JmsOrderEventPublisher` serializes the event explicitly to a JSON text message with Jackson.
- JMS message properties include `eventType`, `eventId`, `orderId`, and `correlationId`.
- `JMSCorrelationID` is set from the request correlation ID.

Outbox relay:

- `OutboxRelayScheduler` runs on a configurable fixed delay.
- `OutboxRelayService` fetches due `PENDING` rows in configurable batches.
- PostgreSQL `FOR UPDATE SKIP LOCKED` avoids multiple relay transactions working the same row in normal operation.
- Successful publication marks a row `PUBLISHED` and sets `published_at`.
- Failed publication increments `attempt_count`, stores `last_error`, and sets `next_attempt_at` using simple backoff.
- Rows are marked `FAILED` after the configured max attempts.

Consumer inbox:

- `OrderSubmittedMessageInboxProcessor` wraps `OrderExecutionProcessor`.
- New messages are claimed in `processed_messages` using `eventId` as `message_id`.
- Successful processing marks the row `PROCESSED`.
- Duplicate terminal messages are skipped and marked `DUPLICATE`.
- Processing failures are recorded as `FAILED` in a separate transaction so diagnostics survive the rollback that enables JMS redelivery.
- The inbox is a diagnostic and coordination layer. Deterministic execution-report/trade IDs remain the final idempotency defense.

Current tests:

- The JMS publisher is unit-tested with a mocked `JmsTemplate`.
- Outbox writer behavior is unit-tested.
- REST integration tests verify accepted orders create one pending outbox row and invalid/conflicting submissions do not create publishable outbox rows.
- Relay integration tests verify pending event publication, `PUBLISHED` marking, failure retry metadata, max-attempt `FAILED` behavior, and skipping already-published rows.
- Consumer integration tests currently invoke the consumer directly against PostgreSQL to verify market fills, limit fills, limit no-fills, missing-order safety, duplicate delivery, inbox `PROCESSED`/`DUPLICATE` rows, and failure diagnostics.
- A broker-backed Artemis Testcontainers integration test verifies that REST submission writes the outbox, the relay publishes a real JMS message, and the asynchronous listener consumes it into an execution report, trade, and filled order state.

Retry and DLQ behavior:

- The relay retries publish failures using database-visible retry state.
- Failed relay attempts are visible in `outbox_events`.
- Consumer processing failures are visible in `processed_messages` with `status`, `attempt_count`, `last_error`, `last_seen_at`, and `correlation_id`.
- The consumer must throw on retryable failures so the broker can redeliver.
- JMS listener sessions are transacted in the MVP so a processing exception rolls back message acknowledgement and allows broker redelivery.
- Broker-level DLQ routing is still configured outside application code. The app-side `DEAD_LETTERED` status is reserved for future broker DLQ listener or administrative reconciliation.
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
- `409 Conflict`: idempotency key conflict, duplicate account, duplicate instrument, or invalid state transition.
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
- GitHub Actions running `./mvnw -B clean verify`.
