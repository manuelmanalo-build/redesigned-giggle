CREATE TABLE accounts (
    id VARCHAR(128) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_accounts_status_valid CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE instruments (
    symbol VARCHAR(32) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    asset_class VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    tick_size NUMERIC(19, 8),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_instruments_asset_class_valid CHECK (asset_class IN ('EQUITY', 'ETF', 'OPTION', 'FUTURE', 'CRYPTO')),
    CONSTRAINT chk_instruments_status_valid CHECK (status IN ('ACTIVE', 'HALTED', 'DELISTED')),
    CONSTRAINT chk_instruments_tick_size_positive CHECK (tick_size IS NULL OR tick_size > 0)
);

CREATE INDEX idx_accounts_status ON accounts(status);
CREATE INDEX idx_instruments_status ON instruments(status);
CREATE INDEX idx_instruments_asset_class ON instruments(asset_class);

INSERT INTO accounts (id, display_name, status, created_at, updated_at)
VALUES
    ('ACC-001', 'Demo Active Account', 'ACTIVE', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z'),
    ('ACC-002', 'Demo Suspended Account', 'SUSPENDED', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z'),
    ('ACC-003', 'Demo Closed Account', 'CLOSED', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z');

INSERT INTO instruments (symbol, name, asset_class, status, tick_size, created_at, updated_at)
VALUES
    ('AAPL', 'Apple Inc.', 'EQUITY', 'ACTIVE', 0.01, '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z'),
    ('MSFT', 'Microsoft Corporation', 'EQUITY', 'ACTIVE', 0.01, '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z'),
    ('TSLA', 'Tesla Inc.', 'EQUITY', 'ACTIVE', 0.01, '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z'),
    ('HALT1', 'Halted Demo Equity', 'EQUITY', 'HALTED', 0.01, '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z'),
    ('OLD1', 'Delisted Demo Equity', 'EQUITY', 'DELISTED', 0.01, '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z');
