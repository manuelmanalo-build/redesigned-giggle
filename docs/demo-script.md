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
```

## 4. Submit A Valid Market Order

Market orders in the current simulator fill completely at the configured simulated market price, which defaults to `100.00`.

```powershell
$MarketBody = @"
{
  "clientOrderId": "DEMO-MARKET-$RunId",
  "accountId": "ACC-DEMO",
  "symbol": "AAPL",
  "side": "BUY",
  "type": "MARKET",
  "quantity": 100
}
"@

$MarketResponse = curl.exe -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $MarketKey" `
  -H "X-Correlation-Id: corr-market-$RunId" `
  --data $MarketBody | ConvertFrom-Json

$MarketResponse | ConvertTo-Json -Depth 5
$MarketOrderId = $MarketResponse.orderId
```

Expected result:

- HTTP response body contains an `orderId`.
- Initial order status is `ACCEPTED`.
- Async JMS processing should later update it to `FILLED`.

## 5. Submit A Valid Limit Order

This limit buy order should fill because the limit price `105.00` is above the default simulated market price `100.00`.

```powershell
$LimitBody = @"
{
  "clientOrderId": "DEMO-LIMIT-$RunId",
  "accountId": "ACC-DEMO",
  "symbol": "MSFT",
  "side": "BUY",
  "type": "LIMIT",
  "quantity": 50,
  "limitPrice": 105.00
}
"@

$LimitResponse = curl.exe -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $LimitKey" `
  -H "X-Correlation-Id: corr-limit-$RunId" `
  --data $LimitBody | ConvertFrom-Json

$LimitResponse | ConvertTo-Json -Depth 5
$LimitOrderId = $LimitResponse.orderId
```

Wait briefly for asynchronous processing:

```powershell
Start-Sleep -Seconds 2
```

## 6. Retrieve Order Status

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

## 7. Retrieve Execution Reports

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

## 8. Retrieve Trades

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

## 9. Demonstrate Idempotency With The Same Idempotency Key

Submit the same market order body again with the same `Idempotency-Key`:

```powershell
curl.exe -i -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $MarketKey" `
  -H "X-Correlation-Id: corr-market-replay-$RunId" `
  --data $MarketBody
```

Expected result:

- The API returns the same order ID and response status. If async processing has already completed, the body may show the current `FILLED` state rather than the original `ACCEPTED` state.
- The `orderId` should match `$MarketOrderId`.
- The event is not republished for the replay.

## 10. Demonstrate Conflict With The Same Idempotency Key And Different Body

Change the request body but reuse the same market idempotency key:

```powershell
$ConflictBody = @"
{
  "clientOrderId": "DEMO-MARKET-$RunId",
  "accountId": "ACC-DEMO",
  "symbol": "AAPL",
  "side": "BUY",
  "type": "MARKET",
  "quantity": 200
}
"@

curl.exe -i -s -X POST "$BaseUrl/api/v1/orders" `
  -H "Content-Type: application/json" `
  -H "Accept: application/json" `
  -H "Idempotency-Key: $MarketKey" `
  -H "X-Correlation-Id: corr-market-conflict-$RunId" `
  --data $ConflictBody
```

Expected result:

- HTTP status is `409 Conflict`.
- Response body uses the standard error shape and includes `errorCode`, `message`, `path`, and `correlationId`.

## 11. Show Health And Metrics Endpoints

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

## 12. Stop Local Dependencies

When finished:

```powershell
docker compose down
```
