ALTER TABLE trades
    ADD COLUMN execution_report_id VARCHAR(64);

UPDATE trades trade
SET execution_report_id = report.id
FROM execution_reports report
WHERE report.order_id = trade.order_id
  AND report.execution_type IN ('PARTIAL_FILL', 'FILL')
  AND trade.execution_report_id IS NULL;

ALTER TABLE trades
    ALTER COLUMN execution_report_id SET NOT NULL,
    ADD CONSTRAINT fk_trades_execution_report_id
        FOREIGN KEY (execution_report_id) REFERENCES execution_reports(id),
    ADD CONSTRAINT uq_trades_execution_report_id
        UNIQUE (execution_report_id);

CREATE INDEX idx_trades_execution_report_id ON trades(execution_report_id);
