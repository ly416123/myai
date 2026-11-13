CREATE TABLE agent_run (
    run_id              varchar(80) PRIMARY KEY,
    run_type            varchar(40) NOT NULL,
    status              varchar(32) NOT NULL,
    user_input          text NOT NULL,
    final_answer        text,
    failure_category    varchar(64),
    deadline_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    model_calls         integer NOT NULL DEFAULT 0,
    tool_calls          integer NOT NULL DEFAULT 0,
    resumed             boolean NOT NULL DEFAULT false,
    prompt_tokens       bigint,
    completion_tokens   bigint,
    token_usage_known   boolean NOT NULL DEFAULT true,
    cancel_requested    boolean NOT NULL DEFAULT false,
    version             bigint NOT NULL DEFAULT 0,
    fencing_token       bigint NOT NULL DEFAULT 0,
    lease_owner         varchar(120),
    lease_expires_at    timestamptz,
    CONSTRAINT agent_run_status_check CHECK (status IN ('CREATED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    CONSTRAINT agent_run_lease_pair_check CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL))
);

CREATE INDEX agent_run_status_deadline_idx ON agent_run (status, deadline_at);
CREATE INDEX agent_run_lease_idx ON agent_run (lease_expires_at) WHERE status IN ('CREATED', 'RUNNING');

CREATE TABLE run_checkpoint (
    run_id      varchar(80) PRIMARY KEY REFERENCES agent_run(run_id) ON DELETE CASCADE,
    version     bigint NOT NULL,
    payload     jsonb NOT NULL,
    saved_at    timestamptz NOT NULL
);

CREATE TABLE run_message (
    run_id      varchar(80) NOT NULL REFERENCES agent_run(run_id) ON DELETE CASCADE,
    ordinal     integer NOT NULL,
    role        varchar(24) NOT NULL,
    payload     jsonb NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);

CREATE TABLE run_step (
    run_id              varchar(80) NOT NULL REFERENCES agent_run(run_id) ON DELETE CASCADE,
    step_id             varchar(80) NOT NULL,
    kind                varchar(32) NOT NULL,
    status              varchar(32) NOT NULL,
    started_at          timestamptz NOT NULL,
    finished_at         timestamptz,
    failure_category    varchar(64),
    payload             jsonb NOT NULL,
    PRIMARY KEY (run_id, step_id)
);
CREATE INDEX run_step_status_idx ON run_step (run_id, status);

CREATE TABLE child_task (
    run_id      varchar(80) NOT NULL REFERENCES agent_run(run_id) ON DELETE CASCADE,
    task_id     varchar(80) NOT NULL,
    worker_id   varchar(80) NOT NULL,
    status      varchar(32) NOT NULL,
    payload     jsonb NOT NULL,
    PRIMARY KEY (run_id, task_id)
);
