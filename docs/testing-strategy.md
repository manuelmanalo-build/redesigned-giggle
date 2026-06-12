# Testing Strategy

## Purpose

The project should be built test-first where practical. Tests must prove the order submission, persistence, JMS processing, execution report, trade creation, idempotency, and error-handling behavior.

The current test suite includes pure domain unit tests, execution simulator unit tests, Spring Boot startup smoke tests, REST controller tests, outbox writer tests, JMS publisher unit tests, PostgreSQL-backed persistence/API/consumer/relay/inbox integration tests, and an Artemis-backed end-to-end publish/consume test.

## Test Commands

Expected commands:

```bash
./mvnw test
./mvnw verify
```

`./mvnw verify` should run integration tests that need Testcontainers.

On Windows with Docker Desktop, the Maven build configures Surefire to use `DOCKER_HOST=tcp://localhost:2375` and docker-java `api.version=1.40`. Docker Desktop's unauthenticated TCP endpoint must be enabled locally for the PostgreSQL Testcontainers tests.

## Test Layers

### Domain Unit Tests

Cover pure Java behavior without Spring:

- Order validation rules.
- `MARKET` and `LIMIT` price rules.
- Allowed and disallowed `OrderStatus` transitions.
- Trade creation rules from execution reports.
- Deterministic execution simulation outcomes.

Tools:

- JUnit 5.
- AssertJ.

### Application Service Tests

Cover orchestration with mocked dependencies where useful:

- Submit order creates idempotency record.
- Duplicate idempotency key with same fingerprint returns the same order resource and response status.
- Duplicate idempotency key with different fingerprint fails with conflict.
- Unknown or inactive account/instrument reference data rejects before order persistence.
- Account and instrument reference-data APIs create, update, list, and retrieve rows used by order validation.
- Accepted order is stored with a pending outbox event in the same transaction.
- Messaging failure behavior is captured through outbox relay retry metadata and consumer-side `processed_messages` diagnostics.

Tools:

- JUnit 5.
- AssertJ.
- Mockito.

### API Slice Tests

Cover REST behavior:

- `POST /api/v1/orders` returns `201 Created` for valid accepted requests.
- Missing `Idempotency-Key` returns validation error.
- Invalid enum values return `400 Bad Request`.
- Invalid quantity or price returns field errors.
- Idempotency conflict returns `409 Conflict`.
- `GET` endpoints return `200 OK` or `404 Not Found`.
- Error responses include `correlationId`.
- REST responses include an `X-Correlation-Id` response header.
- Global exception handling covers validation, domain exceptions, idempotency conflicts, and not-found responses.
- `/actuator/health` and custom Micrometer metrics are accessible in integration tests.

Tools:

- Spring MVC test support.
- Jackson JSON assertions.

### Persistence Integration Tests

Run against PostgreSQL Testcontainers:

- Core persistence tests currently verify save/find behavior for orders, execution reports, and trades.
- Trade persistence verifies the required link from each trade to the execution report that created it.
- Table constraints enforce unique idempotency keys.
- Reference-data repository tests verify seeded active/inactive accounts and instruments.
- Outbox table constraints enforce valid statuses and non-negative attempt counts.
- Processed-message inbox constraints enforce valid statuses and non-negative attempt counts.
- Table constraints enforce enum values, order type/price consistency, execution report fill-field consistency, and valid idempotency response status ranges.
- Foreign keys protect execution report and trade relationships to orders.
- Processed-message inbox rows, pessimistic row locking, and deterministic execution report IDs prevent duplicate message side effects in the current consumer flow.
- Current repository query methods support order, execution-report, and trade lookup by ID/order ID. List filters and pagination are planned extensions.
- Index-backed query paths are documented where relevant.

Tools:

- Spring Boot Test.
- Testcontainers PostgreSQL.

### Messaging Integration Tests

Run against embedded Artemis or an Artemis Testcontainer:

- `OutboxEventWriter` serializes `OrderSubmittedEvent` into `outbox_events`.
- API integration tests verify accepted orders create one pending outbox event and invalid/conflicting submissions do not create publishable outbox rows.
- API integration tests verify unknown accounts, suspended/closed accounts, unknown symbols, halted instruments, and delisted instruments are hard rejected without order/idempotency/outbox persistence.
- API integration tests verify account/instrument reference data can be created, updated, retrieved, and then used by order submission.
- `OutboxRelayService` integration tests verify pending event publication, successful `PUBLISHED` marking, retry state on publish failure, max-attempt `FAILED` behavior, and skipping already-published rows.
- `JmsOrderEventPublisher` serializes `OrderSubmittedEvent` and sends it to `order.submitted`.
- A broker-backed Artemis Testcontainers test verifies that REST order submission writes the outbox, the relay publishes a JMS message, and the asynchronous listener consumes it.
- Current consumer integration tests invoke `OrderSubmittedEventConsumer` directly against PostgreSQL.
- Consumer tests verify market-order fills, marketable limit fills, non-marketable limit no-fills, missing-order safety, and duplicate delivery idempotency.
- Consumer tests verify new messages create `processed_messages` rows, successful processing marks rows `PROCESSED`, duplicate delivery marks rows `DUPLICATE` without duplicate reports/trades, and retryable failures mark rows `FAILED` with `last_error` and `attempt_count`.
- Retryable failures trigger redelivery behavior.
- Poison-message or broker DLQ routing is documented as broker configuration; app-side diagnostics are covered through `processed_messages`.

### End-to-End Tests

Exercise the full flow:

1. Submit order through REST.
2. Wait for asynchronous processing using deterministic polling, not arbitrary sleeps.
3. Retrieve order and assert final status.
4. Retrieve execution reports.
5. Retrieve trades when filled.
6. Assert correlation ID appears in response and logs where feasible.

## Test Data Strategy

- Use small builders or factory methods for accounts, instruments, and orders.
- Keep default test data valid.
- Override only the field relevant to each test.
- Avoid large fixture files unless they clarify API contract tests.

## CI Strategy

GitHub Actions should run:

```bash
./mvnw -B clean verify
```

CI should verify:

- Java 21 compilation.
- Unit tests.
- API slice tests.
- PostgreSQL integration tests.
- JMS integration tests.
- Packaging.

## Performance and Concurrency Tests

The MVP should include focused concurrency tests rather than large load tests:

- Concurrent submissions with the same idempotency key create one order.
- Duplicate consumer attempts for the same message create one execution report, one trade, and a duplicate inbox observation.
- Multiple consumer threads process different orders safely.

Later performance work may add:

- Simple load scripts.
- Queue-depth observations.
- JVM allocation and GC notes.
- Listener concurrency experiments.
- Database connection pool sizing notes.
