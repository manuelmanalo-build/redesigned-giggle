# Project Walkthrough Script

## 1. 60-Second Overview

I would describe this project as a compact Java 21 Spring Boot backend for a simplified real-time trade processing platform.

A client submits an order over REST. The API validates the request, checks that the account and instrument are active, claims a database-backed idempotency key, persists the order, stores the original response snapshot, and writes an `OrderSubmittedEvent` to a transactional outbox. A scheduled relay publishes pending outbox events to an ActiveMQ Artemis JMS queue. The consumer records the event in a processed-message inbox, locks the order, simulates execution, writes an execution report, books a trade if there is a fill, and updates order state.

It also supports cancel and replace, paginated searches for orders/reports/trades, reference-data management, metrics, health checks, local load testing, GC logging helpers, and PostgreSQL lock diagnostics.

The reason this is useful for a senior Java interview is that it is not just CRUD. It shows transaction boundaries, idempotency, at-least-once messaging, SQL constraints and indexes, concurrency choices, operational diagnostics, and honest production tradeoffs.

## 2. Architecture Overview

The codebase is a modular monolith with package boundaries that are easy to explain.

The `api` package owns REST controllers and DTOs. `OrderController` handles submit, cancel, replace, and order-specific reads. `SearchController` handles paginated operational views. `ReferenceDataController` handles simple account and instrument management. `GlobalApiExceptionHandler` keeps error responses consistent.

The `application` package owns orchestration. `OrderApplicationService` coordinates validation, idempotency, order persistence, cancel/replace, and outbox writes. `SearchApplicationService` owns filter validation and query orchestration. `ReferenceDataValidationService` checks active accounts and instruments. `OrderExecutionProcessor` owns the async execution workflow. `OrderSubmittedMessageInboxProcessor` owns consumer-side duplicate/failure tracking.

The `domain` package is pure Java. It has `Order`, `Quantity`, `Price`, `ExecutionReport`, `Trade`, ID wrappers, and enums like `OrderStatus`, `OrderSide`, `OrderType`, and `ExecutionType`. This is where construction rules and state transitions live.

The `persistence` package maps the database. PostgreSQL is the source of truth and Flyway migrations create the schema, constraints, and indexes.

The `messaging` package contains `OrderSubmittedEvent`, the JMS publisher, outbox relay, scheduler, and consumer. The REST path does not call `JmsTemplate`; it writes to the outbox.

The `observability` package provides correlation IDs and Micrometer metrics. The same correlation ID flows from REST into the outbox event and JMS message so I can follow an order across the sync and async paths.

## 3. Order Submission Deep Dive

Order submission starts at `POST /api/v1/orders`.

The client sends an `Idempotency-Key` header and optionally `X-Correlation-Id`. If the correlation ID is missing, `CorrelationIdFilter` creates one.

The request shape is validated with Jakarta validation. Then the application service creates the domain order, so rules like positive quantity, nonblank account/symbol, limit orders requiring price, and market orders not requiring price are enforced outside the controller.

Next, `ReferenceDataValidationService` checks the current database state. The account must exist and be `ACTIVE`; the instrument must exist and be `ACTIVE`. Unknown accounts, suspended/closed accounts, unknown symbols, halted instruments, and delisted instruments are rejected with `400 Bad Request`. Those failures do not create an order, idempotency record, or outbox event.

For idempotency, the service normalizes the business request and hashes it. It claims the idempotency key by inserting into `idempotency_records` with `ON CONFLICT DO NOTHING`. If the insert fails and the hash matches, the service returns the stored response snapshot. That is important because the order may have changed asynchronously since the original response. If the hash differs, it returns `409 Conflict`.

If the claim succeeds, the service accepts the domain order, saves it to `orders`, stores the response body/status in `idempotency_records`, and writes a pending `outbox_events` row. Those database writes are in the same Spring transaction.

The REST request returns the accepted order. It does not publish directly to JMS. That is intentional.

## 4. Why The Transactional Outbox Exists

The outbox was added to close the classic database-versus-broker reliability gap.

If I save the order and then publish directly to JMS, two bad things can happen. The database commit can succeed while JMS publishing fails, leaving an accepted order that never gets processed. Or the JMS publish can succeed while the database transaction rolls back, leaving a consumer with an event for data that does not exist.

With the outbox, the accepted order, idempotency state, and event intent commit together. `OutboxRelayService` later polls `PENDING` rows, publishes the event to `order.submitted`, and marks the row `PUBLISHED`. If publication fails, the row records `attempt_count`, `last_error`, `next_attempt_at`, and eventually `FAILED` after configured max attempts.

The relay uses `FOR UPDATE SKIP LOCKED`, so multiple instances can poll without normally taking the same row. The tradeoff is still at-least-once delivery. If the relay publishes to the broker and crashes before updating the row, it may publish the same event again. That is why the consumer is idempotent.

## 5. JMS Async Processing Deep Dive

The async flow starts with `OrderSubmittedEvent` on `order.submitted`.

`OrderSubmittedEventConsumer` receives the JSON message, deserializes it, restores the correlation ID for logging, and delegates to `OrderSubmittedMessageInboxProcessor`.

The inbox processor claims the `eventId` in `processed_messages`. If the message was already processed, it skips the business operation and records the duplicate observation. If the message is new, it proceeds into `OrderExecutionProcessor`.

`OrderExecutionProcessor` derives a deterministic execution-report ID from the event ID. It checks whether that report already exists, loads the order with a pessimistic write lock, checks again after the lock, and then runs the execution simulator.

The simulator is intentionally simple. Market orders fill completely at the configured simulated market price. Limit buys fill when the limit price is at or above the market price. Limit sells fill when the limit price is at or below the market price. Non-marketable limits get a no-fill execution report and remain `ACCEPTED`.

If the order fills, the processor writes an execution report, creates a trade from that report, and marks the order `FILLED`. Those writes happen in one transaction with the inbox row moving to `PROCESSED`.

If processing fails, the message-processing transaction rolls back and a separate diagnostic path updates `processed_messages` with `FAILED`, attempt count, and last error. The listener is transacted, so retry/redelivery remains the broker's job.

## 6. Inbox / Processed-Message Explanation

The processed-message inbox exists because the broker only gives at-least-once delivery.

The table is `processed_messages`. It stores `message_id`, event type, aggregate ID, consumer name, status, timestamps, attempt count, last error, and correlation ID.

I would explain it this way: the outbox protects the producer side from losing the intent to publish; the inbox protects the consumer side from repeating side effects and gives operators diagnostics when retries or DLQ behavior happen.

The inbox is not the only safety net. The business layer still uses deterministic execution-report IDs, row locking, and a unique trade-to-execution-report constraint. That way the system remains safe even if a duplicate gets past the diagnostic layer.

## 7. REST And JMS Idempotency

There are two separate idempotency strategies.

For REST, clients supply `Idempotency-Key`. The service hashes a normalized request and stores the original response snapshot in `idempotency_records`. Same key plus same request returns the original response. Same key plus different request returns `409 Conflict`. This is used for submit, cancel, and replace.

For JMS, the event has an `eventId`. The consumer stores that in `processed_messages`, skips already processed events, derives deterministic execution-report IDs, locks the order row, and relies on database uniqueness to prevent duplicate trades.

The key point is that idempotency is durable and database-backed. There is no in-memory dedupe map that would fail when the app scales horizontally.

## 8. Trade Lifecycle Script

The lifecycle I can demonstrate is:

Submit: the client posts an order.

Accept/reject: valid active account/instrument orders are accepted; invalid reference data is rejected before persistence.

Execute: accepted orders flow through the outbox, relay, JMS queue, and consumer.

Fill/no-fill: market orders and marketable limits fill; non-marketable limits stay accepted with a no-fill report.

Partially fill/fill: the domain and persistence model support `PARTIALLY_FILLED` and `FILLED`; the current simulator does full fill or no-fill, while partial-fill state is used in lifecycle guards.

Cancel: open accepted or partially filled orders can be cancelled. The order becomes `CANCELLED`, a cancel execution report is written, and existing trades remain.

Replace: open limit orders can be amended in place. The service validates the new quantity, ensures it does not go below already filled quantity, writes a `REPLACED` execution report, and for accepted orders writes another outbox event so the amended order can be re-evaluated.

Book trade: fills create a trade tied to the fill execution report.

The production caveat is that replace should eventually have explicit order versions and a dedicated amendment event.

## 9. SQL Schema And Indexes

The schema is managed by Flyway and PostgreSQL enforces important invariants.

The core tables are `orders`, `execution_reports`, `trades`, and `idempotency_records`. Reliability tables are `outbox_events` and `processed_messages`. Reference-data tables are `accounts` and `instruments`.

Important constraints include positive quantity/price, valid enum values, market-versus-limit price rules, filled quantity not exceeding quantity, fill execution reports requiring quantity and price, and one trade per execution report.

Indexes are chosen for the actual access paths:

`orders(account_id, created_at DESC)`, `orders(symbol, created_at DESC)`, `orders(status, created_at DESC)`, and `orders(account_id, status, created_at DESC)` support operational order views.

`execution_reports(order_id, created_at DESC)` and `trades(order_id, created_at DESC)` support order lifecycle reads.

`trades(account_id, created_at DESC)` and `trades(symbol, created_at DESC)` support operational trade views.

`outbox_events(status, next_attempt_at, created_at)` supports relay polling.

`processed_messages(status)` and `(consumer_name, status)` support retry and DLQ diagnostics.

The default search sort is `createdAt DESC, id DESC`. The ID tie-breaker makes pages stable when timestamps tie.

## 10. Search API Explanation

The search endpoints are:

- `GET /api/v1/orders`
- `GET /api/v1/execution-reports`
- `GET /api/v1/trades`

They support page/size pagination with default size `20` and max size `100`. Filters are typed where possible: order status, side, type, execution type, date ranges, account ID, symbol, client order ID, and order ID.

Sort fields are whitelisted in `SearchApplicationService`. That avoids exposing arbitrary SQL ordering from request parameters.

The tradeoff is offset pagination. It is simple and works for demo/admin views. For high-volume production screens, I would add keyset pagination using `(created_at, id)`.

## 11. Performance And Load Testing

I added local performance diagnostics rather than a fake enterprise benchmark.

The k6 script is `scripts/load/order-load-test.js`. It submits a mix of market orders, fillable limit orders, non-fillable limit orders, and invalid reference-data requests. It also queries order, execution-report, trade, and search endpoints during load.

The docs explain how to watch:

- API p50/p95/p99 and throughput from k6.
- `http.server.requests` metrics.
- `trade.messages.processing.duration`.
- Hikari active, idle, pending, and acquire metrics.
- Outbox and processed-message status counts.
- Artemis queue depth and redeliveries.
- PostgreSQL lock waits using `scripts/sql/db-lock-diagnostics.sql`.
- GC behavior using `scripts/run-local-with-gc-logs.sh`.

I would frame this as diagnostic readiness. The project gives me enough signals to decide whether a bottleneck is in the REST path, outbox relay, broker, consumer, database, connection pool, or JVM.

## 12. JVM / GC And P99 Answer

If an interviewer asks about high p99 latency, I would say:

First I would locate the path. Is it order submission, search, outbox relay, or JMS processing? Then I would correlate k6 p99, Micrometer timers, Hikari acquire/pending metrics, PostgreSQL locks, outbox backlog, broker queue depth, processed-message failures, thread dumps, CPU, and GC logs.

For the JVM specifically, I would check whether GC pause timestamps line up with latency spikes. This app allocates through Jackson DTOs and event payloads, JPA entities, domain records, `BigDecimal`, and logging MDC. If GC aligns with p99, I would inspect allocation rate, heap size, object churn, and pause times. If GC is quiet but Hikari pending or PostgreSQL locks are high, I would fix database pressure before touching JVM flags.

The senior answer is: do not tune the JVM blindly. Prove whether the latency is GC, database, broker, queueing, connection-pool, or thread contention.

## 13. Queue Depth And Backpressure Answer

If queue depth rises, I would separate the backlogs.

If `outbox_events` are stuck in `PENDING`, the relay is slow or broker publishing is failing.

If Artemis `order.submitted` depth is growing, publication is working but consumers are not keeping up.

If `processed_messages` has `FAILED` rows, consumers are receiving messages but business processing is failing.

Then I would check processing duration, Hikari pending threads, PostgreSQL lock waits, broker redeliveries, CPU, and whether a hot order/account is causing contention.

I would not automatically add consumers. More consumers help only if consumers are CPU-bound and the database/broker can handle more concurrency. If the bottleneck is DB locks or connection pool exhaustion, more consumers make p99 worse.

## 14. Hikari Pool Pressure Answer

Hikari pool pressure means requests or consumers are waiting for database connections.

I would check `hikaricp.connections.active`, `idle`, `pending`, and `acquire`. If pending is above zero, I would ask why connections are held too long.

Possible causes in this app are slow search queries, missing indexes, lock waits during order processing, too much JMS concurrency for the pool size, long transactions, or database saturation.

The fix is not always increasing the pool. I would first inspect query plans, lock diagnostics, transaction scope, and consumer concurrency. The Hikari pool has to be sized together with web threads, JMS listener concurrency, and the PostgreSQL/RDS connection limit.

## 15. DB Lock Diagnosis Answer

This project intentionally uses locks where correctness matters: idempotency primary-key claims, outbox `FOR UPDATE SKIP LOCKED`, processed-message claims, and pessimistic order row locking during execution.

If p99 spikes and I suspect locks, I would run `scripts/sql/db-lock-diagnostics.sql` while load is running. I would look for blocked sessions, blocker PIDs, wait events, transaction age, and SQL text.

Then I would map that back to the code path. Is the same order being processed repeatedly? Is the relay holding locks too long? Is a search query forcing scans? Is JMS concurrency higher than the database can support?

The design keeps transactions small, so persistent lock waits would tell me where to refine query patterns, indexes, or concurrency settings.

## 16. Known Limitations

The limitations I would say out loud are:

- The simulator does not yet generate partial fills, even though the domain supports the state.
- Replace updates the current row instead of preserving full order version history.
- Accepted replace reuses `OrderSubmittedEvent`; production should likely have `OrderReplacedEvent`.
- There is no broker-level DLQ listener that marks inbox rows `DEAD_LETTERED`.
- Search uses offset pagination.
- Reference-data APIs are simple and not secured.
- There is no authentication or authorization.
- The load test is local and diagnostic, not a production capacity certification.
- There is no AWS IaC yet.
- The FIX parser is educational and not a FIX engine.

## 17. Strong Closing Answer: What I Would Improve Next

The next production-grade improvements would be security, stronger lifecycle modeling, and operational hardening.

I would add authentication and authorization, separate admin/reference-data permissions from trading operations, and add a DLQ listener or reconciliation job that links broker DLQ messages back to `processed_messages`.

For the order lifecycle, I would add explicit order versions, a dedicated amendment event for replace, realistic partial-fill simulation, tick-size checks, account trading permissions, and instrument trading-session rules.

For scale, I would add keyset pagination, production-like performance baselines, CloudWatch dashboards and alarms, and AWS IaC for ECS, RDS, Amazon MQ, Secrets Manager, KMS, and blue/green deployment.

My closing line would be: this project is interview-ready because it demonstrates the hard backend conversations in a small codebase: correctness first, explicit transaction boundaries, idempotency, at-least-once messaging, SQL design, operational diagnostics, and clear production tradeoffs.
