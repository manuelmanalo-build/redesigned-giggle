-- PostgreSQL lock and connection diagnostics for the local trade simulator.
-- Run from psql:
-- psql -h localhost -U trade_user -d trade_simulator -f scripts/sql/db-lock-diagnostics.sql

\echo 'Connection count by state'
SELECT state, count(*) AS connection_count
FROM pg_stat_activity
WHERE datname = current_database()
GROUP BY state
ORDER BY connection_count DESC;

\echo 'Currently active or waiting sessions'
SELECT
    now() AS sampled_at,
    pid,
    usename,
    application_name,
    state,
    wait_event_type,
    wait_event,
    now() - xact_start AS transaction_age,
    now() - query_start AS query_age,
    left(query, 500) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
  AND (state <> 'idle' OR wait_event_type IS NOT NULL)
ORDER BY query_start NULLS LAST;

\echo 'Blocking relationships'
SELECT
    blocked.pid AS blocked_pid,
    blocked.usename AS blocked_user,
    blocked.wait_event_type,
    blocked.wait_event,
    now() - blocked.query_start AS blocked_for,
    blocker.pid AS blocker_pid,
    blocker.usename AS blocker_user,
    now() - blocker.query_start AS blocker_query_age,
    left(blocked.query, 500) AS blocked_query,
    left(blocker.query, 500) AS blocker_query
FROM pg_stat_activity blocked
JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS blocker_pid ON true
JOIN pg_stat_activity blocker ON blocker.pid = blocker_pid
WHERE blocked.datname = current_database()
ORDER BY blocked_for DESC;

\echo 'Locks by relation and mode'
SELECT
    coalesce(lock_data.relation_name, lock_data.locktype) AS locked_object,
    lock_data.mode,
    lock_data.granted,
    count(*) AS lock_count
FROM (
    SELECT
        locktype,
        mode,
        granted,
        CASE
            WHEN relation IS NULL THEN NULL
            ELSE relation::regclass::text
        END AS relation_name
    FROM pg_locks
    WHERE database = (SELECT oid FROM pg_database WHERE datname = current_database())
       OR database IS NULL
) lock_data
GROUP BY coalesce(lock_data.relation_name, lock_data.locktype), lock_data.mode, lock_data.granted
ORDER BY lock_count DESC, locked_object, mode;

\echo 'Long-running transactions'
SELECT
    pid,
    usename,
    application_name,
    state,
    now() - xact_start AS transaction_age,
    now() - query_start AS query_age,
    wait_event_type,
    wait_event,
    left(query, 500) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND xact_start IS NOT NULL
ORDER BY transaction_age DESC
LIMIT 20;

\echo 'Application lag tables'
SELECT status, count(*) AS outbox_count
FROM outbox_events
GROUP BY status
ORDER BY status;

SELECT status, count(*) AS processed_message_count
FROM processed_messages
GROUP BY status
ORDER BY status;
