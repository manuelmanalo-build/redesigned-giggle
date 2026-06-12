ALTER TABLE execution_reports
    DROP CONSTRAINT chk_execution_reports_type_valid,
    ADD CONSTRAINT chk_execution_reports_type_valid
        CHECK (execution_type IN ('ACCEPTED', 'REJECTED', 'PARTIAL_FILL', 'FILL', 'REPLACED', 'CANCELLED'));
