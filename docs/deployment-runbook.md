# Deployment Runbook

## Purpose

This runbook describes how to package and run the backend as a deployment-shaped container without provisioning paid cloud resources. It is intended for interview demos, local release checks, and cloud-design discussion.

This is not a claim of production readiness. The project has no Terraform, CDK, CloudFormation, real AWS accounts, TLS certificates, authentication, dashboards, or alarms.

## Deployment Artifact

The backend artifact is a Docker image built from `Dockerfile`.

Key packaging choices:

- Multi-stage build: Maven/JDK build image, smaller JRE runtime image.
- Java 21 runtime.
- Non-root `app` user in the runtime image.
- Runtime configuration through environment variables.
- `JAVA_OPTS` for container-specific JVM flags.
- Container healthcheck against `/actuator/health/readiness`.
- No secrets baked into the image.

Build locally:

```bash
docker build -t realtime-trade-processing-simulator:local .
```

Windows PowerShell:

```powershell
docker build -t realtime-trade-processing-simulator:local .
```

## Local Container Modes

### Dependencies Only

This keeps the existing backend development workflow:

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

PowerShell:

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Full Backend Container Demo

Run PostgreSQL, Artemis, and the backend container:

```bash
docker compose --profile backend up -d --build
```

PowerShell:

```powershell
docker compose --profile backend up -d --build
```

Verify:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/v3/api-docs
```

Stop:

```bash
docker compose --profile backend down
```

## Configuration

Configuration is externalized through environment variables.

Common variables:

| Variable | Purpose | Local default |
| --- | --- | --- |
| `SERVER_PORT` | Spring Boot HTTP port inside non-Compose runs | `8080` |
| `BACKEND_PORT` | Host port mapped by Compose | `8080` |
| `SPRING_PROFILES_ACTIVE` | Runtime profile | `docker` for backend container |
| `SPRING_DATASOURCE_URL` | JDBC URL | `jdbc:postgresql://postgres:5432/trade_simulator` in Compose |
| `SPRING_DATASOURCE_USERNAME` | Database username | `trade_user` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `trade_password` |
| `SPRING_ARTEMIS_BROKER_URL` | JMS broker URL | `tcp://artemis:61616` in Compose |
| `SPRING_ARTEMIS_USER` | Broker username | `artemis` |
| `SPRING_ARTEMIS_PASSWORD` | Broker password | `artemis` |
| `TRADE_ORDER_SUBMITTED_QUEUE` | Submitted-order queue | `order.submitted` |
| `TRADE_OUTBOX_RELAY_INTERVAL_MS` | Outbox relay poll interval | `1000` |
| `TRADE_OUTBOX_BATCH_SIZE` | Outbox relay batch size | `25` |
| `TRADE_OUTBOX_MAX_ATTEMPTS` | Max publish attempts | `5` |
| `SPRING_JMS_LISTENER_CONCURRENCY` | JMS listener minimum concurrency | `1` |
| `SPRING_JMS_LISTENER_MAX_CONCURRENCY` | JMS listener maximum concurrency | `4` |
| `JAVA_OPTS` | JVM container flags | G1GC and percentage heap defaults |

For local overrides:

```bash
cp .env.example .env
```

Do not commit `.env`. It is ignored by Git.

## Health, Readiness, And Liveness

Actuator endpoints:

- `/actuator/health`: aggregate health.
- `/actuator/health/readiness`: readiness probe.
- `/actuator/health/liveness`: liveness probe.
- `/actuator/metrics`: metric names.

Recommended usage:

- Load balancer target health: `/actuator/health/readiness`.
- Container healthcheck: `/actuator/health/readiness`.
- Process restart signal: `/actuator/health/liveness`.
- Human investigation: `/actuator/health` plus logs and metrics.

The readiness endpoint should fail when the service cannot use required dependencies such as PostgreSQL or the broker.

## Release Procedure

1. Confirm the Git SHA and changelog entry.
2. Run `./mvnw clean verify`.
3. Build the image with a unique tag, such as the Git SHA.
4. Run the image locally with `docker compose --profile backend up -d --build`.
5. Check `/actuator/health/readiness`.
6. Run a smoke test:
   - Submit a market order.
   - Search the order.
   - Read execution reports and trades.
7. Push the image to the target registry in a real deployment pipeline.
8. Deploy using rolling or blue/green strategy.
9. Watch logs, HTTP latency, error rate, database connections, queue depth, and outbox failures.

## Rollback Strategy

Preferred rollback:

- Keep the previous image tag available.
- Shift traffic back to the previous healthy task set or service revision.
- Do not roll back database schema blindly.

Migration rule:

- Use backward-compatible migrations for releases where old and new versions may run concurrently.
- Avoid destructive migrations in the same release as application code.
- For destructive changes, use expand/migrate/contract:
  1. Add compatible schema.
  2. Deploy code that writes/reads both as needed.
  3. Backfill or migrate.
  4. Remove old schema after verification.

If a bad release has already processed messages:

- Check `outbox_events`, `processed_messages`, `execution_reports`, and `trades`.
- Prefer compensating or replay-safe fixes over manual data edits.
- Preserve audit records for interview/demo clarity.

## Secrets Management

Local demos use `.env` or shell environment variables.

Production-shaped deployments should use a secret store:

- AWS Secrets Manager for RDS and broker credentials.
- IAM task role permissions scoped to only the required secrets.
- KMS encryption for secrets.
- No credentials in Docker images, Git, logs, or command history.

Rotation considerations:

- Verify whether rotated credentials require task restart.
- Coordinate RDS and broker credential rotation with application rollout.
- Keep rollback credentials valid during deployment windows.

## Observability

The service currently exposes:

- Structured logs with `correlationId`.
- Spring Boot Actuator health and metrics.
- Micrometer metrics for order submissions, rejections, execution reports, trades, message failures, processing duration, HTTP requests, and Hikari pool pressure.
- Database-visible diagnostics in `outbox_events` and `processed_messages`.

Minimum deployment dashboard:

- HTTP request rate, error rate, p95/p99 latency.
- JVM heap, GC pauses, CPU, memory.
- Hikari active connections, pending threads, acquisition time.
- RDS CPU, storage, connections, lock waits, slow queries.
- Broker queue depth, consumer count, redelivery/DLQ counts.
- Outbox pending, failed, and oldest pending age.
- Processed-message failures and duplicate count if exposed by future metrics.

## Performance Sizing Assumptions

Local/demo defaults are intentionally conservative:

- One backend task/container.
- JMS listener concurrency `1` to `4`.
- Outbox relay batch size `25`.
- PostgreSQL and Artemis running on local Docker.
- JVM heap sized by container percentage flags through `JAVA_OPTS`.

Starting cloud-demo assumption:

- ECS/Fargate task: 1 vCPU, 2 GB memory.
- RDS PostgreSQL: small burstable instance for demo only.
- Amazon MQ: single-instance broker for demo, multi-AZ broker for production-like HA.
- Hikari max pool should remain comfortably below RDS connection limits.
- Scale consumers only after checking database locks, Hikari pending threads, and broker throughput.

Do not treat local laptop k6 results as production capacity numbers.

## Known Production Gaps

- No real cloud infrastructure definitions.
- No authentication or authorization.
- No TLS termination configuration in this repo.
- No WAF, API Gateway, or rate limiting.
- No dashboards or alarms are checked in.
- No DLQ reconciliation worker.
- No retention or archival policy for operational tables.
- No formal migration rollback automation.
