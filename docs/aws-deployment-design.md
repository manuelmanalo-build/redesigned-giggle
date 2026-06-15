# AWS Deployment Design

## Scope

This document describes a realistic AWS deployment design for the backend MVP. It is intentionally design-only. The repository does not provision AWS resources and does not include Terraform, CDK, CloudFormation, or paid cloud setup.

## Recommended Architecture

Recommended first AWS shape:

- Amazon ECS on Fargate for the Spring Boot container.
- Application Load Balancer for HTTPS REST traffic.
- Amazon RDS PostgreSQL for relational persistence.
- Amazon MQ for ActiveMQ-compatible JMS.
- Amazon ECR for container image storage.
- CloudWatch Logs and CloudWatch metrics for observability.
- AWS Secrets Manager for database and broker credentials.
- KMS for encryption at rest.
- IAM roles for least-privilege task and deployment permissions.

This keeps the cloud design close to the local architecture while demonstrating production-shaped deployment concerns.

## ECS Service Design

Use one ECS service for the backend container.

Suggested task definition:

- Image: `realtime-trade-processing-simulator:<git-sha>`.
- CPU/memory starting point: 1 vCPU / 2 GB memory for demo sizing.
- Port mapping: container port `8080`.
- Health check: `/actuator/health/readiness`.
- Log driver: `awslogs`.
- Environment variables for non-secret configuration.
- Secrets Manager references for credentials.
- Task execution role for ECR pull and CloudWatch logs.
- Task role for reading only required secrets and decrypting required KMS keys.

Sample non-secret environment:

```text
SPRING_PROFILES_ACTIVE=aws
SERVER_PORT=8080
TRADE_ORDER_SUBMITTED_QUEUE=order.submitted
TRADE_OUTBOX_RELAY_INTERVAL_MS=1000
TRADE_OUTBOX_BATCH_SIZE=25
TRADE_OUTBOX_MAX_ATTEMPTS=5
SPRING_JMS_LISTENER_CONCURRENCY=2
SPRING_JMS_LISTENER_MAX_CONCURRENCY=8
JAVA_OPTS=-XX:InitialRAMPercentage=50 -XX:MaxRAMPercentage=75 -XX:+UseG1GC
```

Sample secret-backed environment:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://<rds-endpoint>:5432/trade_simulator
SPRING_DATASOURCE_USERNAME=<from Secrets Manager>
SPRING_DATASOURCE_PASSWORD=<from Secrets Manager>
SPRING_ARTEMIS_BROKER_URL=tcp://<amazon-mq-endpoint>:61617
SPRING_ARTEMIS_USER=<from Secrets Manager>
SPRING_ARTEMIS_PASSWORD=<from Secrets Manager>
```

Use TLS-enabled broker endpoints in production-like deployments where available and validate client configuration before claiming encrypted broker transport.

## Load Balancer And Networking

Use an Application Load Balancer:

- HTTPS listener with ACM certificate.
- Target group health path: `/actuator/health/readiness`.
- Target type: IP for Fargate.
- Security group allows inbound HTTPS from approved clients.
- Backend task security group allows inbound only from ALB security group.

Private subnets:

- ECS tasks in private subnets.
- RDS in private subnets.
- Amazon MQ in private subnets.

Outbound access:

- NAT Gateway or VPC endpoints for ECR, CloudWatch Logs, Secrets Manager, and KMS depending on cost and security posture.

## RDS PostgreSQL Notes

Recommended demo/prod-like RDS setup:

- PostgreSQL 16-compatible engine version.
- Private subnet group.
- Storage encryption with KMS.
- Automated backups enabled.
- Deletion protection for production-like environments.
- Multi-AZ for production-like availability.
- Performance Insights or equivalent query visibility where allowed.
- Parameter group configured for logging slow queries in non-demo environments.

Application considerations:

- Flyway should run in a controlled deployment step or with explicit startup migration policy.
- Hikari maximum pool size should be sized below available RDS connections.
- Monitor `hikaricp.connections.pending`, acquisition time, RDS active connections, locks, and slow queries before increasing ECS task count.

## Amazon MQ Notes

Amazon MQ is the closest managed fit if the goal is JMS compatibility.

Recommended setup:

- ActiveMQ-compatible broker if using Spring JMS against JMS semantics.
- Private broker endpoint.
- Security group allows broker traffic only from ECS tasks.
- Credentials stored in Secrets Manager.
- CloudWatch broker metrics enabled.
- DLQ/redelivery policy documented and tested.

Metrics to watch:

- Queue depth for `order.submitted`.
- Enqueue/dequeue rates.
- Consumer count.
- Redelivery count.
- DLQ count.
- Broker CPU/memory/storage.
- Connection count.

Reliability note:

- The transactional outbox protects the database-to-broker publish boundary.
- Amazon MQ still provides at-least-once delivery, so the processed-message inbox and idempotent consumer remain required.
- A relay crash after publish but before marking `PUBLISHED` can still double-publish. The consumer must continue treating events as duplicates-safe.

## Secrets Management

Use AWS Secrets Manager for:

- RDS username/password.
- Amazon MQ username/password.
- Any future external API credentials.

Use KMS for:

- Secrets encryption.
- RDS storage.
- Amazon MQ storage.
- CloudWatch log groups if required by policy.

IAM guidance:

- ECS task role can read only the exact secrets needed by the service.
- ECS task execution role is separate and limited to image pull and log write operations.
- CI/CD role should use OIDC federation rather than long-lived AWS keys.

## Observability Design

CloudWatch Logs:

- One log group per environment.
- Include `correlationId`, `orderId`, `eventId`, endpoint/consumer name, status, and error category where available.
- Configure retention explicitly.

Metrics and alarms:

- ALB 5xx count and target response time.
- HTTP p95/p99 latency from Micrometer or ALB metrics.
- ECS CPU and memory.
- JVM heap and GC pause metrics.
- Hikari active connections, pending threads, and acquisition time.
- RDS CPU, connections, storage, IOPS, locks, and slow query count.
- Amazon MQ queue depth, oldest message age if available, redeliveries, and DLQ count.
- Outbox pending count, failed count, and oldest pending age.
- Processed-message failed count.

Tracing:

- Not implemented in the MVP.
- A production-grade version could add OpenTelemetry and propagate correlation IDs through REST and JMS.

## Deployment Strategy

### Rolling Deployment

Good default for the interview MVP:

- ECS replaces tasks gradually.
- ALB readiness health checks gate traffic.
- Failed new tasks stop the deployment.
- Previous image tag remains available for rollback.

Risk:

- A bad version can receive some traffic before rollback begins.

### Blue/Green Deployment

Better for production-like confidence:

- Deploy new task set beside old task set.
- Run smoke tests against the new target group.
- Shift traffic after health checks and smoke tests pass.
- Roll back by shifting traffic back to the old task set.

Risk:

- Requires extra deployment configuration and temporary capacity.
- Database migrations must be backward compatible while both task sets may run.

## Rollback Strategy

Application rollback:

- Redeploy the previous image tag.
- Keep configuration versioned and reversible.
- Check health and smoke-test after rollback.

Database rollback:

- Do not rely on automatic down migrations for production incidents.
- Prefer backward-compatible migrations and forward fixes.
- For schema changes, use expand/migrate/contract.

Messaging rollback:

- Preserve outbox and processed-message rows.
- Do not purge queues unless the operational impact is understood.
- Poison messages should go through DLQ/reconciliation workflow, which is a documented MVP gap.

## Autoscaling

API task scaling signals:

- CPU and memory.
- ALB request count per target.
- HTTP p95/p99 latency.
- Error rate.

Consumer scaling signals:

- `order.submitted` queue depth.
- Message age.
- Processing duration.
- Redelivery and failure counts.

Before scaling out:

- Check RDS connection pool pressure.
- Check database locks.
- Check broker throughput.
- Check GC pauses and CPU saturation.

Scaling consumers can make performance worse if the bottleneck is PostgreSQL locks or Hikari connection acquisition.

## Performance Sizing Assumptions

Initial demo environment:

- ECS/Fargate: 1 task, 1 vCPU, 2 GB memory.
- RDS: small PostgreSQL instance, not a benchmark target.
- Amazon MQ: small broker for demo, multi-AZ for production-like discussion.
- Hikari pool sized conservatively.
- JMS concurrency starts low and is increased only after observing database and broker behavior.

Expected bottlenecks to discuss:

- Database connection pool exhaustion.
- Row lock contention during order lifecycle updates.
- Broker queue depth growth if consumers lag.
- GC pauses under allocation-heavy request bursts.
- Slow search queries if indexes do not match filters.

## Known Production Gaps

- No IaC checked in.
- No AWS account, VPC, ECS cluster, ECR repository, RDS instance, or Amazon MQ broker is provisioned.
- No authentication or authorization.
- No TLS/client certificate setup for broker connectivity.
- No OpenTelemetry tracing.
- No checked-in CloudWatch dashboards or alarms.
- No DLQ listener or automated reconciliation workflow.
- No retention/archival policy for high-growth tables.
