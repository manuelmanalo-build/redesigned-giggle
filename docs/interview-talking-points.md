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
- Unique constraints enforce idempotency and message deduplication.
- Queues model asynchronous order processing.
- Ordered execution reports provide lifecycle history.
- Pagination protects list APIs from unbounded result sets.

## Multithreading and Concurrency

- REST handlers process many client submissions concurrently.
- JMS listener concurrency allows multiple orders to process in parallel.
- Idempotency keys protect client retries.
- Processed-message records protect against duplicate JMS delivery.
- Optimistic locking or guarded updates protect order state.
- Domain services should avoid mutable shared state.

## JVM and GC Awareness

- High message throughput can create allocation pressure from DTOs, JSON serialization, and database mapping.
- Connection pool and listener concurrency settings affect throughput and latency.
- `BigDecimal` correctness is worth the allocation cost for money-like values.
- JVM profiling should focus on real bottlenecks before optimization.
- GC discussions can cover object churn, batching, backpressure, and avoiding unnecessary temporary objects.

## REST

- `POST /api/v1/orders` currently returns `201 Created` because the implemented MVP persists and accepts the order synchronously; this can move back to `202 Accepted` when JMS publication and asynchronous processing are introduced.
- `Idempotency-Key` makes client retries safe by replaying the original logical response for the same normalized request.
- Reusing an idempotency key with a different normalized request returns `409 Conflict`.
- Jakarta Bean Validation handles request-shape checks, while domain objects enforce business rules such as market/limit price requirements.
- `X-Correlation-Id` is accepted from clients and included in error responses; later observability work should propagate it through logs and JMS messages.
- `GET` endpoints expose current order state, execution reports, and trades.
- Error responses are consistent and include timestamp, HTTP status, error code, message, path, and correlation ID.

## SQL and Persistence

- PostgreSQL is the source of truth.
- The current MVP schema includes `orders`, `execution_reports`, `trades`, and `idempotency_records`.
- Account and instrument are currently persisted as explicit order/trade fields (`account_id`, `symbol`) to keep the first persistence step focused; separate reference-data tables remain a planned extension.
- Foreign keys protect execution report, trade, and idempotency references to orders.
- The idempotency key is the primary key for `idempotency_records`, which lets PostgreSQL enforce duplicate-submission protection.
- Order submission stores the order and idempotency record in one Spring-managed transaction, so client retry state and durable order state commit together.
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
- `orders.submitted.v1` is the initial durable queue.
- Message payloads include event identity, schema version, order ID, and correlation ID.
- Consumers are idempotent because JMS can redeliver messages.
- Retry and DLQ behavior are part of the design, even if broker defaults are used first.

## CI/CD

- Maven provides repeatable local and CI builds.
- GitHub Actions should run `mvn clean verify`.
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

The project does not implement FIX, but it maps naturally to FIX vocabulary:

- `ClOrdID` maps to `clientOrderId`.
- `Account` maps to `accountId`.
- `Symbol` maps to `symbol`.
- `Side` maps to `OrderSide`.
- `OrdType` maps to `OrderType`.
- `OrderQty` maps to `quantity`.
- `Price` maps to `limitPrice`.
- `OrdStatus` maps to `OrderStatus`.
- `ExecType` maps to `ExecutionType`.
- `ExecID` maps to `executionReportId`.

The order lifecycle demonstrates accepted, rejected, partially filled, and filled states without requiring real market connectivity.
