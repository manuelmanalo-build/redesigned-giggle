# FIX Protocol Notes

## Purpose

This project includes a lightweight FIX-style parsing module for interview demonstration only. It is not a full FIX engine and does not implement FIX session behavior, counterparty connectivity, resend handling, sequence reset handling, heartbeats, logon/logout, validation dictionaries, checksums, or production-grade certification rules.

The goal is to show how common FIX order-entry fields can be parsed and mapped into the simulator's internal order request model.

## Supported Message Format

Messages are simplified `tag=value` fields separated by either:

- SOH (`\u0001`), the standard FIX field delimiter.
- Pipe (`|`), a human-readable delimiter used in tests and examples.

Example:

```text
8=FIX.4.4|35=D|49=ACC-001|56=SIM|34=1|52=20260610-14:00:00.000|11=CLIENT-123|55=AAPL|54=1|38=100|40=2|44=185.50
```

The parser is intentionally strict for deterministic behavior:

- Blank messages are rejected.
- Malformed fields are rejected.
- Blank tags or values are rejected.
- Duplicate tags are rejected to avoid ambiguous mapping.

## Supported Tags

| Tag | Name | Used By Mapper | Notes |
| --- | --- | --- | --- |
| 8 | BeginString | No | Parsed and retained for inspection. |
| 35 | MsgType | Yes | `D` means New Order Single. |
| 49 | SenderCompID | Yes | Simplified demo maps this to `accountId`. |
| 56 | TargetCompID | No | Parsed and retained for inspection. |
| 34 | MsgSeqNum | No | Parsed only; no session sequencing is implemented. |
| 52 | SendingTime | No | Parsed only; no timestamp validation is implemented. |
| 11 | ClOrdID | Yes | Maps to `clientOrderId`. |
| 55 | Symbol | Yes | Maps to `symbol`. |
| 54 | Side | Yes | `1` maps to `BUY`; `2` maps to `SELL`. |
| 38 | OrderQty | Yes | Maps to `quantity`; must be positive. |
| 40 | OrdType | Yes | `1` maps to `MARKET`; `2` maps to `LIMIT`. |
| 44 | Price | Conditional | Required for limit orders; ignored for market orders. |

## New Order Single Mapping

Only `35=D` is mapped to an internal order request.

Mapping rules:

- `11 ClOrdID` -> `SubmitOrderRequest.clientOrderId`
- `49 SenderCompID` -> `SubmitOrderRequest.accountId`
- `55 Symbol` -> `SubmitOrderRequest.symbol`
- `54=1` -> `OrderSide.BUY`
- `54=2` -> `OrderSide.SELL`
- `40=1` -> `OrderType.MARKET`
- `40=2` -> `OrderType.LIMIT`
- `38 OrderQty` -> `SubmitOrderRequest.quantity`
- `44 Price` -> `SubmitOrderRequest.limitPrice` for limit orders

Market orders return `limitPrice = null` even if tag `44` is present, because the core domain model rejects prices on market orders.

## Explicit Non-Goals

This module does not provide:

- A FIX acceptor or initiator.
- Network socket handling.
- FIX session state.
- Message sequence enforcement.
- Heartbeats or test requests.
- Resend requests or gap fill behavior.
- BodyLength or CheckSum validation.
- FIX data dictionary validation.
- ExecutionReport FIX message generation.

For a production FIX integration, this project would use a proven FIX engine such as QuickFIX/J and keep this simplified mapper as a teaching aid or replace it entirely.
