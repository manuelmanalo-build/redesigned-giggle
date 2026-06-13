# Demo Script

## Purpose

This script demonstrates the current local MVP: REST order submission, idempotency, PostgreSQL persistence, JMS async processing, execution reports, trades, health, and metrics.

The commands below are written for Windows PowerShell and use `curl.exe` to avoid PowerShell's `curl` alias. On macOS/Linux/Git Bash, use `curl` instead of `curl.exe` and `./mvnw` instead of `.\mvnw.cmd`.

## 1. Start Dependencies With Docker Compose

Run from the repository root:

```powershell
docker compose up -d
```

This starts:

- PostgreSQL on `localhost:5432`.
- ActiveMQ Artemis on `localhost:61616`.
- Artemis console on `http://localhost:8161`.

Optional check:

```powershell
docker compose ps
```

## 2. Start The Spring Boot App

Open a second terminal from the repository root:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Wait until the logs show that the application has started on port `8080`.

## 3. Prepare Demo Variables

Open a third terminal for API calls:

```powershell
$BaseUrl = "http://localhost:8080"
$RunId = [guid]::NewGuid().ToString("N").Substring(0, 8)
$MarketKey = "demo-market-$RunId"
$LimitKey = "demo-limit-$RunId"
$AmendKey = "demo-amend-$RunId"
$ReplaceKey = "demo-replace-$RunId"
$CancelKey = "demo-cancel-$RunId"
New-Item -ItemType Directory -Force -Path target | Out-Null
```

## 4. View And Manage Reference Data

List seeded accounts and instruments:

```powershell
curl.exe -s "$BaseUrl/api/v1/accounts" | ConvertFrom-Json | ConvertTo-Json -Depth 5
curl.exe -s "$BaseUrl/api/v1/instruments" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Create and update demo reference data:

```powershell
$DemoAccountId = "DEMO-ACC-$RunId"
$DemoSymbol = "ZZ$RunId".ToUpper()

$CreateAccountBody = @"
{
  "accountId": "$DemoAccountId",
  "displayName": "Demo Managed Account",
  "status": "ACTIVE"
}
"@
$CreateAccountFile = "target\demo-create-account-$RunId.json"
Set-Content -Path $CreateAccountFile -Value $CreateAccountBody -Encoding utf8

curl.exe -s -X POST "$BaseUrl/api/v1/accounts" `
  -H "Content-Type: application/json" `
  --data-binary "@$CreateAccountFile" | ConvertFrom-Json | ConvertTo-Json -Depth 5

$CreateInstrumentBody = @"
{
  "symbol": "$DemoSymbol",
  "name": "Demo Managed Equity",
  "assetClass": "EQUITY",
  "status": "HALTED",
  "tickSize": 0.01
}
"@
$CreateInstrumentFile = "target\demo-create-instrument-$RunId.json"
Set-Content -Path $CreateInstrumentFile -Value $CreateInstrumentBody -Encoding utf8

curl.exe -s -X POST "$BaseUrl/api/v1/instruments" `
  -H "Content-Type: application/json" `
  --data-binary "@$CreateInstrumentFile" | ConvertFrom-Json | ConvertTo-Json -Depth 5

$UpdateInstrumentBody = @"
{
  "name": "Demo Managed Equity",
  "assetClass": "EQUITY",
  "status": "ACTIVE",
  "tickSize": 0.01
}
"@
$UpdateInstrumentFile = "target\demo-update-instrument-$RunId.json"
Set-Content -Path $UpdateInstrumentFile -Value $UpdateInstrumentBody -Encoding utf8

curl.exe -s -X PUT "$BaseUrl/api/v1/instruments/$DemoSymbol" `
  -H "Content-Type: application/json" `
  --data-binary "@$UpdateInstrumentFile" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

The order examples below use seeded `ACC-001` and `AAPL` to keep the main trade-processing flow predictable.

## 5. Submit A Valid Market Order

Market orders in the current simulator fill completely at the configured simulated market price, which defaults to `100.00`.

```powershell
$MarketBody = @"
{
  "clientOrderId": "DEMO-MARKET-$RunId",
  "accountId": "ACC-001",
  "symbol": "AAPL",
  "side": "BUY",
  "type": "MARKET",
  "quantity": 100
}
"@
$MarketFile = "target\demo-market-$RunId.json"
Set-Content -Path $MarketFile -Value $MarketBody -Encoding utf8

$MarketResponse = curl.exe -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $MarketKey" `
  -H "X-Correlation-Id: corr-market-$RunId" `
  --data-binary "@$MarketFile" | ConvertFrom-Json

$MarketResponse | ConvertTo-Json -Depth 5
$MarketOrderId = $MarketResponse.orderId
```

Expected result:

- HTTP response body contains an `orderId`.
- Initial order status is `ACCEPTED`.
- Async JMS processing should later update it to `FILLED`.

## 6. Submit A Valid Limit Order

This limit buy order should fill because the limit price `105.00` is above the default simulated market price `100.00`.

```powershell
$LimitBody = @"
{
  "clientOrderId": "DEMO-LIMIT-$RunId",
  "accountId": "ACC-001",
  "symbol": "MSFT",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 50,
  "limitPrice": 105.00
}
"@
$LimitFile = "target\demo-limit-$RunId.json"
Set-Content -Path $LimitFile -Value $LimitBody -Encoding utf8

$LimitResponse = curl.exe -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $LimitKey" `
  -H "X-Correlation-Id: corr-limit-$RunId" `
  --data-binary "@$LimitFile" | ConvertFrom-Json

$LimitResponse | ConvertTo-Json -Depth 5
$LimitOrderId = $LimitResponse.orderId
```

Wait briefly for asynchronous processing:

```powershell
Start-Sleep -Seconds 2
```

## 7. Retrieve Order Status

Retrieve the market order:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders/$MarketOrderId" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Retrieve the limit order:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders/$LimitOrderId" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Expected result:

- The market order should become `FILLED`.
- The marketable limit order should become `FILLED`.
- If a response still shows `ACCEPTED`, wait another second and run the GET again.

## 8. Retrieve Execution Reports

Market order execution reports:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders/$MarketOrderId/execution-reports" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Limit order execution reports:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders/$LimitOrderId/execution-reports" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Expected result:

- Filled orders should have a `FILL` execution report.
- The report includes `executedQuantity`, `executionPrice`, `orderStatus`, and `createdAt`.

## 9. Retrieve Trades

Market order trades:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders/$MarketOrderId/trades" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Limit order trades:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders/$LimitOrderId/trades" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Expected result:

- Filled orders should each have one trade.
- Trade responses include `tradeId`, `orderId`, `executionReportId`, `accountId`, `symbol`, `side`, `quantity`, `price`, and `createdAt`.

## 10. Search Operational Views

Search orders for the demo account:

```powershell
curl.exe -s "$BaseUrl/api/v1/orders?accountId=ACC-001&symbol=AAPL&page=0&size=20&sortBy=createdAt&sortDirection=desc" |
  ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Search execution reports for the market order:

```powershell
curl.exe -s "$BaseUrl/api/v1/execution-reports?orderId=$MarketOrderId&page=0&size=20" |
  ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Search trades for the demo account and symbol:

```powershell
curl.exe -s "$BaseUrl/api/v1/trades?accountId=ACC-001&symbol=AAPL&page=0&size=20" |
  ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Expected result:

- Responses include `items`, `page`, `size`, `totalElements`, and `totalPages`.
- Default sorting is newest first by `createdAt`.
- Page size is capped at `100`.

## 11. Demonstrate Replace And Cancel

Submit a non-marketable limit order that should remain open at the default simulated market price:

```powershell
$AmendBody = @"
{
  "clientOrderId": "DEMO-AMEND-$RunId",
  "accountId": "ACC-001",
  "symbol": "AAPL",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 100,
  "limitPrice": 90.00
}
"@
$AmendFile = "target\demo-amend-$RunId.json"
Set-Content -Path $AmendFile -Value $AmendBody -Encoding utf8

$AmendResponse = curl.exe -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $AmendKey" `
  -H "X-Correlation-Id: corr-amend-$RunId" `
  --data-binary "@$AmendFile" | ConvertFrom-Json

$AmendOrderId = $AmendResponse.orderId
```

Replace the open limit order:

```powershell
$ReplaceBody = @"
{
  "newQuantity": 150,
  "newLimitPrice": 95.00,
  "reason": "Client amended order"
}
"@
$ReplaceFile = "target\demo-replace-$RunId.json"
Set-Content -Path $ReplaceFile -Value $ReplaceBody -Encoding utf8

curl.exe -s -X POST "$BaseUrl/api/v1/orders/$AmendOrderId/replace" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $ReplaceKey" `
  --data-binary "@$ReplaceFile" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Cancel the same open order:

```powershell
$CancelBody = @"
{
  "reason": "Client requested cancel"
}
"@
$CancelFile = "target\demo-cancel-$RunId.json"
Set-Content -Path $CancelFile -Value $CancelBody -Encoding utf8

curl.exe -s -X POST "$BaseUrl/api/v1/orders/$AmendOrderId/cancel" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $CancelKey" `
  --data-binary "@$CancelFile" | ConvertFrom-Json | ConvertTo-Json -Depth 5

curl.exe -s "$BaseUrl/api/v1/orders/$AmendOrderId/execution-reports" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Expected result:

- Replace returns `200 OK` with quantity `150` and limit price `95.00`.
- Cancel returns `200 OK` with status `CANCELLED`.
- Execution reports include `REPLACED` and `CANCELLED`.

## 12. Demonstrate Idempotency With The Same Idempotency Key

Submit the same market order body again with the same `Idempotency-Key`:

```powershell
curl.exe -i -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $MarketKey" `
  -H "X-Correlation-Id: corr-market-replay-$RunId" `
  --data-binary "@$MarketFile"
```

Expected result:

- The API returns the same order ID, response status, and stored response snapshot from the original request.
- Even if async processing has already filled the order, the replay body should still show the original `ACCEPTED` response snapshot.
- The `orderId` should match `$MarketOrderId`.
- The event is not republished for the replay.

## 13. Demonstrate Conflict With The Same Idempotency Key And Different Body

Change the request body but reuse the same market idempotency key:

```powershell
$ConflictBody = @"
{
  "clientOrderId": "DEMO-MARKET-$RunId",
  "accountId": "ACC-001",
  "symbol": "AAPL",
  "side": "BUY",
  "type": "MARKET",
  "quantity": 200
}
"@
$ConflictFile = "target\demo-conflict-$RunId.json"
Set-Content -Path $ConflictFile -Value $ConflictBody -Encoding utf8

curl.exe -i -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $MarketKey" `
  -H "X-Correlation-Id: corr-market-conflict-$RunId" `
  --data-binary "@$ConflictFile"
```

Expected result:

- HTTP status is `409 Conflict`.
- Response body uses the standard error shape and includes `errorCode`, `message`, `path`, and `correlationId`.

## 14. Show Health And Metrics Endpoints

Health:

```powershell
curl.exe -s "$BaseUrl/actuator/health" | ConvertFrom-Json | ConvertTo-Json -Depth 10
```

Available metrics:

```powershell
curl.exe -s "$BaseUrl/actuator/metrics" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Order submissions metric:

```powershell
curl.exe -s "$BaseUrl/actuator/metrics/trade.orders.submitted" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Execution reports created metric:

```powershell
curl.exe -s "$BaseUrl/actuator/metrics/trade.execution_reports.created" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Trades created metric:

```powershell
curl.exe -s "$BaseUrl/actuator/metrics/trade.trades.created" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

Message processing duration metric:

```powershell
curl.exe -s "$BaseUrl/actuator/metrics/trade.messages.processing.duration" | ConvertFrom-Json | ConvertTo-Json -Depth 5
```

## 15. Stop Local Dependencies

When finished:

```powershell
docker compose down
```
