# Interview Talking Points

## 1. 30-Second Project Summary

This project is a Java 21 Spring Boot backend that simulates a simplified real-time trade processing platform. A client submits an order through `POST /api/v1/orders`; the service validates it, stores it in PostgreSQL, records an idempotency claim, and writes an `OrderSubmittedEvent` to a transactional outbox in the same database transaction. A relay publishes pending outbox events to the `order.submitted` JMS queue, and an asynchronous consumer receives the event, locks the order row, simulates execution, writes an execution report, creates a trade for fills, and updates order state. The project demonstrates domain modeling, REST APIs, SQL constraints, reliable messaging patterns, concurrency-safe processing, observability, Testcontainers-based integration tests, CI, and cloud deployment tradeoffs.

## 2. Architecture Explanation

The codebase is a modular monolith with explicit package boundaries:

- `api`: `OrderController`, request/response DTOs, error responses, and `GlobalApiExceptionHandler`.
- `application`: orchestration services such as `OrderApplicationService`, `OutboxEventWriter`, `OrderExecutionProcessor`, and `ExecutionSimulator`.
- `domain`: pure Java domain records and enums such as `Order`, `Quantity`, `Price`, `ExecutionReport`, and `Trade`.
- `persistence`: JPA entities, Spring Data repositories, and Flyway-managed schema.
- `messaging`: outbox relay/scheduler, JMS event payload, publisher abstraction, publisher implementation, and consumer.
- `observability`: correlation ID filter and Micrometer metrics.
- `fix`: simplified educational FIX parser and New Order Single mapper.

Core flow:

1. `OrderController.submitOrder` receives `POST /api/v1/orders` with an `Idempotency-Key`.
2. `CorrelationIdFilter` resolves or creates the correlation ID.
3. `SubmitOrderRequest` validation handles request shape.
4. `OrderApplicationService.submitOrder` builds a domain `Order`, accepts it, and computes a SHA-256 request fingerprint.
5. `IdempotencyRecordJpaRepository.claimSubmission` uses PostgreSQL `ON CONFLICT DO NOTHING` to claim the idempotency key.
6. The service saves the order, completes the idempotency record, and inserts a pending outbox row in one Spring transaction.
7. `OutboxRelayService` polls due pending rows, publishes the JSON event to JMS, and marks successful rows `PUBLISHED`.
8. Failed relay attempts increment `attempt_count`, store `last_error`, and set `next_attempt_at` for retry.
9. `OrderSubmittedEventConsumer` receives the JSON JMS message and delegates to `OrderExecutionProcessor`.
10. `OrderExecutionProcessor` uses a pessimistic write lock, deterministic execution-report IDs, and database constraints to avoid duplicate side effects.
11. Query endpoints return order state, execution reports, and trades from PostgreSQL.

The architecture is deliberately not split into microservices. For interview purposes, the value is in the clarity of boundaries and failure-mode discussion, not distributed complexity for its own sake.

## 3. Java/OOP Concepts Demonstrated

- Java 21 records model immutable domain/value objects: `Order`, `Quantity`, `Price`, `ExecutionReport`, `Trade`, and ID wrappers.
- Enums model constrained domain vocabularies: `OrderSide`, `OrderType`, `OrderStatus`, and `ExecutionType`.
- Domain invariants live in domain constructors and methods, not controllers. For example, `Order` rejects limit orders without price and market orders with price.
- `Order.transitionTo` centralizes valid state transitions and throws `DomainException` for invalid lifecycle movement.
- `Trade.fromExecutionReport` expresses that trades are derived from fill execution reports.
- Interfaces provide testable substitution points: `OrderEventPublisher` decouples relay logic from `JmsTemplate`, and `ExecutionSimulator` decouples execution rules from the consumer.
- DTOs, domain objects, JMS payloads, and JPA entities are separate. That keeps API, persistence, messaging, and business rules from leaking into each other.
- Constructor injection is used for Spring dependencies.

Strong OOP answer:

The domain model is not just getters and setters. It encodes rules around order construction, price requirements, quantity validation, state transitions, and trade creation. Persistence entities exist because JPA has different needs from the domain model, so mapping is explicit.

## 4. Data Structures Used And Why

- Relational tables store authoritative state: `orders`, `execution_reports`, `trades`, `idempotency_records`, and `outbox_events`.
- Primary keys enforce identity for orders, execution reports, trades, and idempotency records.
- `idempotency_records.idempotency_key` acts as a deduplication set for client retries.
- `outbox_events(status, next_attempt_at, created_at)` acts as a durable work queue for integration-event relay.
- Deterministic execution-report and trade IDs derived from event IDs make duplicate message handling idempotent.
- `trades.execution_report_id` is unique, enforcing one trade per fill execution report.
- Indexes support expected lookup paths:
  - `orders(client_order_id)` for client/FIX-style lookup.
  - `orders(account_id)`, `orders(symbol)`, and `orders(status)` for operational filtering.
  - `execution_reports(order_id)` and `trades(order_id)` for order lifecycle history.
  - `trades(execution_report_id)` for the fill-report-to-trade invariant.
  - `outbox_events(status, next_attempt_at, created_at)` for due-event polling.
  - `outbox_events(aggregate_type, aggregate_id)` for diagnostics by order.
- `LinkedHashMap` in the simplified FIX parser preserves tag insertion order, which is useful for deterministic parsing behavior and debugging.
- Queues provide asynchronous buffering between order submission and execution processing.

## 5. Multithreading And Concurrency Concepts Demonstrated

- REST requests are handled concurrently by the web container, so idempotency cannot rely on in-memory checks.
- Client retry concurrency is handled at the database layer using `INSERT ... ON CONFLICT DO NOTHING` in `IdempotencyRecordJpaRepository.claimSubmission`.
- JMS consumers may run concurrently. The listener concurrency is configurable through Spring JMS settings.
- Outbox relay polling can also run concurrently across instances; `FOR UPDATE SKIP LOCKED` prevents normal duplicate work on the same pending outbox row.
- `OrderJpaRepository.findByIdForUpdate` uses `PESSIMISTIC_WRITE` to serialize processing for the same order.
- `OrderExecutionProcessor` checks for an existing deterministic execution report before and after acquiring the order lock, reducing duplicate-message race windows.
- JMS listener sessions are transacted in `JmsListenerConfig`, so processing failures can roll back acknowledgement and allow redelivery.
- Domain services avoid mutable shared state. State changes go through database transactions.

Senior-level nuance:

The current design is safe enough for the MVP, but pessimistic locking can become a throughput bottleneck for hot orders or accounts. A higher-throughput version could use conditional SQL updates, optimistic locking, or partitioned event processing by order ID/account ID.

## 6. JVM/GC Discussion Points

- Main allocation sources are JSON serialization/deserialization, request/response DTOs, JPA entity hydration, records/value objects, `BigDecimal`, and logging context.
- `BigDecimal` is intentionally used for prices because correctness matters more than micro-allocation savings for money-like values.
- Listener concurrency, Hikari connection pool size, and HTTP worker count affect thread pressure and latency.
- For high p99 latency, correlate application timers with GC pauses, CPU, blocked threads, Hikari pool wait, PostgreSQL locks, and Artemis queue depth.
- The repo includes `docs/jvm-gc-performance-notes.md` and helper scripts for local GC logging.
- G1GC is a reasonable default for a Spring Boot service; the goal is predictable pauses, not maximum raw throughput.

Concise high-latency answer:

I would identify the endpoint or consumer path with high p99, then compare Micrometer timings, GC logs, CPU, thread dumps, Hikari pool wait time, PostgreSQL slow queries/locks, and broker queue depth/redeliveries. If GC pauses align with latency spikes, I would inspect allocation rate and heap pressure. If threads are blocked on JDBC or the broker while GC is quiet, I would treat it as a database, broker, or queueing problem.

## 7. REST API Design Discussion

Implemented endpoints:

- `POST /api/v1/orders`
- `GET /api/v1/orders/{orderId}`
- `GET /api/v1/orders/{orderId}/execution-reports`
- `GET /api/v1/orders/{orderId}/trades`

Design points:

- `POST /api/v1/orders` requires `Idempotency-Key`.
- `X-Correlation-Id` is optional; the service generates one when absent and returns it in responses.
- Request validation uses Jakarta Bean Validation on DTOs and headers.
- Domain validation is still enforced separately through `Order`, `Quantity`, `Price`, `AccountId`, and `InstrumentSymbol`.
- Errors use a consistent `ApiErrorResponse` shape through `GlobalApiExceptionHandler`.
- `409 Conflict` is used when an idempotency key is reused with a different normalized request.
- Query endpoints verify that the order exists before returning execution reports or trades.
- The POST currently returns `201 Created` after persistence and outbox insertion; a future asynchronous API could return `202 Accepted` if execution ownership moves further into the async layer.

Tradeoff:

The API is intentionally small and avoids list/search endpoints for now. The schema already has indexes that would support later account, symbol, and status filtering.

## 8. SQL Schema, Indexing, And Transaction Discussion

PostgreSQL is the source of truth. Flyway migrations define and harden the schema:

- `V2__create_core_persistence_tables.sql` creates `orders`, `execution_reports`, `trades`, and `idempotency_records`.
- `V3__harden_core_constraints.sql` adds enum checks, market/limit price consistency, execution-report fill-field consistency, and response-status checks.
- `V4__link_trades_to_execution_reports.sql` adds `trades.execution_report_id`, a foreign key, a unique constraint, and an index.
- `V5__create_outbox_events.sql` creates the transactional outbox table and relay indexes.

Important constraints:

- Order quantity must be positive.
- Filled quantity cannot exceed order quantity.
- Market orders must not have limit price.
- Limit orders must have limit price.
- Fill execution reports must have executed quantity and execution price.
- Non-fill execution reports must not have fill fields.
- Trades require positive quantity and price.
- One execution report can create at most one trade.
- Outbox status must be `PENDING`, `PUBLISHED`, or `FAILED`.
- Outbox attempt count cannot be negative.

Transaction boundaries:

- Order submission stores the order, idempotency record, and outbox event in one `@Transactional` method.
- The REST request does not publish directly to JMS.
- The outbox relay locks due rows, publishes to JMS, and marks rows `PUBLISHED` or records retry state in its own transaction.
- Message processing writes execution report, trade, and order status update in one `@Transactional` method.
- Read endpoints use `@Transactional(readOnly = true)`.

Indexing discussion:

The indexes match current and planned query paths rather than every possible column. The remaining gap is composite/pagination indexes such as `(account_id, created_at)` or `(status, created_at)` once list endpoints are implemented.

## 9. JMS/Messaging Discussion

Messaging components:

- `OrderEventPublisher`: application-facing abstraction.
- `JmsOrderEventPublisher`: Jackson JSON serialization and JMS send through `JmsTemplate`.
- `OutboxEventWriter`: serializes order-submitted events into `outbox_events`.
- `OutboxRelayService`: polls pending outbox rows, publishes them, and records success/failure state.
- `OutboxRelayScheduler`: fixed-delay trigger for the relay.
- `OrderSubmittedEvent`: explicit event payload.
- `OrderSubmittedEventConsumer`: JMS listener that restores correlation ID and delegates processing.
- Queue name: `order.submitted`.

Event fields:

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

Reliability choices:

- Events are persisted in `outbox_events` in the same transaction as the order and idempotency record.
- JMS publication is retried by the relay instead of happening directly in the REST request.
- Relay failures are visible in `attempt_count`, `last_error`, `next_attempt_at`, and `status`.
- Listener sessions are transacted.
- Duplicate delivery is handled with deterministic execution-report IDs and unique trade-to-report constraints.
- Broker-backed Testcontainers coverage verifies a real REST-to-outbox-to-Artemis-to-database path.

Known messaging tradeoff:

The outbox improves reliability, but it is still an at-least-once pattern. If the relay publishes to JMS and the process crashes before marking the row `PUBLISHED`, the event can be published again on retry. That is why the consumer remains idempotent.

## 10. Distributed Systems Tradeoffs

Tradeoffs already visible in the code:

- At-least-once messaging means duplicate deliveries are expected, so consumers must be idempotent.
- Transactional outbox prevents losing the intent to publish when the database commit succeeds but the broker is temporarily unavailable.
- The relay can still duplicate messages during crash recovery, so idempotent consumers are required.
- Database constraints are used as the final line of defense for invariants.
- Correlation IDs are propagated through REST and JMS for traceability.
- The service chooses a simple polling outbox over a full CDC platform for MVP explainability.
- Pessimistic locking is simple and correct for duplicate processing, but it may limit throughput.
- PostgreSQL is a single source of truth, which simplifies consistency but means database health dominates system availability.

How to improve for production:

- Add a processed-message inbox table for explicit consumer idempotency and retry diagnostics.
- Add DLQ dashboards and poison-message handling runbooks.
- Add authentication/authorization and account/instrument reference data.
- Add backward-compatible migrations and deployment gates.

## 11. TDD And Testing Strategy

The test suite is layered:

- Domain tests:
  - `OrderTest`
  - `ValueObjectTest`
  - `ExecutionReportAndTradeTest`
- Application tests:
  - `DefaultExecutionSimulatorTest`
- API tests:
  - `OrderControllerTest`
  - `OrderApiIntegrationTest`
- Persistence tests:
  - `CorePersistenceIntegrationTest` with PostgreSQL Testcontainers.
- Messaging tests:
  - `OutboxEventWriterTest`
  - `OutboxRelayServiceIntegrationTest`
  - `JmsOrderEventPublisherTest`
  - `OrderSubmittedEventConsumerIntegrationTest`
  - `OrderJmsEndToEndIntegrationTest` with Artemis and PostgreSQL Testcontainers.
- FIX tests:
  - `SimplifiedFixParserTest`.
- Startup/smoke tests:
  - `RealtimeTradeProcessingSimulatorApplicationTests`
  - `TestStackSmokeTest`.

Testing strengths:

- Pure domain behavior is tested without Spring.
- Database constraints are tested against real PostgreSQL, not H2.
- Idempotency and duplicate message behavior are covered.
- Broker-backed E2E coverage proves the actual outbox-to-JMS path.
- CI runs `./mvnw -B clean verify`.

Testing gaps to discuss honestly:

- No load/performance tests yet.
- No authentication/authorization tests because auth is not implemented.
- No migration compatibility tests against older production-like data.
- No full DLQ/redelivery policy test beyond transacted listener behavior and duplicate safety.

## 12. CI/CD Discussion

GitHub Actions workflow:

- Checks out code.
- Sets up Java 21 with Temurin.
- Caches Maven dependencies through `actions/setup-java`.
- Runs `./mvnw -v`.
- Runs `./mvnw -B -DskipTests compile`.
- Runs `./mvnw -B clean verify`.
- Builds a Docker image tagged `realtime-trade-processing-simulator:ci`.

CI value:

- Verifies the app compiles on a clean Linux runner.
- Runs unit and integration tests.
- Exercises Testcontainers-backed PostgreSQL and Artemis paths.
- Confirms the Dockerfile stays buildable.

Future CI/CD improvements:

- Publish Docker images to a registry.
- Add vulnerability/dependency scanning.
- Add formatting/static-analysis gates if project standards require them.
- Add environment-specific deployment jobs with manual approvals.
- Add migration checks and rollback planning.

## 13. AWS Deployment Discussion

Recommended AWS deployment:

- Run the Spring Boot container on ECS Fargate behind an Application Load Balancer.
- Use Amazon RDS PostgreSQL for the database.
- Use Amazon MQ for ActiveMQ if preserving JMS semantics matters.
- Send logs and metrics to CloudWatch.
- Store database and broker credentials in Secrets Manager.
- Use IAM task roles for AWS access.
- Use KMS for encryption of RDS, broker storage, secrets, and logs where required.

Alternatives:

- EKS is appropriate if the company already has Kubernetes platform maturity, but it adds operational overhead.
- EC2 offers host-level control but requires managing patching, scaling, deployment, process supervision, and security.
- SQS/SNS is simpler and more AWS-native than JMS, but the app would need a different messaging implementation and different visibility-timeout, ordering, and deduplication semantics.
- MSK/Kafka fits event streaming, replay, and multiple downstream consumers, but it is heavier than a queue for this MVP.

Autoscaling:

- Scale API tasks on request count, CPU, memory, and latency.
- Scale consumers on queue depth, oldest message age, processing duration, and redeliveries.
- Check RDS and broker saturation before blindly adding more tasks.

Concise AWS answer:

I would containerize the service and run it on ECS Fargate behind an ALB, use RDS PostgreSQL as the source of truth, and use Amazon MQ if I want JMS compatibility. I would put credentials in Secrets Manager, encrypt data with KMS, use IAM task roles, and send structured logs and Micrometer metrics to CloudWatch. The transactional outbox gives producer-side retry visibility; before production I would add a processed-message inbox and DLQ dashboards for consumer-side operations.

## 14. FIX And Trade Lifecycle Discussion

Implemented FIX-style module:

- `SimplifiedFixParser` parses `tag=value` fields separated by SOH or `|`.
- `SimplifiedFixMessage` exposes required tag lookup.
- `NewOrderSingleMapper` maps a simplified `35=D` New Order Single-like message into `SubmitOrderRequest`.

Supported mappings:

- `35=D` means New Order Single.
- `11` maps to `clientOrderId`.
- `49` maps to `accountId` in this simplified demo.
- `55` maps to `symbol`.
- `54=1` maps to `BUY`; `54=2` maps to `SELL`.
- `40=1` maps to `MARKET`; `40=2` maps to `LIMIT`.
- `38` maps to `quantity`.
- `44` maps to `limitPrice` for limit orders.

Trade lifecycle shown by the application:

- Order starts as `NEW` in the domain.
- REST submission accepts and persists it as `ACCEPTED`.
- Async processing may leave it `ACCEPTED` with a no-fill execution report, or move it to `FILLED`.
- A fill creates an `ExecutionReport` and a `Trade`.
- `PARTIALLY_FILLED`, `CANCELLED`, and `REJECTED` are modeled but not fully exposed through all workflows yet.

Important honesty:

This is not a FIX engine. It does not implement sessions, sequence numbers, resend requests, heartbeats, BodyLength, CheckSum, dictionaries, counterparty state, or FIX certification behavior. In production, I would use QuickFIX/J for session-level FIX and keep this kind of mapping logic separate.

## 15. Known Limitations And How I Would Improve It

Known limitations:

- No explicit processed-message inbox table for queryable consumer idempotency and retry diagnostics.
- Account and instrument are modeled as identifiers, not persisted reference data with active/suspended status.
- No authentication or authorization.
- No order cancellation API.
- No partial-fill simulation path yet, even though domain states include `PARTIALLY_FILLED`.
- No list/search endpoints or pagination implementation yet.
- No real market data, matching engine, or external venue integration.
- No load tests or capacity sizing.
- No production IaC for AWS deployment.
- Simplified FIX parser is educational only.

Improvements:

- Add processed-message inbox table with message ID, status, attempt count, and timestamps.
- Add DLQ dashboards and poison-message runbooks around broker redelivery.
- Add account/instrument tables and validation.
- Add cancel/replace workflows.
- Add paginated search endpoints with composite indexes.
- Add auth with role-based access.
- Add realistic partial-fill and rejection scenarios.
- Add load tests around API throughput, queue depth, DB locks, and p99 latency.
- Add AWS IaC and deployment pipeline.

## 16. Five Likely Interviewer Questions And Strong Answers

### 1. Why did you separate domain objects from JPA entities?

JPA entities are persistence concerns: they need annotations, no-arg constructors, mutable fields, and database-oriented shapes. The domain records are pure Java and encode business invariants such as order type/price rules, quantity validation, and valid state transitions. Keeping them separate makes the business model easier to test and explain, and prevents persistence framework requirements from driving domain design.

### 2. How does idempotent order submission work?

`POST /api/v1/orders` requires an `Idempotency-Key`. The service canonicalizes the business request fields and hashes them with SHA-256. It then tries to insert an `idempotency_records` row using PostgreSQL `ON CONFLICT DO NOTHING`. If the insert succeeds, this request owns the key and creates the order. If it fails, the service loads the existing record: same hash returns the same order resource and response status; different hash returns `409 Conflict`. Because async processing can update the order, a replay may show current state rather than the original `ACCEPTED` snapshot. This is database-backed, so it works across concurrent requests and multiple service instances.

### 3. What happens if the same JMS message is delivered twice?

The consumer expects at-least-once delivery. `OrderExecutionProcessor` derives a deterministic execution-report ID from the event ID and checks whether that report already exists. It then locks the order row with `PESSIMISTIC_WRITE` and checks again inside the serialized section. Trades also reference the execution report with a unique constraint, so a duplicate fill report cannot create a second trade for the same report. Duplicate events are acknowledged without creating duplicate terminal side effects.

### 4. Where are your transaction boundaries, and what is the main reliability gap?

Order submission is one database transaction for the order, idempotency record, and outbox event. The REST path does not publish directly to JMS. A relay transaction locks due pending outbox rows, publishes them to JMS, and marks them `PUBLISHED` or records retry state. Message consumption is another transaction that writes the execution report, trade, and order status update together. The remaining reliability gap is the classic at-least-once outbox case: if the relay publishes and crashes before marking the row `PUBLISHED`, it may publish the same event again, so the consumer must stay idempotent.

### 5. How would you investigate high p99 latency in this service?

I would start by separating API latency from consumer processing latency. For APIs, I would check request metrics, error rates, thread dumps, Hikari pool wait time, RDS CPU/locks/slow queries, and GC logs. For consumers, I would check processing duration, queue depth, oldest message age, redeliveries, broker health, and database lock contention. If GC pauses align with spikes, I would inspect allocation pressure and heap sizing. If threads are waiting on JDBC or the broker, I would tune database queries, indexes, connection pools, or consumer concurrency before changing JVM flags.
