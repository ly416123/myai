CREATE TABLE agent_config_revision (
    revision_id varchar(80) PRIMARY KEY,
    content_hash char(64) NOT NULL UNIQUE,
    format_version integer NOT NULL CHECK (format_version > 0),
    payload jsonb NOT NULL,
    source_commit varchar(80),
    created_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX agent_config_revision_created_idx ON agent_config_revision(created_at DESC, revision_id);

CREATE TABLE agent_config_release (
    release_id varchar(120) PRIMARY KEY,
    revision_id varchar(80) NOT NULL REFERENCES agent_config_revision(revision_id),
    content_hash char(64) NOT NULL,
    evaluation_id varchar(80) REFERENCES evaluation_run(evaluation_id),
    baseline_release_id varchar(120) REFERENCES agent_config_release(release_id),
    gate_decision jsonb NOT NULL,
    source_commit varchar(80),
    release_notes varchar(2000) NOT NULL,
    created_by varchar(120) NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK (baseline_release_id IS NULL OR baseline_release_id <> release_id)
);
CREATE INDEX agent_config_release_created_idx ON agent_config_release(created_at DESC, release_id);
CREATE INDEX agent_config_release_revision_idx ON agent_config_release(revision_id, created_at DESC);

CREATE TABLE active_config_assignment (
    environment varchar(80) NOT NULL,
    agent_id varchar(80) NOT NULL,
    active_release_id varchar(120) NOT NULL REFERENCES agent_config_release(release_id),
    candidate_release_id varchar(120) REFERENCES agent_config_release(release_id),
    candidate_percent integer NOT NULL DEFAULT 0 CHECK (candidate_percent BETWEEN 0 AND 99),
    assignment_version bigint NOT NULL CHECK (assignment_version > 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (environment, agent_id),
    CHECK ((candidate_release_id IS NULL AND candidate_percent = 0)
        OR (candidate_release_id IS NOT NULL AND candidate_percent BETWEEN 1 AND 99)),
    CHECK (candidate_release_id IS NULL OR candidate_release_id <> active_release_id)
);

CREATE TABLE agent_config_release_event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    environment varchar(80) NOT NULL,
    agent_id varchar(80) NOT NULL,
    action varchar(24) NOT NULL CHECK (action IN ('ACTIVATE','ROLLOUT','ROLLBACK','AUTO_ROLLBACK')),
    release_id varchar(120) NOT NULL REFERENCES agent_config_release(release_id),
    actor varchar(120) NOT NULL,
    reason varchar(1000) NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX agent_config_release_event_scope_idx
    ON agent_config_release_event(environment, agent_id, event_id DESC);

CREATE FUNCTION reject_agent_config_release_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'agent_config_release_event is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER agent_config_release_event_no_update
    BEFORE UPDATE OR DELETE ON agent_config_release_event
    FOR EACH ROW EXECUTE FUNCTION reject_agent_config_release_event_mutation();
