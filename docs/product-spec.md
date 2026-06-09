# Product Spec

## Purpose

`realtime-trade-processing-simulator` models a simplified real-time order processing platform. A client submits an order through REST, the API validates and stores it, publishes an `order-submitted` event to JMS, an asynchronous consumer simulates execution, and the system records execution reports, trades, and final order state.

The product is for interview preparation. It should be realistic enough to discuss backend engineering tradeoffs while remaining small enough to implement, test, and explain clearly.

## MVP Scope

The MVP includes one Spring Boot service with:

- REST APIs for order submission and read models.
- PostgreSQL persistence for accounts, instruments, orders, execution reports, trades, and idempotency records.
- JMS publishing and consuming with ActiveMQ Artemis.
- Idempotent order submission using an `Idempotency-Key` header.
- Idempotent message consumption using event IDs and deterministic execution-report/trade IDs.
- A deterministic execution simulator that creates execution reports.
- Trade creation when an order is `FILLED` or `PARTIALLY_FILLED`.
- Order status updates based on execution outcome.
- Structured logs with correlation IDs.
- Test-first implementation with unit, slice, integration, and end-to-end tests.
- Maven build and GitHub Actions CI.

## Out of Scope for MVP

- Real exchange connectivity.
- Full FIX session management or FIX parsing.
- Authentication and authorization.
- Real market data.
- Real matching engine or order book.
- Complex order routing.
- Multi-currency accounting.
- Settlement, clearing, and regulatory reporting.
- Kubernetes or production cloud deployment.

## Core Business Flow

1. Client submits an order via `POST /api/v1/orders`.
2. API validates request shape and domain rules.
3. API creates or reuses an idempotency record for the request.
4. API stores the order with status `ACCEPTED`.
5. API publishes an `ORDER_SUBMITTED` event to a JMS queue.
6. API returns `201 Created` with order identity and current state.
7. Async consumer receives the event.
8. Consumer verifies the message has not already been processed.
9. Consumer simulates execution.
10. System creates an execution report.
11. System creates a trade record for `FILLED` or `PARTIALLY_FILLED` executions.
12. System updates order status.
13. Client retrieves order, execution report, and trade state via REST.

## Domain Concepts

The MVP domain includes:

- `Account`: owner of orders and trades.
- `Instrument`: tradable symbol and reference data.
- `Order`: client instruction to buy or sell an instrument.
- `OrderSide`: `BUY` or `SELL`.
- `OrderType`: `MARKET` or `LIMIT`.
- `OrderStatus`: lifecycle status of an order.
- `ExecutionReport`: result of simulated processing.
- `ExecutionType`: execution event type.
- `Trade`: fill record derived from an execution.
- `IdempotencyRecord`: record used to deduplicate REST submissions. A dedicated message inbox table is a planned extension.

## Functional Requirements

- The system must accept valid order submissions.
- The system must reject malformed or invalid orders with production-style error responses.
- The system must persist accepted orders before publishing the JMS event.
- The system must publish exactly one logical order-submitted event per idempotent order submission.
- The system must safely return the original response for repeated requests with the same `Idempotency-Key` and equivalent payload.
- The system must reject reuse of the same `Idempotency-Key` with a materially different payload.
- The consumer must tolerate duplicate JMS delivery without creating duplicate execution reports or trades.
- The execution simulator must be deterministic enough for tests.
- Filled and partially filled orders must produce trade records.
- Clients must be able to query orders, execution reports, and trades.
- Logs must carry a correlation ID across REST entry, persistence, message publication, and message consumption where practical.

## Nonfunctional Requirements

- REST API correctness: clear resources, status codes, validation failures, and pagination.
- SQL persistence: normalized tables, constraints, indexes, and transaction-aware writes.
- JMS async messaging: durable queue, explicit destination names, retry-ready listener configuration, and DLQ-ready design.
- Idempotent order submission: duplicate client retries must not create duplicate orders.
- Idempotent message consumption: redelivery must not create duplicate side effects.
- Clear transaction boundaries: document where database transactions begin and end.
- Concurrency-safe processing: parallel consumers must not corrupt order state.
- Test-first development: tests should be written before behavior where practical.
- Observable logging: structured logs should include `correlationId`, `orderId`, `messageId`, and relevant state changes.
- Production-style error handling: no stack traces in client responses, consistent error body, and clear server-side logs.

## MVP Success Criteria

The MVP is complete when a developer can:

- Submit an order with an idempotency key.
- Observe the order move from accepted to filled, or remain accepted when a limit order does not cross the simulated market price.
- Retrieve the order by ID.
- Retrieve execution reports for the order.
- Retrieve trades derived from the order.
- Explain how REST, SQL, JMS, transactions, idempotency, concurrency, and tests work together.
