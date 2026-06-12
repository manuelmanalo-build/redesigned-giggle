# Local Load Test

This directory contains a small k6 load script for local diagnostics. It is intentionally not part of normal CI.

## Prerequisites

- Docker Compose dependencies running: `docker compose up -d`
- Spring Boot app running with the `local` profile
- k6 installed locally

## Default Run

```bash
k6 run scripts/load/order-load-test.js
```

The default run uses:

- `BASE_URL=http://localhost:8080`
- `VUS=5`
- `ITERATIONS=100`
- `INCLUDE_INVALID=true`

The scenario submits a safe mix of market orders, fillable limit orders, non-fillable limit orders, and suspended-account requests that are expected to return `400`.

## Tuned Local Run

```bash
VUS=10 ITERATIONS=250 k6 run scripts/load/order-load-test.js
```

PowerShell:

```powershell
$env:VUS = "10"
$env:ITERATIONS = "250"
k6 run scripts/load/order-load-test.js
```

## Useful Environment Variables

- `BASE_URL`: application URL.
- `VUS`: number of concurrent virtual users.
- `ITERATIONS`: total submitted iterations shared across VUs.
- `MAX_DURATION`: maximum scenario duration, default `5m`.
- `INCLUDE_INVALID`: set to `false` to skip validation-failure traffic.
- `PAUSE_SECONDS`: delay between iterations per VU, default `0.05`.
- `SETTLE_SECONDS`: delay before final Actuator metric sampling, default `2`.

## What To Watch

- k6 summary: request throughput, p50, p95, and p99 latency.
- `/actuator/metrics/trade.messages.processing.duration`: consumer processing count and latency.
- `/actuator/metrics/hikaricp.connections.active`: active database connections.
- `/actuator/metrics/hikaricp.connections.pending`: threads waiting for a connection.
- `outbox_events` and `processed_messages`: application-side publish and consume lag.
- Artemis queue depth through the broker console or CLI.
- PostgreSQL locks using `scripts/sql/db-lock-diagnostics.sql`.

## Guardrails

Keep the default run small on laptops. Increase `VUS` and `ITERATIONS` gradually and stop when queue depth, Hikari pending threads, or p99 latency starts to climb.
