CREATE TABLE run_retention_audit (
    audit_id       bigserial PRIMARY KEY,
    executed_at    timestamptz NOT NULL DEFAULT now(),
    cutoff_at      timestamptz NOT NULL,
    deleted_runs   integer NOT NULL CHECK (deleted_runs >= 0)
);

CREATE INDEX agent_run_terminal_updated_idx ON agent_run (updated_at, run_id)
    WHERE status IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED');
