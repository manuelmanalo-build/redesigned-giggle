# AWS Deployment Notes

## Purpose

This document describes how the Java service could be deployed to AWS. It is intentionally design-level only. The project does not yet include Terraform, CDK, Helm charts, ECS task definitions, or real AWS deployment automation.

## Recommended MVP Shape

A practical first AWS deployment would use:

- Amazon ECS on Fargate for the Spring Boot container.
- Amazon RDS for PostgreSQL.
- Amazon MQ for ActiveMQ if JMS compatibility should be preserved.
- Application Load Balancer for REST traffic.
- CloudWatch Logs and CloudWatch metrics for operational visibility.
- AWS Secrets Manager for database and broker credentials.
- IAM task roles for AWS API access.
- KMS-managed encryption for secrets, logs, database storage, and broker storage.

This keeps the system close to the current local architecture while avoiding Kubernetes and EC2 operations overhead.

## Compute Options

### ECS

ECS is the best default for this project.

Benefits:

- Runs the existing Docker image with limited platform complexity.
- Fargate removes server patching and capacity management.
- Integrates cleanly with ALB, CloudWatch, IAM roles, Secrets Manager, and autoscaling.
- Easier to explain and operate than Kubernetes for a single Spring Boot service.

Tradeoffs:

- Less portable than Kubernetes manifests.
- Advanced service mesh or platform patterns may require extra AWS-specific configuration.
- Long-running JMS consumers need careful shutdown, drain, and deployment settings.

### EKS

EKS is a better fit when the organization already standardizes on Kubernetes.

Benefits:

- Strong portability across Kubernetes environments.
- Rich ecosystem for ingress, autoscaling, policy, observability, and GitOps.
- Works well for larger platforms with many services and shared infrastructure teams.

Tradeoffs:

- More operational overhead than ECS.
- Requires Kubernetes knowledge for deployments, probes, resource requests, limits, secrets, network policies, and cluster upgrades.
- Overkill for an interview-prep MVP unless Kubernetes itself is the topic.

### EC2

EC2 is the lowest-level option.

Benefits:

- Maximum control over the host, JVM, OS tuning, and networking.
- Useful for specialized workloads or legacy deployment models.

Tradeoffs:

- You own patching, AMI updates, instance replacement, process supervision, log forwarding, scaling, and host security.
- More work for less value for this service.
- Harder to keep deployment repeatable unless paired with mature infrastructure automation.

## Database: RDS PostgreSQL

Amazon RDS PostgreSQL maps directly to the current PostgreSQL dependency.

Recommended setup:

- Use Multi-AZ for production-like high availability.
- Use private subnets and security groups so only the service can connect.
- Store credentials in Secrets Manager.
- Enable encryption at rest with KMS.
- Enable automated backups and a defined retention period.
- Use parameter groups for connection, logging, and performance settings.
- Monitor CPU, memory, connections, storage, read/write IOPS, locks, slow queries, and replication lag if using read replicas.

Application considerations:

- Keep Flyway migrations in CI/CD or controlled startup flow.
- Size the Hikari connection pool below the RDS connection limit.
- Use indexes that match query paths before scaling hardware.
- Treat database locks and connection pool exhaustion as likely causes of high p99 latency.

## Messaging Options

### Amazon MQ for JMS Compatibility

Amazon MQ for ActiveMQ is the closest managed equivalent to the local Artemis/JMS design.

Benefits:

- Preserves JMS-style programming model and broker semantics.
- Reduces code changes because Spring JMS abstractions can remain.
- Supports queues, acknowledgements, redelivery, and DLQ-style operational patterns.

Tradeoffs:

- More broker-specific operational tuning than SQS.
- Throughput and scaling characteristics differ from cloud-native queue services.
- The current outbox relay reduces publish-loss risk, but Amazon MQ still needs alarms for connection failures, queue depth, and redelivery/DLQ behavior.

Use this when the interview story emphasizes JMS compatibility or enterprise messaging migration.

### SQS/SNS Alternative

SQS can replace the JMS queue, and SNS can fan out events to multiple subscribers.

Benefits:

- Fully managed, highly available, simple operational model.
- Easy autoscaling signal through queue depth and message age.
- Native IAM integration.
- DLQs are straightforward.

Tradeoffs:

- Not JMS. The publisher and consumer abstraction would need a new implementation.
- Message acknowledgement, visibility timeout, ordering, and deduplication semantics differ from JMS.
- FIFO queues may be needed for strict ordering or deduplication, with throughput tradeoffs.
- Payload size and delivery semantics need explicit design.

Use this when AWS-native simplicity matters more than JMS compatibility.

### MSK/Kafka Alternative

Amazon MSK is a Kafka-compatible streaming option.

Benefits:

- Good fit for event streams, replay, multiple consumers, audit trails, and high-throughput pipelines.
- Retained topics allow downstream systems to reprocess events.
- Strong ecosystem for analytics and stream processing.

Tradeoffs:

- Kafka is not a simple work queue replacement.
- Requires partitioning strategy, consumer group design, offset management, retention settings, and schema/versioning discipline.
- Ordering is per partition, so order ID or account ID partitioning must be deliberate.
- More operational and conceptual overhead than SQS for this MVP.

Use this if the system evolves from command-style async processing into a broader event streaming platform.

## Network Entry

### Application Load Balancer

An ALB is the natural default for REST traffic to ECS or EKS.

- Routes HTTPS traffic to service tasks.
- Supports health checks against `/actuator/health`.
- Works well with ECS service discovery and target groups.
- Can terminate TLS using ACM certificates.

### API Gateway

API Gateway can sit in front of the service when API-management features matter.

- Useful for throttling, API keys, request validation, auth integration, and public API governance.
- Adds cost, latency, and another operational surface.
- Often unnecessary for a private or simple internal service behind an ALB.

## Observability

CloudWatch should collect:

- Application logs with correlation IDs.
- ECS task CPU and memory.
- JVM metrics exported through Micrometer.
- HTTP request rate, error rate, and latency.
- Hikari pool usage and wait time.
- RDS connections, locks, slow queries, CPU, storage, and IOPS.
- Broker queue depth, consumer count, enqueue/dequeue rate, redeliveries, and DLQ count.

The service should emit structured logs and include:

- `correlationId`
- `orderId`
- `eventId`
- endpoint or consumer name
- outcome status
- error category where applicable

## IAM Roles

Use least-privilege IAM roles:

- ECS task execution role: pull images and write logs.
- ECS task role: read required secrets, decrypt with KMS, and access AWS APIs needed by the application.
- Deployment role: update ECS services, register task definitions, and read deployment artifacts.
- CI role: build and push images, preferably through short-lived OIDC federation instead of static AWS keys.

Avoid embedding AWS credentials in application properties or container images.

## Secrets Manager

Store sensitive runtime configuration in Secrets Manager:

- RDS username/password.
- Broker username/password.
- External service credentials.
- Optional signing keys or API integration secrets.

Inject secrets into ECS tasks through task definitions or fetch them at startup through the AWS SDK. Rotate credentials where practical and make sure rotation behavior is tested before enabling it in production.

## KMS

Use KMS keys for encryption at rest:

- RDS storage.
- Amazon MQ storage.
- Secrets Manager secrets.
- CloudWatch log groups if required by policy.
- ECR image scanning and artifact encryption where applicable.

The service role should only have decrypt permission for the keys it actually needs.

## Deployment Strategies

### Rolling Deployments

Rolling deployment is the simplest default for ECS.

- Replace tasks gradually.
- Keep health checks strict enough to reject bad tasks.
- Configure graceful shutdown so JMS listeners stop taking new messages and in-flight work can finish or roll back.

Risk:

- A bad version may serve some traffic before alarms trigger.

### Blue/Green Deployments

Blue/green deployment is safer for higher confidence releases.

- Run old and new task sets side by side.
- Shift traffic after health checks and smoke tests pass.
- Roll back by shifting traffic back to the old task set.

Risk:

- Requires more deployment plumbing and extra temporary capacity.
- Database migrations must be backward compatible while both versions may run.

## Autoscaling Signals

Scale API tasks using:

- CPU utilization.
- Memory utilization.
- ALB request count per target.
- HTTP p95 or p99 latency if available through custom metrics.

Scale consumer tasks using:

- Queue depth.
- Oldest message age.
- Processing duration.
- Redelivery count.
- CPU and memory.

Database and broker bottlenecks should be checked before increasing task count. Adding consumers can make latency worse if RDS connections, locks, or broker throughput are already saturated.

## Failure Modes

Expected failure modes:

- RDS unavailable: REST writes fail, consumers cannot persist reports/trades, health checks should degrade.
- RDS connection pool exhausted: request latency rises and consumers block on JDBC.
- Broker unavailable during outbox relay: accepted orders remain in `outbox_events` with retry state until the broker recovers or max attempts are reached.
- Broker unavailable during consumption: messages wait in the queue until consumers recover.
- Consumer failure after message receipt: transacted JMS session should roll back acknowledgement and allow redelivery.
- Duplicate message delivery: deterministic processing and database constraints should prevent duplicate terminal effects.
- Poison message: broker redelivery may repeat until DLQ policy handles it.
- Bad deployment: health checks, alarms, and rollback strategy should limit impact.
- Secrets rotation mismatch: tasks may fail to connect until restarted or refreshed.
- RDS failover: short outage or connection reset; the app should reconnect and retry where safe.
- Hot account or order stream: row locks or partitioning choices can limit throughput.

## Production Hardening Gaps

The current MVP would need these before real production deployment:

- Explicit processed-message inbox table for queryable consumer idempotency and retry diagnostics.
- Backward-compatible migration policy.
- Load testing against realistic queue depth and database size.
- Security review for authentication, authorization, network boundaries, and secret handling.
- Alarms and dashboards for API, database, broker, and JVM health.
