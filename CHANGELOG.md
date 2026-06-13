# Changelog

## v1.0.0-backend-mvp - 2026-06-13

Backend MVP release checkpoint for `realtime-trade-processing-simulator`.

### Added

- Java 21 Spring Boot backend with Maven wrapper.
- REST APIs for order submit, cancel, replace, order retrieval, execution-report retrieval, trade retrieval, paginated search, and reference-data management.
- Domain model for orders, sides, types, statuses, execution reports, trades, quantities, prices, and identifiers.
- PostgreSQL persistence with Flyway migrations and schema constraints.
- Seeded account and instrument reference data plus validation for active accounts and instruments.
- Database-backed REST idempotency with request hashes and stored response snapshots.
- Transactional outbox for reliable order-submitted event persistence.
- Scheduled outbox relay that publishes pending events to ActiveMQ Artemis JMS.
- Processed-message inbox for consumer duplicate detection, retry diagnostics, and DLQ-ready metadata.
- Async JMS consumer with deterministic execution simulation, order locking, execution report creation, trade booking, and duplicate-message safeguards.
- Cancel and replace lifecycle workflows with execution-report audit records.
- Composite indexes for common order, execution-report, trade, outbox, and inbox query patterns.
- Structured correlation ID propagation through REST, outbox events, JMS messages, and logs.
- Actuator health and Micrometer metrics for API, order, execution, trade, message processing, and Hikari diagnostics.
- Docker Compose for PostgreSQL and ActiveMQ Artemis.
- Dockerfile for application image builds.
- Unit, integration, smoke, and broker-backed end-to-end tests with JUnit 5, AssertJ, Mockito, PostgreSQL Testcontainers, and Artemis Testcontainers.
- GitHub Actions CI workflow.
- Simplified educational FIX-style parser and New Order Single mapper.
- Local load/performance diagnostics with k6, PostgreSQL lock diagnostics, and JVM/GC helper scripts.
- Interview-focused documentation, demo script, AWS deployment notes, JVM/GC notes, and known limitations.

### Verified

- Docker Compose starts PostgreSQL and ActiveMQ Artemis dependencies.
- Spring Boot application starts with the `local` profile.
- Maven `clean verify` passes locally with Testcontainers.
- Demo curl commands cover reference data, order submission, async processing, retrieval, search, replace/cancel, idempotency replay/conflict, health, and metrics.

### Known Limitations

- See [docs/known-limitations.md](docs/known-limitations.md).
