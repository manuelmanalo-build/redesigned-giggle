# Product Spec

## Purpose

`realtime-trade-processing-simulator` models a simplified real-time order processing platform. A client submits an order through REST, the API validates and stores it with a transactional outbox event, a relay publishes the order-submitted event to JMS, an asynchronous consumer tracks the message in an inbox, simulates execution, and the system records execution reports, trades, and final order state.

The product is for interview preparation. It should be realistic enough to discuss backend engineering tradeoffs while remaining small enough to implement, test, and explain clearly.

## MVP Scope

The MVP includes one Spring Boot service with:

- REST APIs for order submission and read models.
- REST APIs for account and instrument reference-data management.
- PostgreSQL persistence for accounts, instruments, orders, execution reports, trades, idempotency records, transactional outbox events, and processed-message inbox diagnostics.
- JMS publishing and consuming with ActiveMQ Artemis.
- Transactional outbox relay for reliable order-submitted event publication and database-visible retry state.
- Idempotent order submission using an `Idempotency-Key` header.
- Idempotent cancel and replace workflows for open orders.
- Idempotent message consumption using a processed-message inbox, event IDs, and deterministic execution-report/trade IDs.
- A deterministic execution simulator that creates execution reports.
- Trade creation when the current simulator fills an order. `PARTIALLY_FILLED` is modeled in the domain and supported by cancel/replace guards, but the current simulator only produces full fills or no-fill accepted reports.
- Order status updates based on execution outcome.
- Structured logs with correlation IDs.
- Test-first implementation with unit, slice, integration, and end-to-end tests.
- Maven build and GitHub Actions CI.

## Out of Scope for MVP

- Real exchange connectivity.
- Full FIX session management or production FIX engine behavior. The repository may include lightweight FIX-style parsing for education only.
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
5. API writes a pending `ORDER_SUBMITTED` outbox event in the same database transaction as the order and idempotency record.
6. API returns `201 Created` with order identity and current state.
7. Outbox relay polls due pending events, publishes to the JMS queue, and marks successful rows as `PUBLISHED`.
8. Async consumer receives the event.
9. Consumer claims or skips the message using `processed_messages`.
10. Consumer simulates execution.
11. System creates an execution report.
12. System creates a trade record for filled executions.
13. System updates order status and marks the message `PROCESSED`.
14. Processing failures are recorded as `FAILED` diagnostics and rethrown for broker retry.
15. Client retrieves order, execution report, and trade state via REST.

## Domain Concepts

The MVP domain includes:

- `Account`: persisted reference data used to validate that orders come from active accounts.
- `Instrument`: persisted reference data used to validate that orders are for active symbols.
- `Order`: client instruction to buy or sell an instrument.
- `OrderSide`: `BUY` or `SELL`.
- `OrderType`: `MARKET` or `LIMIT`.
- `OrderStatus`: lifecycle status of an order.
- `ExecutionReport`: result of simulated processing.
- `ExecutionType`: execution event type.
- `Trade`: fill record derived from an execution.
- `IdempotencyRecord`: record used to deduplicate REST submissions.
- `OutboxEvent`: durable integration event record used by the relay to publish accepted orders to JMS.
- `ProcessedMessage`: consumer inbox record used to detect duplicates and diagnose retries/DLQ investigations.

## Functional Requirements

- The system must accept valid order submissions.
- The system must reject malformed or invalid orders with production-style error responses.
- The system must persist accepted orders before publishing the JMS event.
- The system must publish exactly one logical order-submitted event per idempotent order submission.
- The system must safely return the same order resource and response status for repeated requests with the same `Idempotency-Key` and equivalent payload.
- The system must reject reuse of the same `Idempotency-Key` with a materially different payload.
- The consumer must tolerate duplicate JMS delivery without creating duplicate execution reports or trades.
- The execution simulator must be deterministic enough for tests.
- Filled orders must produce trade records. Partial-fill trade creation is modeled but not produced by the current simulator.
- Clients must be able to query orders, execution reports, and trades.
- Logs must carry a correlation ID across REST entry, persistence, message publication, and message consumption where practical.

## Nonfunctional Requirements

- REST API correctness: clear resources, status codes, and validation failures. Pagination is planned for future list endpoints.
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
