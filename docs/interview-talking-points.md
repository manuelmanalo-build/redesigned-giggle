# Interview Talking Points

## 1. 30-Second Project Summary

This project is a Java 21 Spring Boot backend that simulates a simplified real-time trade processing platform. A client submits an order through `POST /api/v1/orders`; the service validates it, stores it in PostgreSQL, records an idempotency claim, and writes an `OrderSubmittedEvent` to a transactional outbox in the same database transaction. A relay publishes pending outbox events to the `order.submitted` JMS queue, and an asynchronous consumer records an inbox row, locks the order row, simulates execution, writes an execution report, creates a trade for fills, and updates order state. The project demonstrates domain modeling, REST APIs, SQL constraints, outbox/inbox messaging patterns, concurrency-safe processing, observability, Testcontainers-based integration tests, CI, and cloud deployment tradeoffs.

## 2. Architecture Explanation

The codebase is a modular monolith with explicit package boundaries:

- `api`: `OrderController`, request/response DTOs, error responses, and `GlobalApiExceptionHandler`.
- `application`: orchestration services such as `OrderApplicationService`, `ReferenceDataValidationService`, `OutboxEventWriter`, `OrderSubmittedMessageInboxProcessor`, `OrderExecutionProcessor`, and `ExecutionSimulator`.
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
5. `ReferenceDataValidationService` verifies the account and instrument exist and are active.
6. `IdempotencyRecordJpaRepository.claimSubmission` uses PostgreSQL `ON CONFLICT DO NOTHING` to claim the idempotency key.
7. The service saves the order, completes the idempotency record, and inserts a pending outbox row in one Spring transaction.
8. `OutboxRelayService` polls due pending rows, publishes the JSON event to JMS, and marks successful rows `PUBLISHED`.
9. Failed relay attempts increment `attempt_count`, store `last_error`, and set `next_attempt_at` for retry.
10. `OrderSubmittedEventConsumer` receives the JSON JMS message and delegates to `OrderSubmittedMessageInboxProcessor`.
11. The inbox processor claims `eventId` in `processed_messages`, skips terminal duplicates, and records retry diagnostics.
12. `OrderExecutionProcessor` uses a pessimistic write lock, deterministic execution-report IDs, and database constraints to avoid duplicate side effects.
13. Query endpoints return order state, execution reports, and trades from PostgreSQL.

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

The domain model is not just getters and setters. It encodes rules around order construction, price requirements, quantity validation, state transitions, and trade creation. Reference-data checks live in an application service because they require repositories and current database state, while controllers only validate transport shape. Persistence entities exist because JPA has different needs from the domain model, so mapping is explicit.

## 4. Data Structures Used And Why

- Relational tables store authoritative state and reference data: `accounts`, `instruments`, `orders`, `execution_reports`, `trades`, `idempotency_records`, `outbox_events`, and `processed_messages`.
- Primary keys enforce identity for orders, execution reports, trades, and idempotency records.
- `idempotency_records.idempotency_key` acts as a deduplication set for client retries.
- `accounts.id` and `instruments.symbol` are reference-data lookup keys for order validation.
- `outbox_events(status, next_attempt_at, created_at)` acts as a durable work queue for integration-event relay.
- `processed_messages.message_id` acts as a consumer inbox key for duplicate detection and retry diagnostics.
- Deterministic execution-report and trade IDs derived from event IDs make duplicate message handling idempotent.
- `trades.execution_report_id` is unique, enforcing one trade per fill execution report.
- Indexes support expected lookup paths:
  - `orders(client_order_id)` for client/FIX-style lookup.
  - `accounts(status)` and `instruments(status)` for operational reference-data review.
  - `orders(account_id)`, `orders(symbol)`, and `orders(status)` for operational filtering.
  - `execution_reports(order_id)` and `trades(order_id)` for order lifecycle history.
  - `trades(execution_report_id)` for the fill-report-to-trade invariant.
  - `outbox_events(status, next_attempt_at, created_at)` for due-event polling.
  - `outbox_events(aggregate_type, aggregate_id)` for diagnostics by order.
  - `processed_messages(status)` and `(consumer_name, status)` for retry/DLQ investigation.
- `LinkedHashMap` in the simplified FIX parser preserves tag insertion order, which is useful for deterministic parsing behavior and debugging.
- Queues provide asynchronous buffering between order submission and execution processing.

## 5. Multithreading And Concurrency Concepts Demonstrated

- REST requests are handled concurrently by the web container, so idempotency cannot rely on in-memory checks.
- Client retry concurrency is handled at the database layer using `INSERT ... ON CONFLICT DO NOTHING` in `IdempotencyRecordJpaRepository.claimSubmission`.
- JMS consumers may run concurrently. The listener concurrency is configurable through Spring JMS settings.
- Outbox relay polling can also run concurrently across instances; `FOR UPDATE SKIP LOCKED` prevents normal duplicate work on the same pending outbox row.
- Consumer inbox rows are claimed with a database insert and then locked with `PESSIMISTIC_WRITE`, so concurrent duplicate deliveries serialize on `processed_messages.message_id`.
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
- The repo also includes `docs/performance-load-testing.md`, a k6 load script, and PostgreSQL lock diagnostics for local p95/p99 investigation.
- G1GC is a reasonable default for a Spring Boot service; the goal is predictable pauses, not maximum raw throughput.

Concise high-latency answer:

I would identify whether the p99 is in the REST path, the outbox relay, or JMS consumption. Then I would compare k6 latency, Micrometer timers, Hikari active/pending/acquire metrics, PostgreSQL lock diagnostics, Artemis queue depth/redeliveries, CPU/thread dumps, and GC logs. If GC pauses align with the spikes, I would inspect allocation rate and heap pressure. If GC is quiet but threads wait on JDBC or broker calls, I would treat it as a database, broker, or queueing problem before tuning JVM flags.

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
- Reference-data validation is enforced in the application layer: only `ACTIVE` accounts and `ACTIVE` instruments can submit orders.
- Unknown accounts, suspended/closed accounts, unknown symbols, halted instruments, and delisted instruments return `400 Bad Request` without persisting rejected orders.
- Reference data is manageable through small REST endpoints that create, update, list, and retrieve accounts and instruments.
- Cancel and replace are implemented as idempotent write endpoints with order-row locking, state checks, and execution-report audit records.
- Errors use a consistent `ApiErrorResponse` shape through `GlobalApiExceptionHandler`.
- `409 Conflict` is used when an idempotency key is reused with a different normalized request.
- Query endpoints verify that the order exists before returning execution reports or trades.
- The POST currently returns `201 Created` after persistence and outbox insertion; a future asynchronous API could return `202 Accepted` if execution ownership moves further into the async layer.

Tradeoff:

The order API is intentionally small and avoids order list/search endpoints for now. The schema already has indexes that would support later account, symbol, and status filtering.

## 8. SQL Schema, Indexing, And Transaction Discussion

PostgreSQL is the source of truth. Flyway migrations define and harden the schema:

- `V2__create_core_persistence_tables.sql` creates `orders`, `execution_reports`, `trades`, and `idempotency_records`.
- `V3__harden_core_constraints.sql` adds enum checks, market/limit price consistency, execution-report fill-field consistency, and response-status checks.
- `V4__link_trades_to_execution_reports.sql` adds `trades.execution_report_id`, a foreign key, a unique constraint, and an index.
- `V5__create_outbox_events.sql` creates the transactional outbox table and relay indexes.
- `V6__create_processed_messages.sql` creates the consumer inbox table and diagnostic indexes.
- `V7__create_reference_data.sql` creates `accounts` and `instruments` with seed rows for active and inactive cases.
- `V8__allow_replaced_execution_reports.sql` extends execution-report constraints for replace audit records.
- `V9__add_search_composite_indexes.sql` adds composite indexes for paginated operational searches.

Important constraints:

- Order quantity must be positive.
- Filled quantity cannot exceed order quantity.
- Market orders must not have limit price.
- Limit orders must have limit price.
- Fill execution reports must have executed quantity and execution price.
- Non-fill execution reports must not have fill fields.
- Cancel and replace execution reports carry messages but no fill quantity or price.
- Trades require positive quantity and price.
- One execution report can create at most one trade.
- Outbox status must be `PENDING`, `PUBLISHED`, or `FAILED`.
- Outbox attempt count cannot be negative.
- Processed-message status must be `RECEIVED`, `PROCESSED`, `FAILED`, `DUPLICATE`, or `DEAD_LETTERED`.
- Processed-message attempt count cannot be negative.
- Account status must be `ACTIVE`, `SUSPENDED`, or `CLOSED`.
- Instrument status must be `ACTIVE`, `HALTED`, or `DELISTED`.
- Instrument asset class must be `EQUITY`, `ETF`, `OPTION`, `FUTURE`, or `CRYPTO`.

Transaction boundaries:

- Order submission stores the order, idempotency record, and outbox event in one `@Transactional` method.
- Reference-data validation happens before the idempotency claim, so invalid account/symbol requests do not create idempotency or outbox rows.
- The REST request does not publish directly to JMS.
- The outbox relay locks due rows, publishes to JMS, and marks rows `PUBLISHED` or records retry state in its own transaction.
- Message processing claims the inbox row, writes execution report, trade, order status update, and marks the inbox row `PROCESSED` in one `@Transactional` method.
- If message processing fails, that transaction rolls back and a separate diagnostic transaction marks the inbox row `FAILED` with `last_error` and `attempt_count`.
- Read endpoints use `@Transactional(readOnly = true)`.

Indexing discussion:

The indexes match current query paths rather than every possible column. Search endpoints default to `createdAt DESC`, so indexes such as `(account_id, created_at DESC)`, `(symbol, created_at DESC)`, `(status, created_at DESC)`, `(account_id, status, created_at DESC)`, `(execution_type, created_at DESC)`, and `(order_id, created_at DESC)` support selective filters plus chronological pagination. This avoids indexing every filter combination while covering realistic operational views.

Pagination tradeoff:

The API uses page/size pagination with a max page size of `100` because it is simple for demos and admin-style views. For very large tables or deep scrolling, I would add keyset pagination using `(created_at, id)` and return a cursor to avoid expensive offsets.

Slow SQL investigation:

I would start with application metrics and logs to identify the endpoint and parameters, then run `EXPLAIN (ANALYZE, BUFFERS)` for the generated SQL against representative data. I would check whether the filter matches an index prefix, whether PostgreSQL is scanning too many rows, whether statistics are stale, and whether sort or pagination is spilling. Then I would decide between query changes, a more selective composite index, cursor pagination, or data partitioning.

## 9. JMS/Messaging Discussion

Messaging components:

- `OrderEventPublisher`: application-facing abstraction.
- `JmsOrderEventPublisher`: Jackson JSON serialization and JMS send through `JmsTemplate`.
- `OutboxEventWriter`: serializes order-submitted events into `outbox_events`.
- `OutboxRelayService`: polls pending outbox rows, publishes them, and records success/failure state.
- `OutboxRelayScheduler`: fixed-delay trigger for the relay.
- `OrderSubmittedMessageInboxProcessor`: processed-message guard and diagnostics layer.
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
- Consumer failures are visible in `processed_messages.status`, `attempt_count`, `last_error`, `last_seen_at`, and `correlation_id`.
- Listener sessions are transacted.
- Duplicate delivery is handled with the processed-message inbox, deterministic execution-report IDs, and unique trade-to-report constraints.
- Broker-backed Testcontainers coverage verifies a real REST-to-outbox-to-Artemis-to-database path.

Known messaging tradeoff:

The outbox and inbox improve reliability and diagnostics, but this is still an at-least-once design. If the relay publishes to JMS and the process crashes before marking the outbox row `PUBLISHED`, the event can be published again on retry. If the consumer fails, it records a `FAILED` inbox row and rethrows so the broker can redeliver. Broker-level DLQ routing is still external configuration, with `DEAD_LETTERED` reserved for future reconciliation.

## 10. Distributed Systems Tradeoffs

Tradeoffs already visible in the code:

- At-least-once messaging means duplicate deliveries are expected, so consumers must be idempotent.
- Transactional outbox prevents losing the intent to publish when the database commit succeeds but the broker is temporarily unavailable.
- The relay can still duplicate messages during crash recovery, so idempotent consumers are required.
- The processed-message inbox makes duplicate deliveries and failed attempts queryable without replacing domain-level idempotency.
- Database constraints are used as the final line of defense for invariants.
- Correlation IDs are propagated through REST and JMS for traceability.
- The service chooses a simple polling outbox over a full CDC platform for MVP explainability.
- Pessimistic locking is simple and correct for duplicate processing, but it may limit throughput.
- PostgreSQL is a single source of truth, which simplifies consistency but means database health dominates system availability.

How to improve for production:

- Add DLQ dashboards and poison-message handling runbooks.
- Add authentication/authorization and richer account/instrument lifecycle workflows.
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
- Idempotency, duplicate message behavior, and inbox failure diagnostics are covered.
- Broker-backed E2E coverage proves the actual outbox-to-JMS path.
- CI runs `./mvnw -B clean verify`.

Testing gaps to discuss honestly:

- No load/performance tests yet.
- No authentication/authorization tests because auth is not implemented.
- No migration compatibility tests against older production-like data.
- No full broker DLQ routing test beyond transacted listener behavior, duplicate safety, and app-side failure diagnostics.

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

I would containerize the service and run it on ECS Fargate behind an ALB, use RDS PostgreSQL as the source of truth, and use Amazon MQ if I want JMS compatibility. I would put credentials in Secrets Manager, encrypt data with KMS, use IAM task roles, and send structured logs and Micrometer metrics to CloudWatch. The outbox gives producer-side retry visibility, and the inbox gives consumer-side attempt diagnostics; before production I would add broker DLQ dashboards and alarms.

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
- Client cancel moves open orders to `CANCELLED` and records a cancel execution report.
- Client replace amends open limit orders in place and records a replace execution report.
- `PARTIALLY_FILLED` is modeled and protected by cancel/replace guards, but the simulator does not yet generate partial fills.

Important honesty:

This is not a FIX engine. It does not implement sessions, sequence numbers, resend requests, heartbeats, BodyLength, CheckSum, dictionaries, counterparty state, or FIX certification behavior. In production, I would use QuickFIX/J for session-level FIX and keep this kind of mapping logic separate.

## 15. Known Limitations And How I Would Improve It

Known limitations:

- No broker DLQ listener or administrative reconciliation job that marks inbox rows `DEAD_LETTERED`.
- Reference data management is intentionally minimal and does not include delete endpoints.
- No authentication or authorization.
- Replace updates orders in place rather than preserving explicit order versions.
- Replace does not publish a new JMS event; a production venue adapter would usually emit an amendment event.
- No partial-fill simulation path yet, even though domain states include `PARTIALLY_FILLED`.
- No real market data, matching engine, or external venue integration.
- Load testing is local and diagnostic only; there is no formal capacity model, soak test, or production-like benchmark environment.
- No production IaC for AWS deployment.
- Simplified FIX parser is educational only.

Improvements:

- Add DLQ dashboards and poison-message runbooks around broker redelivery.
- Add secured account/instrument administration and richer validation rules such as permissioned trading, tick-size enforcement, and venue eligibility.
- Add explicit order versioning and amendment events for replace workflows.
- Add keyset pagination for deep operational searches.
- Add auth with role-based access.
- Add realistic partial-fill and rejection scenarios.
- Promote local load diagnostics into repeatable capacity tests only when realistic data volume and production-like infrastructure exist.
- Add AWS IaC and deployment pipeline.

## 16. Five Likely Interviewer Questions And Strong Answers

### 1. Why did you separate domain objects from JPA entities?

JPA entities are persistence concerns: they need annotations, no-arg constructors, mutable fields, and database-oriented shapes. The domain records are pure Java and encode business invariants such as order type/price rules, quantity validation, and valid state transitions. Keeping them separate makes the business model easier to test and explain, and prevents persistence framework requirements from driving domain design.

### 2. How does idempotent order submission work?

`POST /api/v1/orders` requires an `Idempotency-Key`. The service canonicalizes the business request fields and hashes them with SHA-256. It first builds the domain order and validates account/instrument reference data. Only valid active accounts and instruments proceed to the idempotency claim. The service then tries to insert an `idempotency_records` row using PostgreSQL `ON CONFLICT DO NOTHING`. If the insert succeeds, this request owns the key and creates the order. If it fails, the service loads the existing record: same hash returns the same order resource and response status; different hash returns `409 Conflict`. Because async processing can update the order, a replay may show current state rather than the original `ACCEPTED` snapshot. This is database-backed, so it works across concurrent requests and multiple service instances.

### 3. What happens if the same JMS message is delivered twice?

The consumer expects at-least-once delivery. `OrderSubmittedMessageInboxProcessor` first claims the event ID in `processed_messages`; terminal messages are skipped and marked as duplicate observations. The business layer still derives a deterministic execution-report ID from the event ID and locks the order row with `PESSIMISTIC_WRITE`. Trades also reference the execution report with a unique constraint, so even without the inbox a duplicate fill report cannot create a second trade for the same report. Duplicate events are acknowledged without creating duplicate terminal side effects.

### 4. Where are your transaction boundaries, and what is the main reliability gap?

Order submission is one database transaction for the order, idempotency record, and outbox event. The REST path does not publish directly to JMS. A relay transaction locks due pending outbox rows, publishes them to JMS, and marks them `PUBLISHED` or records retry state. Message consumption is another transaction that claims the inbox row, writes the execution report, trade, order status update, and marks the message `PROCESSED`. If processing fails, the business transaction rolls back and a separate diagnostic transaction stores the failure before the exception is rethrown for broker redelivery. The remaining gap is broker-level DLQ integration, which is documented but not fully automated.

### 5. How would you investigate high p99 latency in this service?

I would start by separating API latency from consumer processing latency. For APIs, I would check k6 p95/p99, request metrics, error rates, Hikari active/pending/acquire metrics, PostgreSQL locks/slow queries, thread dumps, and GC logs. For consumers, I would check `trade.messages.processing.duration`, Artemis queue depth, oldest message age, redeliveries, broker health, `processed_messages` failures, and database lock contention. If GC pauses align with spikes, I would inspect allocation pressure and heap sizing. If threads are waiting on JDBC or the broker, I would tune queries, indexes, pool sizing, transaction scope, or consumer concurrency before changing JVM flags.
