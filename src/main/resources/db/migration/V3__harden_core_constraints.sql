ALTER TABLE orders
    ADD CONSTRAINT chk_orders_side_valid
        CHECK (side IN ('BUY', 'SELL')),
    ADD CONSTRAINT chk_orders_type_valid
        CHECK (type IN ('MARKET', 'LIMIT')),
    ADD CONSTRAINT chk_orders_status_valid
        CHECK (status IN ('NEW', 'ACCEPTED', 'REJECTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED')),
    ADD CONSTRAINT chk_orders_type_price_consistent
        CHECK (
            (type = 'MARKET' AND limit_price IS NULL)
            OR (type = 'LIMIT' AND limit_price IS NOT NULL)
        );

ALTER TABLE execution_reports
    ADD CONSTRAINT chk_execution_reports_type_valid
        CHECK (execution_type IN ('ACCEPTED', 'REJECTED', 'PARTIAL_FILL', 'FILL', 'CANCELLED')),
    ADD CONSTRAINT chk_execution_reports_order_status_valid
        CHECK (order_status IN ('NEW', 'ACCEPTED', 'REJECTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED')),
    ADD CONSTRAINT chk_execution_reports_fill_fields_consistent
        CHECK (
            (
                execution_type IN ('PARTIAL_FILL', 'FILL')
                AND executed_quantity IS NOT NULL
                AND execution_price IS NOT NULL
            )
            OR (
                execution_type NOT IN ('PARTIAL_FILL', 'FILL')
                AND executed_quantity IS NULL
                AND execution_price IS NULL
            )
        );

ALTER TABLE trades
    ADD CONSTRAINT chk_trades_side_valid
        CHECK (side IN ('BUY', 'SELL'));

ALTER TABLE idempotency_records
    ADD CONSTRAINT chk_idempotency_records_response_status_valid
        CHECK (response_status BETWEEN 100 AND 599);
