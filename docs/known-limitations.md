# Known Limitations

This document records the intentional limits of the `v1.0.0-backend-mvp` checkpoint. These are documented gaps, not hidden work items.

## Trading Lifecycle

- Partial fills are modeled in the domain and persistence layer, but the current execution simulator produces either full fills or no fills.
- Replace updates the current order row in place. It does not preserve full order version history.
- Accepted replace reuses the existing `OrderSubmittedEvent` path for re-evaluation. A production system would likely use a dedicated amendment event such as `OrderReplacedEvent`.
- The simulator does not model external venue acknowledgement, venue rejects, matching-engine behavior, market data feeds, order books, or real execution prices.

## Messaging And Reliability

- The outbox/inbox design is at-least-once, not exactly-once. Duplicate publication can still occur if the relay publishes to JMS and crashes before marking the outbox row `PUBLISHED`.
- Consumers remain idempotent through `processed_messages`, deterministic execution-report IDs, row locking, and database constraints.
- Broker-level DLQ routing is documented, but there is no DLQ listener or reconciliation job that automatically marks inbox rows `DEAD_LETTERED`.
- Event payloads are not versioned yet.

## API And Security

- There is no authentication or authorization.
- Reference-data APIs are simple local/demo management endpoints and are not split into admin-only permissions.
- Error responses are production-shaped, but there is no external API gateway, rate limiting, or tenant isolation.
- Search uses offset pagination. Keyset pagination would be better for deep high-volume operational screens.

## Persistence And Data Model

- Orders reference account IDs and symbols as business identifiers, but the schema does not enforce foreign keys from orders to `accounts` or `instruments`.
- Reference data is intentionally small and seeded for local demos.
- There is no archival, partitioning, or retention strategy for orders, execution reports, trades, outbox events, processed messages, or idempotency records.
- Database migrations are validated through local tests, but there is no production migration rollback playbook.

## Operations And Performance

- The load-testing setup is a local diagnostic aid, not a production capacity benchmark.
- JVM/GC scripts are local helpers and not tuned production runtime settings.
- Observability uses logs and Micrometer/Actuator metrics, but there are no prebuilt dashboards or alerts.
- AWS deployment is documented conceptually, including ECS/RDS/Amazon MQ design notes, but no Terraform, CloudFormation, CDK, or deployment pipeline is implemented.
- The Docker/Compose packaging supports a local deployment-shaped demo; it is not a substitute for a hardened production platform with TLS, identity, network policy, autoscaling policies, dashboards, and alarms.

## FIX Support

- The FIX module is educational only. It parses simplified `tag=value` messages and maps a New Order Single-like message into the internal order request.
- It does not implement FIX sessions, sequence numbers, resend logic, heartbeats, dictionaries, `BodyLength`, `CheckSum`, encryption, certification behavior, or venue connectivity.
