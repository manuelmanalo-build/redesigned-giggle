# realtime-trade-processing-simulator

An interview-prep Java 21 backend project that simulates a simplified real-time order processing platform.

The system accepts orders through REST, validates and persists them, writes order-submitted events to a transactional outbox, relays those events to JMS, processes them asynchronously, simulates executions, creates execution reports and trades, updates order state, and exposes query APIs.

The repository currently contains a working Spring Boot service with a pure domain model, Flyway-managed PostgreSQL persistence mappings, REST order submission/retrieval APIs, idempotency handling, transactional outbox publication, processed-message inbox diagnostics, asynchronous order-submitted consumption, deterministic execution simulation, and container-backed integration tests.

## Project Summary

This project is intended to demonstrate how to design and build a production-shaped backend system with Java 21, Spring Boot 3.x, PostgreSQL, ActiveMQ Artemis, Maven, Docker Compose, Testcontainers, and GitHub Actions.

The MVP models:

- Seeded account and instrument reference data used to validate order submissions.
- REST reference-data APIs for reading, creating, and updating accounts and instruments.
- Idempotent order submission.
- Reference-data validation for active accounts and active instruments.
- SQL-backed order persistence.
- Transactional outbox storage and relay for reliable JMS publication.
- JMS-backed asynchronous processing.
- Processed-message inbox tracking for consumer idempotency and retry/DLQ diagnostics.
- Deterministic execution simulation.
- Execution report creation.
- Trade creation for filled orders. Partial-fill states are modeled as a planned lifecycle extension.
- Order status updates.
- REST reads for orders, execution reports, and trades.
- Correlation IDs and structured logging.

## Target Interview Requirements

This project is designed to demonstrate:

- Core Java 21 language and standard library usage.
- Object-oriented modeling of account/instrument identifiers, orders, execution reports, trades, and idempotency.
- Data structures and relational indexes for lookup, deduplication, pagination-ready query design, and history.
- Multithreading and asynchronous processing with JMS consumers.
- JVM and GC awareness around throughput, allocation, backpressure, and listener concurrency.
- REST API design with Spring Web.
- SQL persistence with PostgreSQL and Spring Data JPA.
- JMS messaging with ActiveMQ Artemis.
- Test-driven development with JUnit 5, Mockito, AssertJ, and Testcontainers.
- CI/CD with Maven and GitHub Actions.
- Distributed system concepts such as idempotency, transaction boundaries, retries, and DLQ-ready design.
- AWS deployment discussion points.
- Optional FIX and trade lifecycle vocabulary.

## MVP Documentation

- [Product Spec](docs/product-spec.md)
- [Architecture Spec](docs/architecture-spec.md)
- [API Spec](docs/api-spec.md)
- [Domain Model](docs/domain-model.md)
- [Testing Strategy](docs/testing-strategy.md)
- [Engineering Standards](docs/engineering-standards.md)
- [FIX Protocol Notes](docs/fix-protocol-notes.md)
- [AWS Deployment Notes](docs/aws-deployment-notes.md)
- [JVM and GC Performance Notes](docs/jvm-gc-performance-notes.md)
- [Demo Script](docs/demo-script.md)
- [Project Walkthrough Script](docs/project-walkthrough-script.md)
- [Interview Talking Points](docs/interview-talking-points.md)

## Quickstart

Prerequisites:

- Java 21.
- Docker and Docker Compose.

The repository includes the Maven wrapper. On macOS/Linux/GitHub Actions use `./mvnw`; on Windows PowerShell use `.\mvnw.cmd`.

Start local infrastructure:

```bash
docker compose up -d
```

This starts:

- PostgreSQL on `localhost:5432`.
- ActiveMQ Artemis JMS broker on `localhost:61616`.
- Artemis web console on `http://localhost:8161`.
- Accepted orders are first written to `outbox_events`; the relay publishes pending rows to the JMS queue `order.submitted`.
- The local application consumes `order.submitted` by default, tracks processing in `processed_messages`, and writes execution reports, trades, and order status updates.

Default local credentials:

- PostgreSQL database: `trade_simulator`.
- PostgreSQL username: `trade_user`.
- PostgreSQL password: `trade_password`.
- Artemis username: `artemis`.
- Artemis password: `artemis`.

Seeded reference data:

- Active account: `ACC-001`.
- Inactive accounts for validation demos: `ACC-002` suspended, `ACC-003` closed.
- Active instruments: `AAPL`, `MSFT`, `TSLA`.
- Inactive instruments for validation demos: `HALT1` halted, `OLD1` delisted.

Useful reference-data endpoints:

- `GET /api/v1/accounts`
- `GET /api/v1/accounts/{accountId}`
- `POST /api/v1/accounts`
- `PUT /api/v1/accounts/{accountId}`
- `GET /api/v1/instruments`
- `GET /api/v1/instruments/{symbol}`
- `POST /api/v1/instruments`
- `PUT /api/v1/instruments/{symbol}`

Start the application locally after Docker Compose is running:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows PowerShell equivalent:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

Run the demo:

- Follow [docs/demo-script.md](docs/demo-script.md) for copy/paste `curl` commands that submit orders, show idempotency, and inspect health/metrics.

Run tests:

```bash
./mvnw test
```

On Windows with Docker Desktop, Testcontainers is configured to use `tcp://localhost:2375` and Docker API `1.40` during Maven test runs. Keep Docker Desktop's unauthenticated TCP option enabled for local Testcontainers tests.

Run the full build:

```bash
./mvnw clean verify
```

Health endpoint:

```bash
curl http://localhost:8080/actuator/health
```

Useful operational endpoints:

- `GET /actuator/health`: application, database, and broker health where the corresponding components are configured.
- `GET /actuator/info`: application info endpoint.
- `GET /actuator/metrics`: available Micrometer meter names.
- `GET /actuator/metrics/trade.orders.submitted`
- `GET /actuator/metrics/trade.orders.rejected`
- `GET /actuator/metrics/trade.execution_reports.created`
- `GET /actuator/metrics/trade.trades.created`
- `GET /actuator/metrics/trade.messages.processing.failures`
- `GET /actuator/metrics/trade.messages.processing.duration`

REST responses include an `X-Correlation-Id` response header. If the client does not provide one, the application generates one and uses it in logs and order-submitted events.

If Java and Maven were installed project-locally under `.toolchain`, load them in the current PowerShell session with:

```powershell
.\scripts\dev-env.ps1
```

## Command List

Local commands:

```bash
docker compose up -d
./mvnw test
./mvnw clean verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
./scripts/run-local-with-gc-logs.sh
./scripts/run-load-demo.sh
docker build -t realtime-trade-processing-simulator:local .
docker compose down
```

Windows PowerShell equivalents use `.\mvnw.cmd` instead of `./mvnw`.

CI commands:

```bash
./mvnw -v
./mvnw -B -DskipTests compile
./mvnw -B clean verify
docker build --tag realtime-trade-processing-simulator:ci .
```

## Architecture

The intended architecture is a single Spring Boot service with REST APIs, PostgreSQL persistence, a transactional outbox relay, a processed-message inbox, JMS producer and consumer components, explicit transaction boundaries, and correlation-aware logging.

Current package roots:

- `api`
- `application`
- `common`
- `config`
- `domain`
- `fix`
- `messaging`
- `observability`
- `persistence`

See [docs/architecture-spec.md](docs/architecture-spec.md).

## JVM and GC Notes

For local JVM/GC observation, see [docs/jvm-gc-performance-notes.md](docs/jvm-gc-performance-notes.md).

Optional helper scripts:

- `./scripts/run-local-with-gc-logs.sh`: starts the packaged app with local demo heap settings, G1GC, and rotating GC logs under `logs/gc`.
- `./scripts/run-load-demo.sh`: sends a small configurable burst of order submissions to a running local app. This is a smoke/load demo, not a benchmark.

## Development Workflow

Expected workflow:

- Start from the specs.
- Write a failing test for the next behavior.
- Implement the smallest coherent behavior.
- Run local tests.
- Update docs when API, domain, persistence, messaging, or operational behavior changes.
- Keep branches and pull requests focused.

## Testing

The current test suite includes domain unit tests, execution simulator unit tests, controller validation tests, outbox writer/relay tests, processed-message inbox assertions, publisher unit tests, application startup smoke tests, PostgreSQL Testcontainers integration tests, and an Artemis-backed end-to-end JMS flow test.

See [docs/testing-strategy.md](docs/testing-strategy.md).

Docker must be running for the PostgreSQL and Artemis integration tests.
