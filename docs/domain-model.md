# Domain Model

## Purpose

This document defines the intended domain model for the MVP. The model is simplified, but it should be precise enough to drive implementation and tests.

## Entities

### Account

Represents a trading account.

Fields:

- `accountId`: stable external identifier.
- `displayName`: human-readable account name.
- `status`: `ACTIVE` or `SUSPENDED`.
- `createdAt`
- `updatedAt`

Rules:

- Only active accounts can submit orders.
- Account IDs are unique.

### Instrument

Represents a tradable instrument.

Fields:

- `instrumentId`: internal identifier.
- `symbol`: unique symbol, such as `AAPL`.
- `description`
- `currency`
- `status`: `ACTIVE` or `INACTIVE`.
- `createdAt`
- `updatedAt`

Rules:

- Only active instruments can be ordered.
- Symbol is unique.

### Order

Represents a client instruction to buy or sell an instrument.

Fields:

- `orderId`: system identifier.
- `accountId`
- `symbol`
- `side`
- `orderType`
- `quantity`
- `limitPrice`
- `filledQuantity`
- `remainingQuantity`
- `averageExecutionPrice`
- `status`
- `version`: optimistic locking value.
- `createdAt`
- `updatedAt`

Rules:

- Quantity must be greater than zero.
- `LIMIT` orders require a positive limit price.
- `MARKET` orders do not require a limit price.
- Filled quantity cannot exceed order quantity.
- Remaining quantity equals quantity minus filled quantity.

### ExecutionReport

Represents the result of processing an order event.

Fields:

- `executionReportId`
- `orderId`
- `executionType`
- `orderStatus`
- `lastQuantity`
- `lastPrice`
- `cumulativeQuantity`
- `leavesQuantity`
- `averagePrice`
- `message`
- `correlationId`
- `createdAt`

Rules:

- `lastQuantity` is positive for fill and partial-fill reports.
- `lastPrice` is positive when `lastQuantity` is positive.
- Rejected reports must include a message.
- Accepted, rejected, and cancelled reports do not carry fill quantity or price.

### Trade

Represents an executed fill derived from an execution report.

Fields:

- `tradeId`
- `orderId`
- `executionReportId`
- `accountId`
- `symbol`
- `side`
- `quantity`
- `price`
- `tradeDate`
- `createdAt`

Rules:

- A trade is created only for `FILL` and `PARTIAL_FILL` execution types.
- Trade quantity and price must be positive.
- One execution report should create at most one trade in the MVP.

### IdempotencyRecord

Represents deduplication state for REST submission or message consumption.

Fields:

- `idempotencyKey`
- `scope`: `ORDER_SUBMISSION` or `MESSAGE_CONSUMPTION`.
- `requestFingerprint`
- `resourceType`
- `resourceId`
- `responseStatus`
- `status`: `STARTED`, `COMPLETED`, or `FAILED`.
- `createdAt`
- `updatedAt`

Rules:

- `(scope, idempotencyKey)` is unique.
- Reuse with a different request fingerprint is a conflict.
- Completed records can return the original logical result.

## Enums

### OrderSide

- `BUY`
- `SELL`

### OrderType

- `MARKET`
- `LIMIT`

### OrderStatus

- `NEW`: domain object has been created but not yet accepted or rejected by the application workflow.
- `ACCEPTED`: stored and queued for processing.
- `REJECTED`: failed validation or simulated execution.
- `PARTIALLY_FILLED`: some quantity filled and some remaining.
- `FILLED`: full quantity filled.
- `CANCELLED`: reserved for future use.

The MVP does not implement cancel requests, but `CANCELLED` may be included to discuss lifecycle extension.

### ExecutionType

- `ACCEPTED`: order accepted by the system.
- `REJECTED`: order rejected by processing.
- `PARTIAL_FILL`: order partially executed.
- `FILL`: order fully executed.
- `CANCELLED`: order cancelled.

## State Transitions

Allowed MVP order transitions:

- `NEW` to `ACCEPTED`.
- `NEW` to `REJECTED`.
- `ACCEPTED` to `PARTIALLY_FILLED`.
- `ACCEPTED` to `FILLED`.
- `PARTIALLY_FILLED` to `FILLED`.
- `ACCEPTED` to `CANCELLED`.
- `PARTIALLY_FILLED` to `CANCELLED`.

Disallowed transitions:

- `FILLED` to `ACCEPTED`.
- `REJECTED` to `FILLED`.
- `REJECTED` to `PARTIALLY_FILLED`.
- `CANCELLED` to any later processing state.
- Any transition that makes filled quantity exceed ordered quantity.

## Execution Simulation Rules

The MVP simulator should be deterministic for tests. Recommended initial rules:

- Reject orders for inactive accounts or instruments if they somehow reach processing.
- For `MARKET` orders, fill the full quantity at a deterministic simulated price.
- For `LIMIT BUY`, fill when simulated market price is less than or equal to limit price.
- For `LIMIT SELL`, fill when simulated market price is greater than or equal to limit price.
- If the limit condition is close but not fully met, optionally partial fill using a deterministic percentage.
- If no fill is possible, reject or leave unfilled based on the chosen MVP rule. The recommended MVP behavior is `REJECTED` to keep the lifecycle simple.

## Invariants

- Accepted orders must reference an active account and instrument.
- Every execution report references exactly one order.
- Every trade references exactly one order and one execution report.
- A fill or partial fill execution report must create a trade.
- Duplicate message delivery must not create duplicate execution reports or trades.
- Current order status must agree with cumulative execution state.

## Current Persistence Schema

The first persistence migration implements the core trade-processing tables only. Account and instrument remain domain concepts represented by `account_id` and `symbol` fields until reference-data persistence is added.

### `orders`

Columns:

- `id`: primary key, maps to `OrderId`.
- `client_order_id`: optional client-supplied identifier for future API idempotency and FIX `ClOrdID` discussion.
- `account_id`: non-null account identifier.
- `symbol`: non-null instrument symbol.
- `side`: `BUY` or `SELL`.
- `type`: `MARKET` or `LIMIT`.
- `status`: current `OrderStatus`.
- `quantity`: positive order quantity.
- `limit_price`: nullable positive price for limit orders.
- `filled_quantity`: non-negative cumulative filled quantity, constrained to be less than or equal to `quantity`.
- `created_at`, `updated_at`: persistence timestamps.

Indexes:

- `idx_orders_client_order_id`
- `idx_orders_account_id`
- `idx_orders_symbol`
- `idx_orders_status`

### `execution_reports`

Columns:

- `id`: primary key, maps to `ExecutionReportId`.
- `order_id`: non-null foreign key to `orders(id)`.
- `execution_type`: `ExecutionType`.
- `order_status`: order status after the report.
- `executed_quantity`: nullable positive quantity for fills and partial fills.
- `execution_price`: nullable positive price for fills and partial fills.
- `message`: optional rejection or lifecycle message.
- `created_at`: report creation timestamp.

Index:

- `idx_execution_reports_order_id`

### `trades`

Columns:

- `id`: primary key, maps to `TradeId`.
- `order_id`: non-null foreign key to `orders(id)`.
- `account_id`
- `symbol`
- `side`
- `quantity`: positive executed quantity.
- `price`: positive execution price.
- `created_at`: trade creation timestamp.

Index:

- `idx_trades_order_id`

### `idempotency_records`

Columns:

- `idempotency_key`: primary key.
- `request_hash`: hash or fingerprint of the original request.
- `order_id`: nullable foreign key to `orders(id)`.
- `response_status`: HTTP response status to replay for duplicate submissions.
- `created_at`: record creation timestamp.

Index:

- `idx_idempotency_records_idempotency_key`

## FIX Vocabulary Mapping

The project may discuss simplified FIX mappings without implementing FIX:

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
