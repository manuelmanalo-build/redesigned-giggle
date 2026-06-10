# Engineering Standards

## Purpose

These standards guide implementation once code is added. They should keep the project production-shaped, testable, and interview-ready.

## Java Standards

- Use Java 21.
- Prefer clear, idiomatic Java over clever constructs.
- Use records for immutable DTOs and simple value carriers when appropriate.
- Use enums for constrained domain values.
- Keep domain rules explicit and unit tested.
- Avoid nullable return values when `Optional`, result types, or explicit errors communicate intent better.
- Keep exceptions meaningful and close to the failure cause.
- Do not use mutable static state for domain or processing logic.

## Spring Standards

- Use Spring Boot 3.x conventions.
- Use constructor injection.
- Keep controllers thin.
- Put use-case orchestration in application services.
- Keep domain decisions outside controllers, JPA entities, and JMS listener methods where practical.
- Use `@ConfigurationProperties` for database, broker, listener, and simulator settings.
- Keep transaction boundaries explicit at service methods.

## API Standards

- Use `/api/v1` for MVP routes.
- Use JSON.
- Require `Idempotency-Key` on `POST /api/v1/orders`.
- Accept and return `X-Correlation-Id`.
- Return consistent error responses.
- Validate API shape at the boundary and enforce business invariants in domain/application code.
- Never expose stack traces or persistence internals in API responses.

## Persistence Standards

- Use PostgreSQL.
- Use explicit schema migrations once a migration tool is introduced.
- Use database constraints for uniqueness and referential integrity.
- Index documented query paths.
- Avoid leaking JPA entities into API responses or JMS payloads.
- Use optimistic locking or guarded updates for concurrent order state changes.
- Store timestamps in UTC.

## Messaging Standards

- Use Spring JMS with ActiveMQ Artemis.
- Use durable queues for order processing.
- Initial destination: `order.submitted`.
- Include `eventId`, `orderId`, client order/account/symbol fields, order terms, `correlationId`, and creation time in event payloads.
- Make consumers idempotent.
- Throw on retryable failures so broker redelivery can occur.
- Make DLQ behavior documented and configurable.
- Version message payloads.

## Idempotency Standards

- REST submission idempotency is based on `Idempotency-Key` plus request fingerprint.
- Message consumption idempotency is based on message ID.
- Protect idempotency with unique database constraints.
- Replays must not create duplicate orders, execution reports, or trades.
- Conflicting reuse of an idempotency key must return `409 Conflict`.

## Observability Standards

- Use structured logs.
- Include `correlationId` in REST and JMS flows.
- Include relevant identifiers such as `orderId`, `executionReportId`, `tradeId`, and `messageId`.
- Log state transitions at info level.
- Log unexpected failures with stack traces server-side only.
- Do not log sensitive account data beyond stable identifiers needed for debugging.
- Expose operational health and metrics through Spring Boot Actuator.
- Keep custom metric names stable and documented when behavior changes.

## Testing Standards

- Prefer TDD for behavior changes.
- Use JUnit 5, AssertJ, Mockito, and Testcontainers.
- Keep unit tests fast and framework-light.
- Use PostgreSQL for persistence integration tests, not H2, unless a specific narrow test justifies it.
- Avoid arbitrary sleeps in asynchronous tests; use polling with timeouts.
- Add tests for error paths, retries, idempotency, and concurrent processing.

## Quality Gates

Local and CI quality gates should stay aligned:

- Java version: CI must run Java 21.
- Dependency resolution: use the Maven wrapper rather than relying on runner-local Maven.
- Compile/static check: run `./mvnw -B -DskipTests compile`.
- Unit and integration tests: run `./mvnw -B clean verify`.
- Testcontainers: integration tests must run against real PostgreSQL containers in CI.
- Packaging: `clean verify` must produce the Spring Boot jar.
- Container build: CI should build the Docker image with `docker build --tag realtime-trade-processing-simulator:ci .`.
- Formatting/static analysis plugins should be added to the Maven build before they become required CI gates.

## Documentation Standards

- Update specs when APIs, data flow, domain rules, schemas, message payloads, or operational assumptions change.
- Keep documentation implementation-ready but not cluttered with accidental code detail.
- Record tradeoffs explicitly, especially transaction and messaging guarantees.

## Review Standards

- Keep changes focused and reviewable.
- Include tests with behavior changes.
- Include docs updates when behavior changes.
- Avoid unrelated refactors.
- Do not introduce major frameworks, dependencies, or architecture shifts without updating specs first.
