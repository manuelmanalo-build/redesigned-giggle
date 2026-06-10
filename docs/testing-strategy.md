# Testing Strategy

## Purpose

The project should be built test-first where practical. Tests must prove the order submission, persistence, JMS processing, execution report, trade creation, idempotency, and error-handling behavior.

The current test suite includes pure domain unit tests, execution simulator unit tests, Spring Boot startup smoke tests, REST controller tests, JMS publisher unit tests, and PostgreSQL-backed persistence/API/consumer integration tests. Broker-backed JMS publish/consume tests should be added as a future hardening step.

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
- Filled and remaining quantity calculations.
- Average execution price calculation.
- Allowed and disallowed `OrderStatus` transitions.
- Trade creation rules from execution reports.
- Deterministic execution simulation outcomes.

Tools:

- JUnit 5.
- AssertJ.

### Application Service Tests

Cover orchestration with mocked dependencies where useful:

- Submit order creates idempotency record.
- Duplicate idempotency key with same fingerprint returns original result.
- Duplicate idempotency key with different fingerprint fails with conflict.
- Accepted order is stored before event publication is requested.
- Messaging failure behavior is explicit and tested according to the chosen outbox or direct-publish design.

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
- Table constraints enforce unique idempotency keys.
- Table constraints enforce enum values, order type/price consistency, execution report fill-field consistency, and valid idempotency response status ranges.
- Foreign keys protect execution report and trade relationships to orders.
- Pessimistic row locking and deterministic execution report IDs prevent duplicate message side effects in the current consumer flow.
- Query filters and pagination work for orders, execution reports, and trades.
- Index-backed query paths are documented where relevant.

Tools:

- Spring Boot Test.
- Testcontainers PostgreSQL.

### Messaging Integration Tests

Run against embedded Artemis or an Artemis Testcontainer:

- `JmsOrderEventPublisher` serializes `OrderSubmittedEvent` and sends it to `order.submitted`.
- Current API integration tests verify the `OrderEventPublisher` seam without starting a broker.
- Future broker-backed tests should verify that order submission publishes a readable message to Artemis.
- Current consumer integration tests invoke `OrderSubmittedEventConsumer` directly against PostgreSQL.
- Consumer tests verify market-order fills, marketable limit fills, non-marketable limit no-fills, missing-order safety, and duplicate delivery idempotency.
- Retryable failures trigger redelivery behavior.
- Poison-message or DLQ-ready behavior is documented and covered where practical.

### End-to-End Tests

Exercise the full flow:

1. Submit order through REST.
2. Wait for asynchronous processing using deterministic polling, not arbitrary sleeps.
3. Retrieve order and assert final status.
4. Retrieve execution reports.
5. Retrieve trades when filled or partially filled.
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
- Duplicate consumer attempts for the same message create one execution report and one trade.
- Multiple consumer threads process different orders safely.

Later performance work may add:

- Simple load scripts.
- Queue-depth observations.
- JVM allocation and GC notes.
- Listener concurrency experiments.
- Database connection pool sizing notes.
