CREATE TABLE orders (
    id VARCHAR(64) PRIMARY KEY,
    client_order_id VARCHAR(128),
    account_id VARCHAR(128) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(16) NOT NULL,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    quantity BIGINT NOT NULL,
    limit_price NUMERIC(19, 4),
    filled_quantity BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_orders_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_orders_filled_quantity_non_negative CHECK (filled_quantity >= 0),
    CONSTRAINT chk_orders_filled_quantity_not_over_order CHECK (filled_quantity <= quantity),
    CONSTRAINT chk_orders_limit_price_positive CHECK (limit_price IS NULL OR limit_price > 0)
);

CREATE TABLE execution_reports (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id),
    execution_type VARCHAR(32) NOT NULL,
    order_status VARCHAR(32) NOT NULL,
    executed_quantity BIGINT,
    execution_price NUMERIC(19, 4),
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_execution_reports_quantity_positive CHECK (executed_quantity IS NULL OR executed_quantity > 0),
    CONSTRAINT chk_execution_reports_price_positive CHECK (execution_price IS NULL OR execution_price > 0)
);

CREATE TABLE trades (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id),
    account_id VARCHAR(128) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(16) NOT NULL,
    quantity BIGINT NOT NULL,
    price NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_trades_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_trades_price_positive CHECK (price > 0)
);

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash VARCHAR(128) NOT NULL,
    order_id VARCHAR(64) REFERENCES orders(id),
    response_status INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_orders_client_order_id ON orders(client_order_id);
CREATE INDEX idx_orders_account_id ON orders(account_id);
CREATE INDEX idx_orders_symbol ON orders(symbol);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_execution_reports_order_id ON execution_reports(order_id);
CREATE INDEX idx_trades_order_id ON trades(order_id);
CREATE INDEX idx_idempotency_records_idempotency_key ON idempotency_records(idempotency_key);

