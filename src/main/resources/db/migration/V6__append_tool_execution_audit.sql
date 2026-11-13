CREATE TABLE tool_execution_audit (
    audit_id          bigserial PRIMARY KEY,
    run_id            varchar(80) NOT NULL REFERENCES agent_run(run_id) ON DELETE CASCADE,
    step_id           varchar(80) NOT NULL,
    tool_call_id      varchar(200),
    task_id           varchar(80),
    worker_id         varchar(80),
    tool_name         varchar(120) NOT NULL,
    status            varchar(32) NOT NULL,
    failure_category  varchar(64),
    occurred_at       timestamptz NOT NULL,
    CONSTRAINT tool_execution_audit_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'INDETERMINATE'))
);

CREATE INDEX tool_execution_audit_run_idx
    ON tool_execution_audit (run_id, occurred_at, audit_id);
