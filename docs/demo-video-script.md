# Demo Video Script

## Purpose

This is a 5-7 minute walkthrough script for presenting `realtime-trade-processing-simulator` as a senior Java backend portfolio project. It should sound natural, like a candidate explaining design choices while showing the app.

## 1. 30-Second Intro

"This project is a simplified real-time trade processing platform built with Java 21 and Spring Boot. The goal was not to build a full trading system, but to demonstrate the backend patterns I would expect in a production-shaped order-processing service.

It covers REST APIs, validation, PostgreSQL persistence, transactional boundaries, JMS messaging, asynchronous consumers, idempotency, retry diagnostics, metrics, Docker packaging, CI, and a lightweight Angular demo UI. I also added interview-focused docs for JVM and GC behavior, AWS deployment design, load testing, and FIX-style message parsing."

## 2. Architecture View

"At the edge, clients submit orders through a REST API. The API validates the request shape, then validates reference data against accounts and instruments. For example, an order is only accepted if the account and instrument are active.

Accepted orders are stored in PostgreSQL. In the same database transaction, the service also writes an outbox event. That is important because I do not want the REST request to commit an order and then lose the JMS event because the broker is temporarily down.

A separate outbox relay polls pending outbox rows and publishes `OrderSubmittedEvent` messages to the JMS queue. The relay tracks attempts, last error, backoff, and failed status.

On the consumer side, the JMS listener receives the submitted-order event. Before processing, it records the event in a processed-message inbox table. That gives me duplicate detection and operational diagnostics for failed or retried messages.

The async consumer loads the order, simulates execution, creates an execution report, creates a trade if the order fills, and updates the order status and filled quantity.

The API also exposes order retrieval, paginated operational search, execution reports, trades, cancel and replace workflows, reference-data endpoints, Actuator health and metrics, and OpenAPI docs. The Angular UI is a small operational demo over those same APIs."

## 3. Live Demo

"I'll start with the local stack. The backend can run directly with Maven, or as a container with the Compose backend profile. For the demo UI, I run the Angular app on port 4200 and it proxies API calls to the backend on port 8080.

First, I'll submit a market order. I'm using account `ACC-001` and symbol `AAPL`, both active seed records. The UI generates an idempotency key and sends it in the `Idempotency-Key` header.

After submit, the response shows the order ID, initial status, and correlation ID. Behind the scenes, the API persisted the order, persisted the idempotency record, and inserted an outbox row in the same transaction.

Now I'll refresh or search for that order. After the async consumer runs, the order moves to `FILLED`. I can open the detail view and show the execution report. The execution report records the execution type, order status, executed quantity, price, message, and timestamp.

Next I'll show the trade record. Since this market order filled, the consumer created a trade linked back to the order and execution report.

For idempotency, I can reuse the same key with the same request and get the same logical response. If I reuse the same key with a different body, the backend returns a conflict. That is intentional because idempotency keys should make retries safe, not allow different commands to share the same key.

Now I'll show a lifecycle operation. I'll create a non-fillable limit order so it stays `ACCEPTED`. From the detail page, I can replace it by increasing the quantity or changing the limit price. The backend writes a `REPLACED` execution report and re-evaluates the order. I can also cancel an eligible `ACCEPTED` or `PARTIALLY_FILLED` order, which updates status to `CANCELLED` and writes a cancel execution report.

Then I'll go to order search. This screen calls the paginated search endpoint with filters for account, symbol, status, side, type, and date range. This is meant to resemble an operational support view, not a trading blotter.

Finally, I'll show diagnostics. The app exposes health and metrics through Actuator. Outbox and processed-message diagnostics are persisted in the database. I have not exposed admin REST endpoints for those tables yet, and the UI says that honestly."

## 4. Reliability Explanation

"The key reliability pattern here is the transactional outbox.

Originally, a naive implementation would save the order and then publish directly to JMS. That creates a gap: the database commit could succeed while the JMS publish fails, or a message could publish while the database later rolls back.

With the outbox, order creation, idempotency, and event creation are atomic in PostgreSQL. If the broker is down, the outbox row remains pending and the relay retries it later.

The inbox pattern handles the other side of the problem. JMS gives at-least-once delivery, so consumers must expect duplicates. The processed-message table records event IDs, consumer name, attempt count, status, last error, and correlation ID. If a duplicate event arrives after successful processing, the consumer skips the business operation.

REST idempotency covers client retries. JMS inbox idempotency covers message redelivery. The domain and database constraints also remain defensive, so correctness does not depend on only one layer."

## 5. Performance Explanation

"For performance, I added a local load-test setup and diagnostics notes rather than pretending this laptop test is a production benchmark.

The load test submits concurrent orders, mixes market and limit orders, and reads search/detail endpoints. The goal is to observe behavior under pressure: request throughput, p95 and p99 latency, queue depth, message processing lag, Hikari connection pool pressure, and database lock waits.

If p99 latency spikes, I would break the problem down by layer.

First, check HTTP metrics: request count, error rate, p95/p99, and which endpoint is slow. Then check Hikari metrics. If pending threads are rising or connection acquisition time is high, the app is waiting for database connections.

Next I would inspect PostgreSQL using the lock diagnostic SQL in `scripts/sql/db-lock-diagnostics.sql`. I would look for blocked sessions, long transactions, and hot rows around order updates or idempotency records.

For messaging, I would check queue depth and oldest message age. Rising queue depth means the relay or consumer side is behind. But I would not blindly add consumers, because that can make database locking or connection pool pressure worse.

For JVM behavior, I would look at heap usage, allocation rate, GC pause time, and CPU. The project includes local GC log helpers and JVM notes. If GC pauses line up with p99 spikes, I would tune heap/container sizing or reduce allocation. If GC looks healthy, I would focus back on database, broker, or downstream bottlenecks."

## 6. Closing

"If I were taking this further, I would add authentication and authorization, real admin diagnostics endpoints for outbox and processed messages, a DLQ reconciliation worker, event schema versioning, stronger order version history for replace, and infrastructure-as-code for AWS.

I would also add OpenTelemetry tracing, production dashboards and alarms, a real deployment pipeline, and retention or partitioning policies for high-growth tables.

The reason I like this project for senior Java backend interviews is that it gives me concrete examples to discuss: Java domain modeling, Spring transaction boundaries, SQL schema design, indexes, idempotency, async messaging, failure modes, retry behavior, JVM performance, Docker packaging, AWS deployment tradeoffs, and how I would debug a real high-latency or reliability incident."
