# JVM and GC Performance Notes

## Scope

These notes are for local demo and interview discussion. They are not production tuning defaults. Real production JVM settings should be based on workload measurements, container limits, latency objectives, and operational constraints.

## JVM Memory Areas

### Heap

The Java heap stores most application objects: REST DTOs, JSON serialization objects, JPA entities, Hibernate state, JMS payload objects, and collections used during request processing.

Heap size matters because:

- Too small: frequent collections, allocation stalls, or `OutOfMemoryError`.
- Too large: longer worst-case collection work and slower memory pressure feedback.
- Reasonable local demo starting point: `-Xms256m -Xmx512m`.

### Young Generation

The young generation holds newly allocated objects. In this project, many objects are short-lived:

- HTTP request and response DTOs.
- Jackson parsing/serialization objects.
- JMS event payloads.
- Temporary strings and collections.
- JPA mapping objects around transaction boundaries.

High allocation rate usually shows up first as frequent young GCs. Short young GC pauses are normal. A problem appears when pause frequency or duration starts affecting p99 latency.

### Old Generation

Objects that survive enough collections are promoted into the old generation. Old-gen growth can indicate:

- Long-lived caches or static collections.
- Large persistence contexts held too long.
- Message backlogs represented in memory.
- Memory leaks from unbounded maps, listeners, or retained request data.

For this project, most business objects should be short-lived or persisted to PostgreSQL, not retained indefinitely in memory.

### Metaspace

Metaspace stores class metadata, generated proxy metadata, reflection metadata, and framework-loaded class structures. Spring Boot, Hibernate, Jackson, Mockito, and test infrastructure all use metaspace.

Metaspace issues are usually different from heap issues. If metaspace grows unexpectedly, look for classloader churn, repeated dynamic generation, or test/application context leaks.

## Allocation Rate

Allocation rate is how quickly the JVM creates new objects. It matters because GC work is driven partly by how much memory is allocated and retained.

In this service, common allocation sources are:

- JSON request parsing and response writing.
- FIX-style message parsing.
- JPA entity hydration and dirty checking.
- JMS event serialization/deserialization.
- Logging message construction.
- `BigDecimal` values for prices.

The first optimization step is measurement. Do not remove clear domain objects just to reduce allocations unless profiling shows they are meaningful to latency or throughput.

## GC Pauses

GC pauses are times when application threads are paused or slowed while memory is reclaimed. For a REST and JMS service, watch whether GC pauses line up with:

- HTTP p95/p99 latency spikes.
- JMS consumer processing delays.
- Hikari connection wait time.
- Broker redelivery or queue buildup.
- CPU saturation.

A few short pauses are normal. Repeated long pauses during load are a signal to inspect allocation rate, heap sizing, old-gen occupancy, and object retention.

## G1GC Basics

G1GC is the default collector in modern server JVMs and is appropriate for this project. It divides the heap into regions and tries to balance throughput with predictable pauses.

Useful G1 ideas:

- Young collections reclaim short-lived objects.
- Mixed collections reclaim selected old-gen regions.
- Humongous allocations can behave differently and should be watched if large payloads are introduced.
- `-XX:MaxGCPauseMillis` is a goal, not a guarantee.
- Increasing heap can reduce collection frequency but may not fix retained-object problems.

For this project, prefer default G1 behavior unless measurements show a specific issue.

## Local Demo JVM Flags

Safe local demo flags:

```bash
-Xms256m
-Xmx512m
-XX:+UseG1GC
-Xlog:gc*,safepoint:file=logs/gc/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=10m
```

What these do:

- `-Xms256m`: starts the heap at 256 MB.
- `-Xmx512m`: caps the heap at 512 MB.
- `-XX:+UseG1GC`: explicitly selects G1GC.
- `-Xlog:gc*,safepoint:...`: writes GC and safepoint logs to rotating local files.

Run the local helper:

```bash
./scripts/run-local-with-gc-logs.sh
```

The script builds the jar if needed, starts the app with the `local` Spring profile by default, and writes GC logs under `logs/gc`.

## Metrics to Watch

Application:

- `http.server.requests` p95/p99 latency.
- Order submission counters.
- Execution report and trade counters.
- Message processing failure counters.
- Message processing duration timer.

JVM:

- `jvm.memory.used`
- `jvm.memory.committed`
- `jvm.memory.max`
- `jvm.gc.pause`
- `jvm.gc.memory.allocated`
- `jvm.gc.memory.promoted`
- `jvm.threads.live`
- `process.cpu.usage`
- `system.cpu.usage`

Database and broker:

- Hikari active connections, idle connections, pending threads, and acquire time.
- PostgreSQL slow queries, locks, connection count, and CPU.
- Artemis queue depth, consumer count, enqueue/dequeue rate, redelivery count, and DLQ movement.

## Investigating p99 Latency

Start with correlation, not assumptions.

1. Confirm the symptom: which endpoint or message flow has high p99, and when did it begin?
2. Compare p50, p95, and p99. If only p99 is bad, suspect contention, pauses, queueing, slow queries, or downstream spikes.
3. Check application logs with correlation IDs for slow request paths.
4. Check JVM metrics: GC pause duration, allocation rate, heap pressure, CPU, and thread count.
5. Check database metrics: query latency, locks, connection pool wait time, and slow SQL.
6. Check broker metrics: queue depth, redeliveries, consumer lag, and DLQ activity.
7. Capture focused evidence: GC log snippet, thread dump during slowness, SQL plan, or broker queue snapshot.
8. Change one variable at a time and retest.

## JVM vs DB/Broker/Downstream Issues

Signals that point toward JVM or application runtime:

- GC pauses line up with latency spikes.
- Heap old-gen keeps growing after full collections.
- Allocation rate rises sharply with traffic.
- CPU is saturated in the app process.
- Thread dumps show blocked application threads or lock contention.

Signals that point toward PostgreSQL:

- Hikari pending threads increase.
- Connection acquisition time rises.
- Slow queries or lock waits appear.
- Database CPU or IO is saturated.
- App threads are waiting on JDBC calls.

Signals that point toward Artemis or messaging:

- Queue depth grows while consumers are running.
- Redelivery count increases.
- Consumer processing duration is stable but dequeue rate falls.
- Broker health or connection logs show instability.

Signals that point toward downstream dependencies:

- App threads wait on external calls.
- Timeouts cluster around one dependency.
- Correlation IDs show time spent outside application logic.

## Local Load Demo

The load script is intentionally small and educational. It is not a benchmark tool.

```bash
./scripts/run-load-demo.sh
```

Configurable environment variables:

- `APP_URL`, default `http://localhost:8080`
- `TOTAL_ORDERS`, default `25`
- `CONCURRENCY`, default `5`
- `SYMBOL`, default `AAPL`

Use it to create enough traffic to observe logs, counters, database writes, JMS flow, and GC log shape during a local demo.
