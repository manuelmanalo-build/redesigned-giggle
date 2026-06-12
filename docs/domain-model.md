# Domain Model

## Purpose

This document defines the intended domain model for the MVP. The model is simplified, but it should be precise enough to drive implementation and tests.

## Entities

### Account

Represents a trading account. The MVP persists account reference data, exposes it through REST management endpoints, and uses it to validate order submissions before an order is accepted.

Fields:

- `accountId`: stable external identifier.
- `displayName`: human-readable account name.
- `status`: `ACTIVE`, `SUSPENDED`, or `CLOSED`.
- `createdAt`
- `updatedAt`

- Only active accounts can submit orders.
- Accounts can be created and updated through the reference-data API. The account ID is stable and cannot be renamed.
- Account IDs are unique.
- Unknown, suspended, and closed accounts are hard rejected by the API with `400 Bad Request`; no order, idempotency record, or outbox event is created.

### Instrument

Represents a tradable instrument. The MVP persists instrument reference data, exposes it through REST management endpoints, and uses it to validate order submissions before an order is accepted.

Fields:

- `symbol`: unique symbol, such as `AAPL`.
- `name`
- `assetClass`: `EQUITY`, `ETF`, `OPTION`, `FUTURE`, or `CRYPTO`.
- `status`: `ACTIVE`, `HALTED`, or `DELISTED`.
- `tickSize`
- `createdAt`
- `updatedAt`

- Only active instruments can be ordered.
- Instruments can be created and updated through the reference-data API. The symbol is stable and cannot be renamed.
- Symbol is unique.
- Unknown, halted, and delisted instruments are hard rejected by the API with `400 Bad Request`; no order, idempotency record, or outbox event is created.

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
- `remainingQuantity`: derived from persisted `quantity` and `filledQuantity`.
- `averageExecutionPrice`: planned extension.
- `status`
- `version`: planned extension if optimistic locking is added.
- `createdAt`
- `updatedAt`

Rules:

- Quantity must be greater than zero.
- `LIMIT` orders require a positive limit price.
- `MARKET` orders do not require a limit price.
- Filled quantity cannot exceed order quantity.
- Remaining quantity equals quantity minus filled quantity.
- Orders can be cancelled only from `ACCEPTED` or `PARTIALLY_FILLED`.
- Limit orders can be replaced only from `ACCEPTED` or `PARTIALLY_FILLED`.
- Replacement quantity must be greater than or equal to already filled quantity.
- Market order replacement is rejected in the MVP.
- Replace updates the existing order row in place. Accepted-order replacements are re-evaluated through the existing outbox/JMS processing path. This keeps the implementation small; a production system would usually preserve explicit order versions or amendment history.

### ExecutionReport

Represents the result of processing an order event.

Fields:

- `executionReportId`
- `orderId`
- `executionType`
- `orderStatus`
- `lastQuantity`: represented as `executedQuantity` in the current persistence/API model.
- `lastPrice`: represented as `executionPrice` in the current persistence/API model.
- `cumulativeQuantity`: planned extension.
- `leavesQuantity`: planned extension.
- `averagePrice`: planned extension.
- `message`
- `correlationId`
- `createdAt`

Rules:

- `lastQuantity` is positive for fill and partial-fill reports.
- `lastPrice` is positive when `lastQuantity` is positive.
- Rejected reports must include a message.
- Accepted, rejected, replaced, and cancelled reports do not carry fill quantity or price.

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
- `tradeDate`: planned extension; current persistence uses `createdAt`.
- `createdAt`

Rules:

- A trade is created only for `FILL` and `PARTIAL_FILL` execution types.
- Trade quantity and price must be positive.
- One execution report should create at most one trade in the MVP.

### IdempotencyRecord

Represents deduplication state for REST submit, cancel, and replace workflows. Message-consumption deduplication is handled separately by `processed_messages`.

Fields:

- `idempotencyKey`
- `requestFingerprint`: persisted as `request_hash`.
- `resourceId`: persisted as `order_id`.
- `responseStatus`
- `status`: planned extension.
- `createdAt`
- `updatedAt`

Rules:

- `idempotencyKey` is unique for REST write operations.
- Reuse with a different request fingerprint is a conflict.
- Completed records return the same response status and stored response body snapshot for equivalent retry requests.

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
- `CANCELLED`: client cancellation accepted for an open order.

### ExecutionType

- `ACCEPTED`: order accepted by the system.
- `REJECTED`: order rejected by processing.
- `PARTIAL_FILL`: order partially executed.
- `FILL`: order fully executed.
- `REPLACED`: order quantity or limit price amended in place.
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

Replace keeps the current status as `ACCEPTED` or `PARTIALLY_FILLED`; it amends quantity and/or limit price and records an execution report with `ExecutionType.REPLACED`.

Disallowed transitions:

- `FILLED` to `ACCEPTED`.
- `REJECTED` to `FILLED`.
- `REJECTED` to `PARTIALLY_FILLED`.
- `CANCELLED` to any later processing state.
- Any transition that makes filled quantity exceed ordered quantity.

## Execution Simulation Rules

The MVP simulator is deterministic for tests. Current rules:

- For `MARKET` orders, fill the full quantity at the configured simulated market price.
- For `LIMIT BUY`, fill when simulated market price is less than or equal to limit price.
- For `LIMIT SELL`, fill when simulated market price is greater than or equal to limit price.
- If no fill is possible, leave the order `ACCEPTED`, write an `ACCEPTED` execution report with a no-fill message, and create no trade.
- Partial fills are a planned extension.

## Invariants

- Accepted orders carry non-blank `account_id` and `symbol` that were validated against active reference data at submission time.
- Every execution report references exactly one order.
- Every trade references exactly one order and one execution report.
- A fill or partial fill execution report must create a trade.
- Duplicate message delivery must not create duplicate execution reports or trades.
- Current order status must agree with cumulative execution state.

## Current Persistence Schema

The persistence schema includes core trade-processing tables plus reference-data tables used by order submission validation. Orders still denormalize `account_id` and `symbol` for query and audit convenience.

### `accounts`

Columns:

- `id`: primary key, stable account identifier.
- `display_name`: account display name.
- `status`: `ACTIVE`, `SUSPENDED`, or `CLOSED`.
- `created_at`, `updated_at`: persistence timestamps.

Seed rows:

- `ACC-001`: `ACTIVE`
- `ACC-002`: `SUSPENDED`
- `ACC-003`: `CLOSED`

Database constraints enforce valid account statuses.

Indexes:

- `idx_accounts_status`

### `instruments`

Columns:

- `symbol`: primary key, normalized instrument symbol.
- `name`: instrument display name.
- `asset_class`: `EQUITY`, `ETF`, `OPTION`, `FUTURE`, or `CRYPTO`.
- `status`: `ACTIVE`, `HALTED`, or `DELISTED`.
- `tick_size`: optional positive tick size.
- `created_at`, `updated_at`: persistence timestamps.

Seed rows:

- `AAPL`: `ACTIVE`
- `MSFT`: `ACTIVE`
- `TSLA`: `ACTIVE`
- `HALT1`: `HALTED`
- `OLD1`: `DELISTED`

Database constraints enforce valid asset classes, valid instrument statuses, and positive tick size when present.

Indexes:

- `idx_instruments_status`
- `idx_instruments_asset_class`

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

Database constraints also enforce:

- `side` is `BUY` or `SELL`.
- `type` is `MARKET` or `LIMIT`.
- `status` is a known `OrderStatus`.
- `MARKET` orders have no `limit_price`.
- `LIMIT` orders have a positive `limit_price`.

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

Database constraints also enforce:

- `execution_type` is a known `ExecutionType`.
- `order_status` is a known `OrderStatus`.
- `FILL` and `PARTIAL_FILL` reports include executed quantity and execution price.
- Non-fill reports do not include executed quantity or execution price.

Index:

- `idx_execution_reports_order_id`

### `trades`

Columns:

- `id`: primary key, maps to `TradeId`.
- `order_id`: non-null foreign key to `orders(id)`.
- `execution_report_id`: non-null foreign key to `execution_reports(id)`, unique so one execution report creates at most one trade.
- `account_id`
- `symbol`
- `side`
- `quantity`: positive executed quantity.
- `price`: positive execution price.
- `created_at`: trade creation timestamp.

Database constraints also enforce `side` as `BUY` or `SELL`.

Indexes:

- `idx_trades_order_id`
- `idx_trades_execution_report_id`

### `idempotency_records`

Columns:

- `idempotency_key`: primary key.
- `request_hash`: hash or fingerprint of the original request.
- `order_id`: nullable foreign key to `orders(id)`.
- `response_status`: HTTP response status to replay for duplicate submissions.
- `response_body`: stored JSON response snapshot to replay for duplicate requests with the same fingerprint.
- `created_at`: record creation timestamp.

Database constraints also enforce `response_status` in the valid HTTP status code range. REST submission claims use the `idempotency_key` primary key with PostgreSQL conflict handling so concurrent first submissions with the same key resolve to one stored order and one replayable response snapshot.

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
