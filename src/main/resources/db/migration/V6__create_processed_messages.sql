CREATE TABLE processed_messages (
    message_id VARCHAR(128) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    aggregate_id VARCHAR(128),
    consumer_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    correlation_id VARCHAR(128),
    CONSTRAINT chk_processed_messages_status_valid CHECK (
        status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'DUPLICATE', 'DEAD_LETTERED')
    ),
    CONSTRAINT chk_processed_messages_attempt_count_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_processed_messages_status ON processed_messages(status);
CREATE INDEX idx_processed_messages_aggregate ON processed_messages(aggregate_id);
CREATE INDEX idx_processed_messages_consumer_status ON processed_messages(consumer_name, status);
