# realtime-trade-processing-simulator

An interview-prep Java 21 backend project that simulates a simplified real-time order processing platform.

The system will accept orders through REST, validate and persist them, publish order-submitted events to JMS, process those events asynchronously, simulate executions, create execution reports and trades, update order state, and expose query APIs.

The repository currently contains the Spring Boot skeleton, the pure domain model, Flyway-managed PostgreSQL persistence mappings, REST order submission/retrieval APIs, idempotency handling, JMS order-submitted publication, asynchronous order-submitted consumption, deterministic execution simulation, and container-backed integration tests.

## Project Summary

This project is intended to demonstrate how to design and build a production-shaped backend system with Java 21, Spring Boot 3.x, PostgreSQL, ActiveMQ Artemis, Maven, Docker Compose, Testcontainers, and GitHub Actions.

The MVP models:

- Account and instrument reference data.
- Idempotent order submission.
- SQL-backed order persistence.
- JMS-backed asynchronous processing.
- Deterministic execution simulation.
- Execution report creation.
- Trade creation for fills and partial fills.
- Order status updates.
- REST reads for orders, execution reports, and trades.
- Correlation IDs and structured logging.

## Target Interview Requirements

This project is designed to demonstrate:

- Core Java 21 language and standard library usage.
- Object-oriented modeling of accounts, instruments, orders, execution reports, trades, and idempotency.
- Data structures and relational indexes for lookup, deduplication, pagination, and history.
- Multithreading and asynchronous processing with JMS consumers.
- JVM and GC awareness around throughput, allocation, backpressure, and listener concurrency.
- REST API design with Spring Web.
- SQL persistence with PostgreSQL and Spring Data JPA.
- JMS messaging with ActiveMQ Artemis.
- Test-driven development with JUnit 5, Mockito, AssertJ, and Testcontainers.
- CI/CD with Maven and GitHub Actions.
- Distributed system concepts such as retries, DLQs, idempotency, and transaction boundaries.
- AWS deployment discussion points.
- Optional FIX and trade lifecycle vocabulary.

## MVP Documentation

- [Product Spec](docs/product-spec.md)
- [Architecture Spec](docs/architecture-spec.md)
- [API Spec](docs/api-spec.md)
- [Domain Model](docs/domain-model.md)
- [Testing Strategy](docs/testing-strategy.md)
- [Engineering Standards](docs/engineering-standards.md)
- [Interview Talking Points](docs/interview-talking-points.md)

## Local Setup

Prerequisites:

- Java 21.
- Maven 3.9 or newer.
- Docker and Docker Compose.

Start local infrastructure:

```bash
docker compose up -d
```

This starts:

- PostgreSQL on `localhost:5432`.
- ActiveMQ Artemis JMS broker on `localhost:61616`.
- Artemis web console on `http://localhost:8161`.
- Order submission events are published to the JMS queue `order.submitted`.
- The local application consumes `order.submitted` by default and writes execution reports, trades, and order status updates.

Default local credentials:

- PostgreSQL database: `trade_simulator`.
- PostgreSQL username: `trade_user`.
- PostgreSQL password: `trade_password`.
- Artemis username: `artemis`.
- Artemis password: `artemis`.

Run tests:

```bash
mvn test
```

On Windows with Docker Desktop, Testcontainers is configured to use `tcp://localhost:2375` and Docker API `1.40` during Maven test runs. Keep Docker Desktop's unauthenticated TCP option enabled for local Testcontainers tests.

Run the full build:

```bash
mvn clean verify
```

Start the application locally after Docker Compose is running:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Health endpoint:

```bash
curl http://localhost:8080/actuator/health
```

If Java and Maven were installed project-locally under `.toolchain`, load them in the current PowerShell session with:

```powershell
.\scripts\dev-env.ps1
```

## Architecture

The intended architecture is a single Spring Boot service with REST APIs, PostgreSQL persistence, JMS producer and consumer components, explicit transaction boundaries, and correlation-aware logging.

Current package roots:

- `api`
- `application`
- `common`
- `config`
- `domain`
- `messaging`
- `observability`
- `persistence`

See [docs/architecture-spec.md](docs/architecture-spec.md).

## Development Workflow

Expected workflow:

- Start from the specs.
- Write a failing test for the next behavior.
- Implement the smallest coherent behavior.
- Run local tests.
- Update docs when API, domain, persistence, messaging, or operational behavior changes.
- Keep branches and pull requests focused.

## Testing

The current test suite includes domain unit tests, execution simulator unit tests, controller validation tests, publisher unit tests, application startup smoke tests, and PostgreSQL Testcontainers integration tests for persistence, REST API behavior, and consumer-side execution processing. Broker-backed JMS tests remain a future hardening step.

See [docs/testing-strategy.md](docs/testing-strategy.md).

Docker must be running for the PostgreSQL integration tests.
