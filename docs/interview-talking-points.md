# Interview Talking Points

## 1. 60-Second Project Summary

This is a Java 21 Spring Boot backend that models a simplified real-time trade processing platform. A client submits an order through `POST /api/v1/orders`; the service validates the request, checks active account and instrument reference data, claims the REST idempotency key in PostgreSQL, stores the order, stores the idempotent response snapshot, and writes an `OrderSubmittedEvent` to a transactional outbox in the same database transaction.

A scheduled `OutboxRelayService` publishes pending outbox rows to the `order.submitted` JMS queue. The JMS consumer records the event in `processed_messages`, locks the order row, simulates execution, writes an execution report, books a trade when there is a fill, and updates order state. The API also supports cancel, replace, reference-data management, paginated operational search, observability metrics, and local load/performance diagnostics.

The project is intentionally a modular monolith, not a fleet of microservices. The interview value is that the important backend decisions are visible: domain modeling, REST contracts, SQL constraints and indexes, transaction boundaries, outbox/inbox reliability, idempotency, concurrent processing, p99 latency investigation, Testcontainers, CI, and AWS tradeoffs.

## 2. Architecture Overview

The package boundaries are deliberately explicit:

- `api`: REST controllers, DTOs, page responses, and `GlobalApiExceptionHandler`.
- `application`: orchestration services such as `OrderApplicationService`, `ReferenceDataValidationService`, `SearchApplicationService`, `OutboxEventWriter`, `OrderSubmittedMessageInboxProcessor`, `OrderExecutionProcessor`, and `ExecutionSimulator`.
- `domain`: pure Java records/enums such as `Order`, `Quantity`, `Price`, `ExecutionReport`, `Trade`, `OrderStatus`, and `ExecutionType`.
- `persistence`: JPA entities, repositories, specifications, and Flyway schema.
- `messaging`: `OrderSubmittedEvent`, `JmsOrderEventPublisher`, `OutboxRelayService`, `OutboxRelayScheduler`, and `OrderSubmittedEventConsumer`.
- `observability`: correlation ID filter and Micrometer-backed trade metrics.
- `fix`: simplified educational FIX parser and New Order Single mapper.

The main write flow is:

1. `OrderController.submitOrder` receives `POST /api/v1/orders`.
2. `CorrelationIdFilter` resolves or creates `X-Correlation-Id`.
3. Bean validation checks request shape.
4. `OrderApplicationService` builds the domain order and validates domain invariants.
5. `ReferenceDataValidationService` verifies the account and instrument are active.
6. `IdempotencyRecordJpaRepository.claimRequest` claims the key with PostgreSQL `ON CONFLICT DO NOTHING`.
7. One transaction saves the order, response snapshot, and outbox event.
8. `OutboxRelayService` polls due `PENDING` rows with `FOR UPDATE SKIP LOCKED`.
9. `JmsOrderEventPublisher` publishes the JSON event to Artemis.
10. `OrderSubmittedEventConsumer` receives the event and delegates to the inbox processor.
11. `OrderSubmittedMessageInboxProcessor` claims `eventId` in `processed_messages`.
12. `OrderExecutionProcessor` locks the order, simulates execution, writes reports/trades, and updates status.

That gives a clean story: controllers are thin, business rules live in domain/application services, persistence is explicit, and messaging is isolated behind the outbox and publisher abstraction.

## 3. Why Transactional Outbox Was Added

Originally, a simple service could save an order and immediately publish to JMS. The problem is the split between the database transaction and the broker send:

- Database commit succeeds, JMS publish fails: the order exists but no consumer ever processes it.
- JMS publish succeeds, database transaction later rolls back: the consumer receives an event for data that does not exist.

The current code fixes that by writing `outbox_events` in the same transaction as the accepted order and `idempotency_records` update. The REST path no longer talks directly to `JmsTemplate`. `OutboxEventWriter` serializes the event into the database, and `OutboxRelayService` later publishes due rows.

This is easy to explain in an interview because it is not a full CDC platform. It is a small polling outbox with:

- `status`: `PENDING`, `PUBLISHED`, or `FAILED`.
- `attempt_count`, `last_error`, and `next_attempt_at` for retry visibility.
- `published_at` for operational confirmation.
- `FOR UPDATE SKIP LOCKED` so multiple app instances can poll without normally processing the same row.
- Configurable `trade.outbox.batch-size`, `max-attempts`, `initial-backoff-ms`, and relay interval.

The remaining honest tradeoff is that outbox does not make delivery exactly once. If the relay publishes to JMS and crashes before marking the row `PUBLISHED`, the relay may publish again. That is why the consumer still has to be idempotent.

## 4. Why Inbox / Processed-Message Tracking Was Added

JMS should be treated as at-least-once delivery. Redelivery can happen after consumer failure, broker retry, network interruption, or outbox relay crash recovery. The consumer cannot assume each `OrderSubmittedEvent` arrives once.

The project adds `processed_messages` as an inbox/diagnostics table. `OrderSubmittedMessageInboxProcessor` uses the event's `eventId` as `message_id` and records:

- `event_type`
- `aggregate_id`
- `consumer_name`
- `status`
- `first_seen_at`
- `last_seen_at`
- `processed_at`
- `attempt_count`
- `last_error`
- `correlation_id`

The inbox has two jobs. First, it avoids reprocessing messages already marked `PROCESSED`. Second, it gives an operator a place to investigate failures and duplicate observations without scraping logs.

The inbox is not the only correctness mechanism. `OrderExecutionProcessor` also derives deterministic execution-report IDs from the event ID, locks the order row, and the database enforces one trade per execution report through `trades.execution_report_id`. That is the right layering: diagnostics help operations, but domain/data constraints still defend correctness.

## 5. Idempotency Strategy Across REST And JMS

REST idempotency:

- `POST /api/v1/orders`, `/cancel`, and `/replace` require `Idempotency-Key`.
- `OrderApplicationService` normalizes the business request and hashes it with SHA-256.
- `idempotency_records.idempotency_key` is the primary key.
- Claiming a key uses `INSERT ... ON CONFLICT DO NOTHING`.
- Same key and same normalized request returns the stored response status/body snapshot.
- Same key and different request returns `409 Conflict`.
- Invalid reference-data requests are rejected before claiming idempotency, so unknown/suspended accounts and unknown/halted/delisted instruments do not create order, idempotency, or outbox rows.

JMS idempotency:

- The outbox event has an `eventId`.
- The consumer claims `eventId` in `processed_messages`.
- Already processed messages are skipped safely.
- The execution report ID is deterministic from the event ID.
- The order is loaded with `PESSIMISTIC_WRITE`.
- The trade points to the execution report, and `trades.execution_report_id` is unique.

The short answer I would give: the REST layer handles client retries with a database-backed idempotency key and response snapshot; the JMS layer handles at-least-once delivery with an inbox row, deterministic IDs, row locks, and database uniqueness.

## 6. Trade Lifecycle Supported

The current lifecycle is intentionally simplified but realistic enough to discuss:

- Submit: `POST /api/v1/orders` creates an order request.
- Accept/reject: syntactically valid requests with active account/instrument are accepted; invalid reference data is hard rejected with `400` and no persisted order.
- Execute: accepted orders emit `OrderSubmittedEvent` through the outbox and JMS.
- Fill/no-fill: market orders fill at the simulated market price; marketable limit orders fill; non-marketable limit orders stay `ACCEPTED` with a no-fill report.
- Partially fill/fill: the domain supports `PARTIALLY_FILLED` and `FILLED`; current simulator fills fully or no-fills, while partial-fill state is covered by cancel/replace guards and persistence tests.
- Cancel: `POST /api/v1/orders/{orderId}/cancel` moves `ACCEPTED` or `PARTIALLY_FILLED` orders to `CANCELLED` and writes a `CANCELLED` execution report. Existing trades are preserved.
- Replace: `POST /api/v1/orders/{orderId}/replace` amends open limit orders in place, writes a `REPLACED` execution report, and for `ACCEPTED` orders writes another outbox event so the amended order can be re-evaluated.
- Book trade: a fill creates an `ExecutionReport` and a `Trade`; the trade is tied to the fill report.

The simplification I would call out: replace updates the current order row rather than keeping an explicit version history, and accepted replace reuses `OrderSubmittedEvent` instead of a dedicated amendment event. That keeps the MVP readable; a production venue adapter would model amendment events and versioned order state.

## 7. SQL And Indexing Explanation

PostgreSQL is the source of truth and Flyway owns schema changes.

Main tables:

- `accounts`: reference data with `ACTIVE`, `SUSPENDED`, `CLOSED`.
- `instruments`: reference data with `ACTIVE`, `HALTED`, `DELISTED`.
- `orders`: current order state.
- `execution_reports`: lifecycle and execution audit trail.
- `trades`: booked fills.
- `idempotency_records`: REST retry state and response snapshots.
- `outbox_events`: producer-side durable event relay.
- `processed_messages`: consumer-side inbox and retry diagnostics.

Important constraints:

- Positive quantities and prices.
- Filled quantity cannot exceed order quantity.
- Market orders cannot have limit price; limit orders must have price.
- Execution report fill fields must match execution type.
- One execution report can create at most one trade.
- Valid enum/status checks for order, execution, outbox, inbox, account, and instrument state.

Indexing is aligned to real access patterns:

- `orders(client_order_id)` for client/FIX-style lookup.
- `orders(account_id, created_at DESC)` for account order history.
- `orders(symbol, created_at DESC)` for symbol activity.
- `orders(status, created_at DESC)` for operational queues.
- `orders(account_id, status, created_at DESC)` for account/status screens.
- `execution_reports(order_id, created_at DESC)` for order lifecycle history.
- `execution_reports(execution_type, created_at DESC)` for execution-report operations.
- `trades(account_id, created_at DESC)`, `trades(symbol, created_at DESC)`, and `trades(order_id, created_at DESC)` for trade views.
- `outbox_events(status, next_attempt_at, created_at)` for relay polling.
- `processed_messages(status)` and `(consumer_name, status)` for retry/DLQ investigation.

The search API sorts by `createdAt DESC, id DESC` by default. The `id` tie-breaker makes pagination deterministic when multiple rows share the same timestamp.

## 8. Search API Explanation

The project now has operational search endpoints:

- `GET /api/v1/orders`
- `GET /api/v1/execution-reports`
- `GET /api/v1/trades`

Order filters:

- `accountId`
- `symbol`
- `status`
- `side`
- `type`
- `createdFrom`
- `createdTo`
- `clientOrderId`

Execution-report filters:

- `orderId`
- `executionType`
- `orderStatus`
- `createdFrom`
- `createdTo`

Trade filters:

- `orderId`
- `accountId`
- `symbol`
- `side`
- `createdFrom`
- `createdTo`

The implementation uses `SearchController`, `SearchApplicationService`, and JPA specifications. Page size defaults to `20` and is capped at `100`. Sort fields are whitelisted; callers cannot inject arbitrary SQL sort expressions. This is a good interview point because it is simple but production-minded: useful operational views, controlled filters, max page size, deterministic sorting, and indexes that support the common query prefixes.

The known tradeoff is offset pagination. It is fine for the demo and admin-style views. For high-volume deep scrolling, I would add keyset pagination using `(created_at, id)` and return a cursor.

## 9. Performance And Load-Testing Explanation

The project includes a safe local performance setup, not a pretend production benchmark:

- `scripts/load/order-load-test.js`: k6 script that submits mixed market, fillable-limit, non-fillable-limit, and invalid-reference-data orders.
- `scripts/load/README.md`: how to run the load script and read the results.
- `docs/performance-load-testing.md`: diagnostic guide for latency, queue depth, Hikari pressure, and locks.
- `scripts/sql/db-lock-diagnostics.sql`: PostgreSQL queries against `pg_stat_activity` and `pg_locks`.
- `scripts/run-local-with-gc-logs.sh`: local JVM/GC logging helper.
- `scripts/run-load-demo.sh`: lightweight curl-based smoke/load demo.

The k6 script reports request latency and throughput. The application exposes Micrometer/Actuator metrics, including HTTP percentiles, message processing duration percentiles, and Hikari acquire percentiles:

- `http.server.requests`
- `trade.messages.processing.duration`
- `hikaricp.connections.active`
- `hikaricp.connections.idle`
- `hikaricp.connections.pending`
- `hikaricp.connections.acquire`

For this project, the performance story is not "I tuned everything." It is "I built the hooks needed to diagnose where pressure is coming from: API, outbox relay, broker queue, consumer, database locks, Hikari, or JVM."

## 10. JVM / GC And P99 Latency Investigation Answer

My p99 answer would be:

First, I would split the path. Is p99 high on `POST /api/v1/orders`, the search endpoints, the outbox relay, or the JMS consumer? Then I would correlate:

- k6 p95/p99 latency.
- `http.server.requests` percentiles.
- `trade.messages.processing.duration` percentiles.
- Hikari active/pending/acquire metrics.
- PostgreSQL lock diagnostics from `scripts/sql/db-lock-diagnostics.sql`.
- Outbox backlog by `outbox_events.status`.
- Consumer failures/backlog by `processed_messages.status`.
- Artemis queue depth/redeliveries/DLQ state.
- CPU, thread dumps, and GC logs.

For the JVM side, I would check whether GC pause timestamps line up with p99 spikes. This service allocates through JSON DTOs, Jackson event payloads, JPA entities, domain records, `BigDecimal`, and logging MDC. If GC aligns with the spikes, I would look at allocation rate, heap sizing, object churn, and pause time. If GC is quiet but Hikari pending threads or lock waits are high, I would not tune JVM flags first; I would fix database or connection-pool pressure.

The short senior answer: prove where the time is spent before tuning. GC is one possible cause, but in this service p99 is just as likely to come from locks, connection acquisition, slow queries, broker backlog, or over-aggressive consumer concurrency.

## 11. Queue Depth And Backpressure Answer

Queue depth means the system is accepting or publishing work faster than consumers can finish it.

I would look at three queues/backlogs:

- `outbox_events` stuck in `PENDING`: the relay is slow, disabled, or failing to publish.
- Artemis `order.submitted` depth growing: the broker has messages but consumers are not keeping up.
- `processed_messages` in `FAILED`: consumers are receiving work but business processing is failing.

My first response would not be "add consumers." I would check:

- Consumer processing duration.
- Hikari pending connections.
- PostgreSQL locks.
- Broker redeliveries.
- CPU and thread saturation.
- Whether one hot order/account is causing lock contention.

If the database is healthy and consumers are CPU-bound, increasing JMS listener concurrency may help. If Hikari is saturated or lock waits are high, more consumers can make the backlog worse. In production I would scale based on queue depth plus oldest message age, processing duration, failure rate, and database health.

## 12. Hikari Pool Pressure Answer

Hikari pressure shows up when active connections are high and pending threads are above zero. In this project I would check:

- `hikaricp.connections.active`
- `hikaricp.connections.idle`
- `hikaricp.connections.pending`
- `hikaricp.connections.acquire`

Then I would ask why connections are held too long:

- Slow search query or missing index.
- Row locks in order execution.
- Transactions doing too much work.
- Too much JMS listener concurrency for the pool size.
- Database CPU/I/O saturation.

The fix is not automatically "increase the pool." I would first check query plans, lock waits, transaction boundaries, and consumer concurrency. The pool should be sized below the database connection limit and coordinated with web threads, JMS concurrency, and RDS/PostgreSQL capacity.

## 13. DB Lock Diagnosis Answer

The code intentionally uses database locking in a few places:

- REST idempotency uses the idempotency key primary key.
- Outbox relay uses `FOR UPDATE SKIP LOCKED`.
- Consumer processing locks `processed_messages` and the `orders` row.

That is correct for consistency, but lock waits can affect p99.

For diagnosis I would run `scripts/sql/db-lock-diagnostics.sql` during load. I would look for blocked sessions, blocker PIDs, long-running transactions, wait events, and the SQL text involved. Then I would map that back to the application path:

- Is an order row hot because duplicate or repeated events target the same order?
- Is the outbox relay holding locks too long?
- Are search queries scanning and holding connections?
- Is a transaction doing broker or slow external work while holding a DB lock?

In this code, the intended transaction scopes are small: persist order/outbox, relay due rows, or process one message. If lock waits appear, I would inspect transaction duration and query plans before changing locking strategy.

## 14. Java / OOP Concepts Demonstrated

The project demonstrates core Java/OOP without hiding the model behind framework code:

- Records and enums model immutable value objects and constrained state.
- `Order.transitionTo` centralizes lifecycle rules.
- `Quantity`, `Price`, `AccountId`, and `InstrumentSymbol` enforce validation close to the domain.
- `Trade.fromExecutionReport` expresses the relationship between fill reports and trades.
- Interfaces such as `ExecutionSimulator` and `OrderEventPublisher` create focused test seams.
- DTOs, domain records, JPA entities, and JMS payloads are separate models.
- Controllers do transport work; application services coordinate transactions; domain objects enforce business rules.

That separation is what I would emphasize: I did not make controllers responsible for order lifecycle or persistence entities responsible for every business concept.

## 15. Testing And CI/CD Discussion

The suite is layered:

- Domain unit tests for construction rules and state transitions.
- Application tests for execution simulation and outbox writer behavior.
- Controller tests for validation and exception mapping.
- PostgreSQL Testcontainers integration tests for schema, constraints, idempotency, reference data, search, cancel/replace, and persistence.
- Artemis/Testcontainers coverage for outbox-to-JMS-to-consumer flow.
- Consumer tests for duplicate message safety and failure diagnostics.
- FIX parser tests for the simplified educational parser.

CI runs through GitHub Actions with Java 21 and Maven wrapper commands, including `./mvnw -B clean verify`, and builds the Docker image. Heavy k6 load tests are documented for manual local use and intentionally excluded from normal CI.

## 16. AWS Deployment Answer

The straightforward AWS deployment is ECS Fargate behind an ALB, RDS PostgreSQL for the database, and Amazon MQ if the team wants JMS compatibility. Secrets go in Secrets Manager, encryption uses KMS, logs and metrics go to CloudWatch, and the service gets an IAM task role.

I would scale API tasks on request count, CPU, memory, and latency. I would scale consumers on queue depth, oldest message age, processing duration, and redelivery/failure rate, while watching RDS and broker saturation. If the organization already runs Kubernetes well, EKS is reasonable, but ECS is simpler for this project. If JMS compatibility were not required, SQS/SNS could replace Artemis with different semantics; Kafka/MSK would make sense for replayable event streams but is heavier than this queue-based workflow.

## 17. FIX And Trade Lifecycle Discussion

The `fix` package is intentionally not a FIX engine. It parses simplified `tag=value` messages separated by SOH or `|` and maps a New Order Single-like `35=D` message into `SubmitOrderRequest`.

Supported mappings include:

- `11` to `clientOrderId`.
- `49` to `accountId` for the demo.
- `55` to `symbol`.
- `54=1/2` to `BUY`/`SELL`.
- `40=1/2` to `MARKET`/`LIMIT`.
- `38` to quantity.
- `44` to limit price.

The honest production answer is that I would use QuickFIX/J for sessions, sequence numbers, heartbeats, resend requests, dictionaries, `BodyLength`, `CheckSum`, and certification behavior. The project only keeps enough FIX flavor to show how external protocol messages can map into the internal order API.

## 18. Known Limitations

Known limitations I would state clearly:

- Partial fills are modeled but not generated by the current simulator.
- Replace updates the current order row instead of preserving full order version history.
- Accepted replace reuses `OrderSubmittedEvent`; a production system would likely use an amendment event.
- Broker-level DLQ routing/reconciliation is documented, but there is no DLQ listener that marks rows `DEAD_LETTERED`.
- Search uses offset pagination, not keyset pagination.
- Reference-data management is simple and not secured.
- There is no authentication or authorization.
- The load test is local diagnostic tooling, not a capacity certification benchmark.
- No production AWS IaC exists yet.
- The FIX parser is educational only.

These are acceptable for an interview portfolio because they are named, documented, and connected to clear next steps.

## 19. Next Production-Grade Improvements

The next improvements I would prioritize:

1. Add authentication/authorization and separate business APIs from admin/reference-data APIs.
2. Add a DLQ listener or reconciliation job that links broker DLQ messages back to `processed_messages`.
3. Add explicit order versions and a dedicated `OrderReplacedEvent`.
4. Add real partial-fill simulation and richer execution outcomes.
5. Add keyset pagination for deep operational screens.
6. Add tick-size validation, account permissions, and instrument trading-session rules.
7. Add AWS IaC for ECS, RDS, Amazon MQ, Secrets Manager, CloudWatch alarms, and blue/green deployment.
8. Add formal performance baselines once representative data volume and infrastructure are available.

## 20. Five Likely Interview Questions And Strong Answers

### Why did you add an outbox instead of publishing directly to JMS?

Direct publish creates a reliability gap between the database commit and broker send. The outbox persists the intent to publish in the same transaction as the order and idempotency state. A relay owns broker publication, retry state, and failure visibility. It is still at-least-once, so consumers stay idempotent.

### How does the consumer handle duplicate messages?

The consumer claims `eventId` in `processed_messages`, skips messages already marked `PROCESSED`, and records duplicate/failure diagnostics. The business logic also derives deterministic execution-report IDs, locks the order row, and relies on a unique `trades.execution_report_id` constraint. That gives both operational visibility and data-level protection.

### Where are the important transaction boundaries?

Order submission stores order, idempotency response snapshot, and outbox row in one transaction. Outbox relay locks and updates due rows in its own transaction. Message processing claims the inbox row, writes report/trade/order updates, and marks the message processed in one transaction; failure diagnostics are recorded separately so retry information survives.

### How would you diagnose high p99 latency?

I would split API latency from async processing latency, then correlate k6 results, Micrometer timers, Hikari acquire/pending metrics, PostgreSQL lock diagnostics, outbox backlog, Artemis queue depth, processed-message failures, thread dumps, CPU, and GC logs. I would tune the JVM only if GC pauses line up with the p99 spikes.

### What makes this senior-level rather than just CRUD?

It has real backend failure-mode thinking: database-backed REST idempotency with response snapshots, transactional outbox, at-least-once JMS consumption, inbox diagnostics, explicit transaction boundaries, row locking, SQL constraints, operational search, metrics, load diagnostics, and documented production tradeoffs.
