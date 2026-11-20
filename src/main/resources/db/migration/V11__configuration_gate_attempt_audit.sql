CREATE TABLE agent_config_gate_attempt (
    attempt_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    revision_id varchar(80) NOT NULL REFERENCES agent_config_revision(revision_id),
    evaluation_id varchar(80) NOT NULL,
    baseline_release_id varchar(120) REFERENCES agent_config_release(release_id),
    decision jsonb NOT NULL,
    actor varchar(120) NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX agent_config_gate_attempt_revision_idx
    ON agent_config_gate_attempt(revision_id, attempt_id DESC);

CREATE FUNCTION reject_agent_config_gate_attempt_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'agent_config_gate_attempt is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER agent_config_gate_attempt_no_update
    BEFORE UPDATE OR DELETE ON agent_config_gate_attempt
    FOR EACH ROW EXECUTE FUNCTION reject_agent_config_gate_attempt_mutation();
