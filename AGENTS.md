# AGENTS.md

## Project Purpose

`realtime-trade-processing-simulator` is a Java 21 Spring Boot backend project for interview preparation. It models a simplified real-time order processing platform: REST order submission, validation, SQL persistence, JMS publication, asynchronous execution simulation, execution report creation, trade creation, and query APIs.

The project should remain understandable, explainable, and reviewable. Favor clear implementation choices that can be defended in an interview over unnecessary framework complexity.

## Coding Standards

- Use Java 21 and Spring Boot 3.x conventions.
- Keep domain concepts explicit and readable.
- Prefer immutable value objects where appropriate.
- Keep business logic out of controllers.
- Keep persistence concerns out of core domain logic where practical.
- Use constructor injection for Spring dependencies.
- Use meaningful names for classes, methods, tests, database columns, and messages.
- Avoid premature abstractions.
- Keep methods small enough to reason about.
- Use structured logging when logging is introduced.
- Do not add application code before the relevant spec or test context exists.
- Keep order, execution report, trade, idempotency, persistence, and messaging behavior aligned with the docs.

## TDD Preference

- Prefer test-driven development for behavior changes.
- Start with focused unit tests for domain rules.
- Add integration tests when database, messaging, or Spring wiring behavior is involved.
- Use Testcontainers for PostgreSQL and broker-backed integration tests when those components are introduced.
- Keep tests deterministic and avoid sleeps where polling, latches, or Awaitility-style patterns would be more reliable.
- Add idempotency and concurrency tests for order submission and message consumption.

## Build Commands

Run the full build with:

```bash
./mvnw clean verify
```

For faster local feedback:

```bash
./mvnw test
```

## CI Commands

GitHub Actions should run:

```bash
./mvnw -v
./mvnw -B -DskipTests compile
./mvnw -B clean verify
docker build --tag realtime-trade-processing-simulator:ci .
```

## Test Commands

Run tests with:

```bash
./mvnw test
./mvnw verify
```

The current suite includes domain unit tests, startup smoke tests, and PostgreSQL Testcontainers integration tests. Ensure Docker is running before `./mvnw verify`.

The persistence test suite uses PostgreSQL Testcontainers. On Windows with Docker Desktop, Maven activates a Windows-only Surefire profile that expects Docker Desktop's TCP endpoint on `tcp://localhost:2375` and pins docker-java to API version `1.40`.

## Local Run Commands

Start PostgreSQL and ActiveMQ Artemis:

```bash
docker compose up -d
```

Run the application:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Branch and PR Expectations

- Use focused branches for each feature or fix.
- Keep pull requests small enough to review thoroughly.
- Include a concise summary of behavior changes.
- Include test evidence in the PR description.
- Link docs updates when behavior, APIs, architecture, or operational assumptions change.
- Avoid mixing unrelated refactors with feature work.

## Documentation Expectations

- Update documentation whenever behavior changes.
- Keep `docs/product-spec.md` aligned with user-facing capabilities.
- Keep `docs/api-spec.md` aligned with REST contracts.
- Keep `docs/domain-model.md` aligned with domain concepts and lifecycle states.
- Keep `docs/architecture-spec.md` aligned with component boundaries and data flow.
- Keep `docs/testing-strategy.md` aligned with actual test layers and commands.
- Keep `docs/engineering-standards.md` aligned with project conventions.
- Update message payload and transaction-boundary documentation whenever JMS or persistence behavior changes.

## Change Size Expectations

- Avoid large, unreviewable changes.
- Prefer incremental commits that each compile and pass tests once code exists.
- Split changes by concern: API, domain, persistence, messaging, tests, and docs.
- Do not introduce major frameworks, dependencies, or architecture shifts without updating the relevant spec.

## Completion Checklist

Before summarizing completion:

- Confirm the requested files were created or changed.
- Run the relevant tests once a test suite exists.
- If tests cannot be run, state why.
- Summarize only what changed.
- Call out any follow-up implementation step.
