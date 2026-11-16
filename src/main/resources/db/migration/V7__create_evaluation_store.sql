CREATE TABLE evaluation_run (
    evaluation_id varchar(80) PRIMARY KEY,
    status varchar(16) NOT NULL CHECK (status IN ('CREATED','RUNNING','SUCCEEDED','FAILED','CANCELLED','PARTIAL')),
    dataset_id varchar(120) NOT NULL,
    dataset_version varchar(64) NOT NULL,
    dataset_hash char(64) NOT NULL,
    dataset_format_version integer NOT NULL CHECK (dataset_format_version > 0),
    git_commit varchar(80), workflow_mode varchar(32) NOT NULL, provider varchar(80) NOT NULL, model varchar(120) NOT NULL,
    evaluator_versions jsonb NOT NULL,
    model_call_budget integer NOT NULL CHECK (model_call_budget > 0),
    tool_call_budget integer NOT NULL CHECK (tool_call_budget >= 0),
    max_cases integer NOT NULL CHECK (max_cases > 0),
    created_at timestamptz NOT NULL, started_at timestamptz, deadline_at timestamptz NOT NULL, completed_at timestamptz,
    execution_owner varchar(80), execution_fence bigint NOT NULL DEFAULT 0, lease_expires_at timestamptz,
    total_cases integer NOT NULL, completed_cases integer NOT NULL, passed_cases integer NOT NULL,
    failed_cases integer NOT NULL, model_calls integer NOT NULL, tool_calls integer NOT NULL,
    prompt_tokens bigint, completion_tokens bigint, estimated_cost_usd numeric(20,8), failure_category varchar(64)
);
CREATE INDEX evaluation_run_created_idx ON evaluation_run(created_at DESC, evaluation_id);

CREATE TABLE evaluation_case (
    evaluation_id varchar(80) NOT NULL REFERENCES evaluation_run(evaluation_id) ON DELETE CASCADE,
    case_id varchar(80) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED','INDETERMINATE')),
    run_id varchar(80) REFERENCES agent_run(run_id) ON DELETE SET NULL,
    failure_category varchar(64), scores jsonb NOT NULL DEFAULT '[]'::jsonb,
    model_calls integer NOT NULL DEFAULT 0, tool_calls integer NOT NULL DEFAULT 0,
    token_usage_known boolean NOT NULL DEFAULT false, prompt_tokens bigint, completion_tokens bigint,
    duration_millis bigint, started_at timestamptz, completed_at timestamptz,
    attempt integer NOT NULL DEFAULT 0,
    PRIMARY KEY (evaluation_id, case_id)
);
CREATE INDEX evaluation_case_status_idx ON evaluation_case(evaluation_id, status, case_id);

CREATE TABLE evaluation_status_event (
    event_id bigserial PRIMARY KEY,
    evaluation_id varchar(80) NOT NULL REFERENCES evaluation_run(evaluation_id) ON DELETE CASCADE,
    status varchar(16) NOT NULL CHECK (status IN ('CREATED','RUNNING','SUCCEEDED','FAILED','CANCELLED','PARTIAL')),
    occurred_at timestamptz NOT NULL,
    failure_category varchar(64)
);
CREATE INDEX evaluation_status_event_run_idx ON evaluation_status_event(evaluation_id, event_id);
