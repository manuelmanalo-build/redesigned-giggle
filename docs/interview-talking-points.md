# Interview Talking Points

## Project Pitch

This project is a Java 21 Spring Boot backend that simulates real-time order processing. A client submits an order through REST, the service validates and stores it, publishes an order-submitted event to JMS, an asynchronous consumer simulates execution, and the system creates execution reports, trades, and queryable order state.

The project is intentionally small, but it contains the backend topics interviewers usually probe: OOP, data modeling, concurrency, messaging, SQL, testing, idempotency, and operational thinking.

## Java

- Java 21 records can model immutable request, response, command, and event payloads.
- Enums model `OrderSide`, `OrderType`, `OrderStatus`, and `ExecutionType`.
- Collections support query results, state history, and deduplication logic.
- Exceptions and result objects communicate validation and state-transition failures.
- `BigDecimal` should be used for prices and average execution price, not floating-point types.

## OOP and Domain Modeling

- `Order`, `ExecutionReport`, `Trade`, `Account`, and `Instrument` are explicit domain concepts.
- Business rules are not hidden in controllers.
- Status transitions are testable and constrained.
- Trade creation is derived from execution report behavior.
- API DTOs, JMS payloads, domain objects, and JPA entities remain separate where useful.

## Data Structures

- Database indexes support lookup by order ID, account, symbol, status, and creation time.
- Primary keys enforce request idempotency and deterministic report/trade IDs make duplicate message side effects detectable.
- Queues model asynchronous order processing.
- Ordered execution reports provide lifecycle history.
- Pagination protects list APIs from unbounded result sets.

## Multithreading and Concurrency

- REST handlers process many client submissions concurrently.
- JMS listener concurrency allows multiple orders to process in parallel.
- Idempotency keys protect client retries.
- Current duplicate delivery protection uses deterministic execution-report/trade IDs derived from the event ID.
- The consumer locks the order row while it decides whether to create an execution report, create a trade, and update order state.
- A production extension would add a dedicated processed-message inbox table for richer retry/DLQ diagnostics.
- Domain services should avoid mutable shared state.

## JVM and GC Awareness

- High message throughput can create allocation pressure from DTOs, JSON serialization, and database mapping.
- Connection pool and listener concurrency settings affect throughput and latency.
- `BigDecimal` correctness is worth the allocation cost for money-like values.
- JVM profiling should focus on real bottlenecks before optimization.
- GC discussions can cover object churn, batching, backpressure, and avoiding unnecessary temporary objects.
- For local demos, GC logs can be enabled with G1GC and bounded heap settings to inspect allocation pressure and pause behavior.

Concise high-latency investigation answer:

Start by identifying the specific endpoint or consumer path with high p99, then correlate the spike with application logs, Micrometer timers, GC pause metrics, CPU, thread count, Hikari pool wait time, PostgreSQL slow queries/locks, and Artemis queue depth/redeliveries. If GC pauses or allocation rate line up with the spike, inspect heap pressure, object churn, and thread dumps. If app threads are waiting on JDBC or the broker while GC is quiet, treat it as a database, broker, or queueing problem. Change one variable at a time and validate with a repeatable load shape.

## REST

- `POST /api/v1/orders` currently returns `201 Created` after persisting the accepted order and registering JMS publication; this can move back to `202 Accepted` when asynchronous execution processing owns more of the lifecycle.
- `Idempotency-Key` makes client retries safe by replaying the original logical response for the same normalized request.
- Reusing an idempotency key with a different normalized request returns `409 Conflict`.
- Jakarta Bean Validation handles request-shape checks, while domain objects enforce business rules such as market/limit price requirements.
- `X-Correlation-Id` is accepted from clients, included in error responses, and carried into `OrderSubmittedEvent`.
- `GET` endpoints expose current order state, execution reports, and trades.
- Error responses are consistent and include timestamp, HTTP status, error code, message, path, and correlation ID.

## SQL and Persistence

- PostgreSQL is the source of truth.
- The current MVP schema includes `orders`, `execution_reports`, `trades`, and `idempotency_records`.
- Account and instrument are currently persisted as explicit order/trade fields (`account_id`, `symbol`) to keep the first persistence step focused; separate reference-data tables remain a planned extension.
- Foreign keys protect execution report, trade, and idempotency references to orders.
- The idempotency key is the primary key for `idempotency_records`, which lets PostgreSQL enforce duplicate-submission protection.
- Order submission stores the order and idempotency record in one Spring-managed transaction, so client retry state and durable order state commit together.
- Order-submitted publication runs after transaction commit, so rolled-back orders are not emitted to JMS.
- The request hash is based on normalized business fields, so superficial JSON formatting differences do not create false idempotency conflicts.
- Numeric price columns use `NUMERIC(19, 4)` to avoid floating-point money errors.
- Quantity columns use integer types with positive check constraints because order quantities are discrete in the MVP.
- `orders.client_order_id` supports client/FIX-style lookup by `ClOrdID`.
- `orders.account_id`, `orders.symbol`, and `orders.status` indexes support common query filters and operational screens.
- `execution_reports.order_id` and `trades.order_id` indexes support order-lifecycle history reads.
- The separate `idempotency_records.idempotency_key` index is redundant with the primary key in PostgreSQL, but it is intentionally listed in the migration to satisfy the explicit MVP indexing requirement and make the access path obvious during review.
- Transactions define when order submission and message consumption become durable.
- Testcontainers proves behavior against real PostgreSQL.

## JMS

- ActiveMQ Artemis decouples API submission from execution processing.
- `order.submitted` is the initial durable queue.
- `OrderEventPublisher` is an application-facing abstraction, so the order service is not coupled directly to `JmsTemplate`.
- `JmsOrderEventPublisher` serializes `OrderSubmittedEvent` explicitly as JSON and sets JMS metadata such as event type, event ID, order ID, and correlation ID.
- Message payloads include event identity, order ID, client order ID, account ID, symbol, side, type, quantity, limit price, correlation ID, and creation time.
- `OrderSubmittedEventConsumer` deserializes JSON explicitly, puts the correlation ID into logging context, and delegates business work to `OrderExecutionProcessor`.
- `ExecutionSimulator` is an abstraction, which keeps market/limit execution rules testable and replaceable.
- MARKET orders fill at the configured simulated market price; LIMIT orders fill only when the buy/sell limit crosses that price.
- The processor writes execution reports, trades, and order updates in one transaction.
- The MVP uses direct after-commit JMS publication instead of a transactional outbox. This is simpler and explainable, but a crash or broker outage after database commit can lose an event.
- A production-grade extension would add an outbox table and relay to retry publication independently.
- Consumers are idempotent because JMS can redeliver messages.
- Retry and DLQ behavior are part of the design, even if broker defaults are used first.

## CI/CD

- Maven provides repeatable local and CI builds.
- GitHub Actions should run `./mvnw -B clean verify`.
- CI should include unit tests, slice tests, PostgreSQL integration tests, and JMS integration tests.
- A clean pipeline demonstrates that the project is not just a local demo.

## TDD

- Start with unit tests for domain validation and state transitions.
- Add service tests for idempotent submission and orchestration.
- Add API tests for request validation and error contracts.
- Add integration tests for database constraints and JMS behavior.
- Use failing tests to guide each implementation step.

## AWS and Cloud Discussion

The MVP runs locally, but it can be mapped to AWS concepts:

- Spring Boot service on ECS, EKS, or Elastic Beanstalk.
- PostgreSQL on Amazon RDS.
- JMS-like asynchronous processing with Amazon MQ for ActiveMQ or SQS with design adjustments.
- CloudWatch logs and metrics for observability.
- Secrets Manager or Parameter Store for configuration.
- Horizontal scaling of API tasks and consumer tasks.

## FIX and Trade Lifecycle

The project includes a deliberately lightweight FIX-style parser for educational use. It accepts simplified `tag=value` messages separated by SOH or `|`, supports a small New Order Single-like field set, and maps `35=D` messages into the same internal order request model used by the REST API.

This is not a full FIX engine. It does not implement logon/logout, heartbeats, sequence recovery, resend requests, gap fills, BodyLength/CheckSum validation, counterparty sessions, or FIX dictionary certification behavior. A production integration would use a proven engine such as QuickFIX/J and keep business mapping separate from session management.

- `ClOrdID` maps to `clientOrderId`.
- In this simplified demo, `SenderCompID` maps to `accountId`.
- `Symbol` maps to `symbol`.
- `Side=1` maps to `OrderSide.BUY`; `Side=2` maps to `OrderSide.SELL`.
- `OrdType=1` maps to `OrderType.MARKET`; `OrdType=2` maps to `OrderType.LIMIT`.
- `OrderQty` maps to `quantity`.
- `Price` maps to `limitPrice` for limit orders and is ignored for market orders because the domain model rejects prices on market orders.
- `OrdStatus` maps to `OrderStatus`.
- `ExecType` maps to `ExecutionType`.
- `ExecID` maps to `executionReportId`.

The parser and mapper are isolated from controllers, persistence, and JMS. That keeps the protocol translation testable and makes the boundary between transport/protocol concerns and domain/application behavior easy to explain.

The order lifecycle demonstrates accepted, rejected, partially filled, and filled states without requiring real market connectivity.
