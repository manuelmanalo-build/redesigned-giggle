CREATE INDEX idx_orders_account_id_created_at_desc ON orders(account_id, created_at DESC);
CREATE INDEX idx_orders_symbol_created_at_desc ON orders(symbol, created_at DESC);
CREATE INDEX idx_orders_status_created_at_desc ON orders(status, created_at DESC);
CREATE INDEX idx_orders_account_id_status_created_at_desc ON orders(account_id, status, created_at DESC);

CREATE INDEX idx_execution_reports_order_id_created_at_desc ON execution_reports(order_id, created_at DESC);
CREATE INDEX idx_execution_reports_execution_type_created_at_desc ON execution_reports(execution_type, created_at DESC);

CREATE INDEX idx_trades_account_id_created_at_desc ON trades(account_id, created_at DESC);
CREATE INDEX idx_trades_symbol_created_at_desc ON trades(symbol, created_at DESC);
CREATE INDEX idx_trades_order_id_created_at_desc ON trades(order_id, created_at DESC);
