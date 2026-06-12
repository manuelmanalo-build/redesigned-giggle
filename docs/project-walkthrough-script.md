# Project Walkthrough Script

## 1. 60-Second Overview

This project is a Java 21 Spring Boot backend that simulates a simplified real-time trade processing platform.

The core flow is: a client submits an order over REST, the API validates it, stores it in PostgreSQL, records idempotency state, and writes an `OrderSubmittedEvent` to a transactional outbox in the same database transaction. A relay publishes pending outbox events to a JMS queue. A separate asynchronous consumer receives that event, simulates execution, creates an execution report, creates a trade when the order fills, and updates the order status.

I built it to demonstrate the backend topics that usually come up in senior Java interviews: domain modeling, REST design, SQL constraints and indexes, transaction boundaries, JMS messaging, duplicate-message safety, concurrency, observability, Testcontainers integration tests, CI, and cloud deployment tradeoffs.

The main thing I would emphasize is that this is intentionally not a giant system. It is a focused service where each important backend decision is visible and explainable.

## 2. 3-Minute Architecture Walkthrough

At a high level, this is a modular Spring Boot service with clear package boundaries.

The `api` package owns the REST layer. `OrderController` exposes `POST /api/v1/orders`, `GET /api/v1/orders/{orderId}`, `GET /api/v1/orders/{orderId}/execution-reports`, and `GET /api/v1/orders/{orderId}/trades`. The controller stays thin. It validates request shape and delegates to the application layer.

The `application` package owns orchestration. `OrderApplicationService` handles order submission, idempotency, database writes, and outbox event creation. `OrderExecutionProcessor` handles the asynchronous execution workflow after a JMS message arrives. `ExecutionSimulator` is an abstraction for the market/limit fill logic.

The `domain` package contains the pure Java model. That includes `Order`, `Quantity`, `Price`, `ExecutionReport`, `Trade`, IDs, and enums like `OrderStatus`, `OrderType`, and `ExecutionType`. I kept database annotations out of the pure domain model so the business rules are testable without Spring or JPA.

The `persistence` package contains JPA entities and repositories. PostgreSQL is the source of truth, and Flyway migrations define the schema. The schema has database constraints for important invariants, like positive quantities, valid enum values, market versus limit price rules, and one trade per execution report.

The `messaging` package contains the outbox relay and JMS pieces. There is an `OutboxRelayService`, an `OrderEventPublisher` abstraction, a `JmsOrderEventPublisher` implementation, the `OrderSubmittedEvent` payload, and an `OrderSubmittedEventConsumer`. The API/application layer does not talk directly to `JmsTemplate`.

The `observability` package handles correlation IDs and Micrometer metrics. REST requests get an `X-Correlation-Id`, and that same value is carried into JMS events so API and async processing can be tied together in logs.

So the architecture is a modular monolith, not microservices. I chose that because the interview value is in showing clean boundaries, transaction decisions, and failure-mode thinking without adding unnecessary distributed-system complexity.

## 3. 5-Minute Deep Dive Into Order Submission

Order submission starts in `OrderController.submitOrder`.

The endpoint is `POST /api/v1/orders`. The client must send an `Idempotency-Key` header, and may send `X-Correlation-Id`. If the correlation ID is missing, the correlation filter generates one and stores it in the request context.

The request body includes fields like `clientOrderId`, `accountId`, `symbol`, `side`, `type`, `quantity`, and optional `limitPrice`.

The controller uses Jakarta validation for the API contract, but that is not the only validation. The application service converts the request into the domain model, and the domain model enforces business rules. For example, `Quantity` must be positive, `InstrumentSymbol` cannot be blank, limit orders require a price, and market orders must not include a price.

Inside `OrderApplicationService.submitOrder`, the first important thing is idempotency. The service creates a normalized fingerprint of the request. It canonicalizes the business fields, normalizes the symbol and price, and hashes the result with SHA-256.

Then it attempts to claim the idempotency key using a PostgreSQL insert with `ON CONFLICT DO NOTHING`. That is important because it makes idempotency safe across concurrent requests and across multiple application instances. It is not an in-memory map.

If the claim fails, the service loads the existing idempotency record. If the request hash matches, it returns the same order resource and response status. Because the order may have been processed asynchronously, that replay can show the current order state rather than the original `ACCEPTED` snapshot. If the hash is different, it throws an idempotency conflict and the API returns `409 Conflict`.

If the claim succeeds, the service creates an `Order` domain object, transitions it from `NEW` to `ACCEPTED`, saves it as an `OrderEntity`, and completes the idempotency record with the created order ID and response status.

That order insert and idempotency update happen in the same Spring transaction as the outbox insert. That means the accepted order, replay state, and intent to publish the integration event become durable together.

After the transaction commits, the REST path is done. It does not publish directly to JMS. A scheduled outbox relay polls pending outbox rows, publishes the event payload to `order.submitted`, and marks the row `PUBLISHED`.

The tradeoff is that this is still at-least-once messaging. If the relay publishes to JMS but crashes before marking the outbox row published, it may publish that event again on retry. That is why the consumer is idempotent.

The response currently returns `201 Created` with the accepted order state. Later, if I wanted the API to be more explicitly asynchronous, I could return `202 Accepted`, but because the order itself is durably created synchronously, `201` is reasonable here.

## 4. 5-Minute Deep Dive Into JMS Async Processing

The async side starts with an `OrderSubmittedEvent` published to the `order.submitted` queue.

The event includes the event ID, order ID, client order ID, account ID, symbol, side, type, quantity, limit price, correlation ID, and creation timestamp. The publisher serializes it explicitly as JSON and also sets JMS metadata like event type, event ID, order ID, and correlation ID.

The consumer is `OrderSubmittedEventConsumer`. It receives the JMS text message, deserializes the JSON, restores the correlation ID into logging context, and delegates the business work to `OrderExecutionProcessor`.

The actual processing is transactional. In `OrderExecutionProcessor`, the first thing it does is derive a deterministic execution-report ID from the event ID. That gives the consumer a stable idempotency key. If that execution report already exists, the consumer treats the message as a duplicate and exits safely.

Then it loads the order with a pessimistic write lock using `findByIdForUpdate`. That matters because JMS can redeliver messages, and multiple consumers may be active. The lock serializes processing for the same order.

After acquiring the lock, it checks again whether the deterministic execution report already exists. This second check closes the race where two consumers start at nearly the same time and one wins just before the other acquires the lock.

If the order is missing, the consumer logs and skips. If the order is not in `ACCEPTED` status, it also skips, because it should not reprocess a filled, cancelled, or rejected order.

Then it delegates to `ExecutionSimulator`.

The current simulator is deterministic. Market orders fill completely at the configured simulated market price. Limit buy orders fill if the limit price is greater than or equal to the simulated market price. Limit sell orders fill if the limit price is less than or equal to the simulated market price. If the limit order is not marketable, the system writes a no-fill execution report and leaves the order `ACCEPTED`.

If there is a fill, the processor creates an `ExecutionReport`, creates a `Trade` from that report, saves both, and marks the order filled. Those writes happen in one transaction with the order update.

JMS listener sessions are configured as transacted, so if processing throws an exception, message acknowledgement can roll back and the broker can redeliver. The code also has duplicate protection because at-least-once delivery is the normal expectation with messaging.

The consumer now uses a processed-message inbox table. It claims the event ID before doing business work, marks successful messages `PROCESSED`, marks duplicate observations `DUPLICATE`, and stores failure diagnostics like attempt count and last error when processing throws. The deterministic execution report ID still remains as a business-level safety net.

## 5. 3-Minute Explanation Of Idempotency

There are two idempotency problems in this project.

The first is client retry idempotency for `POST /api/v1/orders`.

Clients must send an `Idempotency-Key`. The service creates a normalized SHA-256 fingerprint of the business request. Then it tries to insert a row into `idempotency_records`. The primary key is the idempotency key, and the insert uses `ON CONFLICT DO NOTHING`.

If the insert succeeds, this request owns the key and creates the order. If the insert does not happen because the key already exists, the service loads the record. If the fingerprint matches, it returns the same order resource and response status. If the fingerprint differs, it returns `409 Conflict`.

That is stronger than an in-memory approach because it works with multiple application instances and concurrent requests.

The second idempotency problem is message consumption.

JMS delivery is at least once. So the consumer assumes the same event can arrive more than once. It derives a deterministic execution-report ID from the event ID. If that report already exists, the event has already been processed. The consumer also locks the order row and checks again inside the lock.

For fills, the trade has a deterministic ID as well, and the database enforces that a trade references one execution report with a unique `execution_report_id`. That means duplicate processing cannot create multiple trades for the same fill report.

The project also has a processed-message inbox table. That gives operational visibility into processed message IDs, duplicate observations, attempts, failures, and the metadata I would need for retry or DLQ investigation.

## 6. 3-Minute Explanation Of Concurrency And Throughput

Concurrency appears in two places: REST submission and JMS consumption.

For REST, multiple clients can submit the same request at the same time. The service does not try to solve that with Java synchronization because that would only work inside one JVM. Instead, PostgreSQL enforces the idempotency key with a primary key and an atomic `ON CONFLICT DO NOTHING` insert.

For JMS, multiple listener threads can consume messages. The system uses a pessimistic write lock when processing an order, so two consumers cannot update the same order lifecycle at the same time.

That is a correctness-first choice. It is easy to reason about and interview-friendly.

The throughput tradeoff is that pessimistic locks can limit performance if many messages target the same order or if processing inside the transaction becomes slow. For this MVP, the transaction is small: load order, simulate execution, insert report, insert trade if needed, update order.

If I needed higher throughput, I would look at a few options:

- Use conditional SQL updates or optimistic locking instead of pessimistic locking.
- Partition messages by order ID or account ID so related work is serialized before it hits the database.
- Tune JMS listener concurrency and Hikari pool size together.
- Add composite indexes for query paths before scaling the database vertically.
- Watch queue depth, message age, DB locks, connection pool wait time, and p99 processing duration.

The important point is that adding more consumers is not always the fix. If the database or broker is saturated, more workers can make latency worse.

## 7. 3-Minute Explanation Of SQL Schema And Indexes

The schema is managed with Flyway and PostgreSQL is the source of truth.

There are four core tables.

`orders` stores the current order state. It has the system ID, client order ID, account ID, symbol, side, type, status, quantity, limit price, filled quantity, and timestamps.

`execution_reports` stores lifecycle events from processing. It references `orders`, records execution type, resulting order status, executed quantity, execution price, message, and created timestamp.

`trades` stores actual fills. It references both the order and the execution report that created the trade. That execution report link is unique, so one fill report cannot create multiple trades.

`idempotency_records` stores the idempotency key, request hash, created order ID, response status, and created timestamp.

The schema uses constraints as a second line of defense behind application validation. For example, quantities must be positive, filled quantity cannot exceed order quantity, side/type/status values must be valid enums, market orders cannot have a limit price, limit orders must have one, and fill execution reports must have quantity and price.

The indexes match current and planned access patterns:

- `orders.client_order_id` supports client/FIX-style lookup.
- `orders.account_id`, `orders.symbol`, and `orders.status` support operational filtering.
- `execution_reports.order_id` supports lifecycle history reads.
- `trades.order_id` supports trade history by order.
- `trades.execution_report_id` supports the one-report-to-one-trade invariant.
- `idempotency_records.idempotency_key` makes the retry path explicit, even though the primary key already provides that access path.

For operational list endpoints, the schema has composite indexes like `(account_id, created_at DESC)`, `(status, created_at DESC)`, and `(order_id, created_at DESC)` so common filtered searches can still return newest-first pages efficiently.

## 8. 2-Minute JVM/GC Talking Point

For JVM and GC, I would focus on latency and allocation pressure.

This service allocates objects through JSON DTOs, domain records, JPA entities, BigDecimal prices, log context, and JMS payload serialization. That is normal for a Spring Boot service, but under load it can show up as increased allocation rate and more frequent GC.

I would start with G1GC, bounded heap settings, and GC logs for local profiling. The repo includes JVM/GC notes and helper scripts for local GC logging.

If p99 latency increased, I would not immediately tune the heap. I would first check whether the spike lines up with GC pauses. If it does, I would inspect allocation rate, object churn, heap sizing, and pause times. If it does not, I would look at thread dumps, Hikari pool wait, PostgreSQL locks, slow queries, broker queue depth, and redeliveries.

The main interview point is that JVM tuning is only one part of latency investigation. In a service like this, database locks or connection pool starvation are just as likely as GC to cause p99 problems.

## 9. 2-Minute Testing, CI/CD Talking Point

The testing strategy is layered.

There are pure unit tests for domain behavior like order validation, state transitions, value objects, execution reports, trades, and the execution simulator.

There are controller tests for REST validation and error handling.

There are PostgreSQL Testcontainers integration tests for persistence, schema constraints, idempotency uniqueness, trade persistence, and database-level invariants.

There are messaging tests at multiple levels: a unit test for the JMS publisher, direct consumer integration tests against PostgreSQL, and a broker-backed end-to-end test that submits an order over REST, publishes to Artemis, consumes asynchronously, and verifies the order becomes filled with an execution report and trade.

The CI pipeline uses GitHub Actions. It sets up Java 21, caches Maven dependencies, runs compile checks, runs `./mvnw -B clean verify`, and builds a Docker image.

The main point I would make is that the tests prove behavior against real dependencies where it matters. I avoided H2 for persistence because PostgreSQL constraints, locking, and SQL behavior are part of the system design.

## 10. Strong Closing Answer: What I Would Improve Next

The next thing I would improve is consumer-side operational visibility.

The project now uses a transactional outbox. Order submission writes the order, idempotency record, and outbox event in one transaction. A separate relay publishes those events to the broker, marks them published, and retries failures. That gives durability and operational visibility for producer-side messaging.

The project also uses a processed-message inbox. That makes duplicate observations and failed attempts queryable. The next production step would be broker-level DLQ integration: either a DLQ listener or an operational reconciliation job that marks poisoned messages `DEAD_LETTERED` and links them to broker diagnostics.

After that, I would add authentication and authorization, explicit order amendment versions, amendment events for replace, keyset pagination for deep searches, partial-fill simulation, and production-like capacity tests built from the current local load diagnostics.

So my closing summary would be: this project is interview-ready as a compact backend system because it demonstrates the important decisions clearly. For production, I would harden the async reliability model, security model, and operational runbooks before scaling it further.
