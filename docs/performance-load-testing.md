# Performance Load Testing

This project includes a small local load-testing setup for diagnosing throughput, queue depth, PostgreSQL lock behavior, Hikari pool pressure, and p99 latency. It is a diagnostic aid, not a capacity certification lab.

## Goals

- Submit concurrent orders through the real REST API.
- Exercise transactional order writes, outbox relay, JMS publication, async consumption, execution report creation, and trade creation.
- Query operational read endpoints during write load.
- Observe API latency, consumer latency, queue depth, database locks, and connection pool pressure.
- Keep defaults safe enough for a laptop.

Heavy or long-running load tests are intentionally not part of normal CI.

## Prerequisites

- Java 21.
- Docker and Docker Compose.
- k6 installed locally.
- Local PostgreSQL and Artemis started with Docker Compose.

Start dependencies:

```bash
docker compose up -d
```

Start the app:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

## Run The Load Test

Default safe run:

```bash
k6 run scripts/load/order-load-test.js
```

PowerShell:

```powershell
k6 run scripts/load/order-load-test.js
```

Slightly larger local run:

```bash
VUS=10 ITERATIONS=250 k6 run scripts/load/order-load-test.js
```

PowerShell:

```powershell
$env:VUS = "10"
$env:ITERATIONS = "250"
k6 run scripts/load/order-load-test.js
```

Supported environment variables:

- `BASE_URL`: default `http://localhost:8080`.
- `VUS`: concurrent virtual users, default `5`.
- `ITERATIONS`: total scenario iterations, default `100`.
- `MAX_DURATION`: default `5m`.
- `INCLUDE_INVALID`: default `true`; includes suspended-account requests that should return `400`.
- `PAUSE_SECONDS`: delay between iterations per VU, default `0.05`.
- `SETTLE_SECONDS`: delay before final Actuator metric sampling, default `2`.

The script mixes market orders, fillable limit orders, non-fillable limit orders, and invalid reference-data requests. Valid orders use seeded account `ACC-001` and instrument `AAPL`; invalid requests use suspended account `ACC-002`.

## What The k6 Summary Shows

k6 reports:

- Total request count.
- Request failure rate.
- Throughput.
- p50, p95, and p99 latency through `http_req_duration`.
- Custom submit latency through `trade_order_submit_latency`.
- Custom query latency through `trade_query_latency`.
- Expected success/failure accounting through `trade_expected_success` and `trade_expected_failure`.

Expected `400` responses for invalid reference-data orders are counted as expected behavior by the custom checks even though raw HTTP failure views may still show non-2xx responses.

## Application Metrics

Spring Boot Actuator exposes Micrometer metrics at `/actuator/metrics`.

Useful service metrics:

- `http.server.requests`: REST request timer with configured p50/p95/p99 percentiles.
- `trade.orders.submitted`: accepted order submissions.
- `trade.orders.rejected`: validation and domain rejections.
- `trade.execution_reports.created`: execution reports written.
- `trade.trades.created`: trades written.
- `trade.messages.processing.failures`: consumer failures.
- `trade.messages.processing.duration`: consumer processing timer with configured p50/p95/p99 percentiles; its `COUNT` approximates consumed message count.

Useful Hikari metrics when the datasource is active:

- `hikaricp.connections.active`: currently borrowed connections.
- `hikaricp.connections.idle`: idle connections.
- `hikaricp.connections.pending`: threads waiting for a connection.
- `hikaricp.connections.acquire`: connection acquisition timer with configured p50/p95/p99 percentiles.
- `hikaricp.connections.timeout`: connection timeout count.

Example:

```bash
curl http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

If Hikari pending threads rise above zero during load, the app is waiting for database connections. The next question is whether the pool is too small, queries are too slow, transactions are too long, or PostgreSQL is blocked on locks or I/O.

## Queue Depth And Processing Lag

The application is intentionally built around at-least-once messaging. Queue depth can rise when order submission and outbox relay are faster than consumer processing.

Check application-side lag:

```bash
docker exec trade-simulator-postgres psql -U trade_user -d trade_simulator -c "select status, count(*) from outbox_events group by status order by status;"
docker exec trade-simulator-postgres psql -U trade_user -d trade_simulator -c "select status, count(*) from processed_messages group by status order by status;"
```

Check broker-side queue state:

```bash
docker exec trade-simulator-artemis /var/lib/artemis-instance/bin/artemis queue stat --user artemis --password artemis --url tcp://localhost:61616
```

You can also inspect the Artemis console at `http://localhost:8161` with username `artemis` and password `artemis`.

Interpretation:

- `outbox_events` stuck in `PENDING`: relay cannot publish fast enough or broker publishing is failing.
- Artemis `order.submitted` queue depth rising: consumers are slower than producers.
- `processed_messages` rows in `FAILED`: application processing failed and broker retry/DLQ behavior should be investigated.
- Trade and execution report counts flat while order submissions rise: async processing is lagging or failing.

## PostgreSQL Lock Diagnostics

Run:

```bash
psql -h localhost -U trade_user -d trade_simulator -f scripts/sql/db-lock-diagnostics.sql
```

Or through Docker:

```bash
docker exec -i trade-simulator-postgres psql -U trade_user -d trade_simulator < scripts/sql/db-lock-diagnostics.sql
```

The script reports:

- Connection count by state.
- Active or waiting sessions.
- Blocking relationships through `pg_blocking_pids`.
- Locks grouped by relation and mode.
- Long-running transactions.
- Outbox and processed-message status counts.

The queries use normal PostgreSQL catalog views available to the application database user in the local setup.

## Diagnosing Rising Queue Depth

Start by separating producer, relay, broker, and consumer paths:

1. Check API k6 latency and success rate.
2. Check `outbox_events` status counts.
3. Check Artemis `order.submitted` queue depth and consumer count.
4. Check `trade.messages.processing.duration` count and p95/p99.
5. Check `processed_messages` status and `last_error`.
6. Check Hikari active/pending connections and PostgreSQL lock diagnostics.

If the outbox is pending but the broker queue is not growing, focus on the relay or broker publish path. If the broker queue is growing, focus on consumer throughput, database locks, and listener concurrency.

## Diagnosing DB Lock Waits

Lock waits usually show up as high API or consumer p99 with Hikari connections active for longer than expected.

Use `scripts/sql/db-lock-diagnostics.sql` while the load is running. Look for:

- Sessions waiting on `Lock`.
- Blocking relationships with old blocker queries.
- Long-running transactions.
- Frequent writes to the same order, outbox, or processed-message rows.

This service uses row-level locking for order processing and explicit status transitions. That is correct for consistency, but high contention means the workload, indexes, or transaction scope should be reviewed.

## Diagnosing Hikari Pool Exhaustion

Symptoms:

- `hikaricp.connections.active` stays near the configured maximum.
- `hikaricp.connections.pending` is greater than zero.
- `hikaricp.connections.acquire` p95/p99 rises.
- API and consumer latency rise together.

Do not immediately increase the pool. First check slow SQL, lock waits, long transactions, and consumer concurrency. A larger pool can make PostgreSQL contention worse if the database is already saturated.

## Diagnosing p99 Latency Spikes

Use a layered approach:

1. Confirm whether p99 is on order submission, search endpoints, or JMS processing.
2. Compare p99 spikes with GC logs from `scripts/run-local-with-gc-logs.sh`.
3. Check CPU saturation and thread dumps.
4. Check Hikari pending/acquire metrics.
5. Check PostgreSQL locks and slow queries.
6. Check Artemis queue depth, redeliveries, and consumer count.
7. Check outbox and inbox status tables.

If GC pauses align with p99 spikes, investigate heap sizing, allocation rate, object churn, and pause goals. If GC is quiet but threads wait on JDBC or broker calls, treat it as a database, broker, or queueing bottleneck before tuning JVM flags.

## Safe Defaults And CI

The k6 script defaults to `5` VUs and `100` iterations. Keep heavy runs manual. Normal CI should continue to run unit, integration, and build checks, not performance tests.
