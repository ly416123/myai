CREATE TABLE agent_config_release_approval (
    release_id varchar(120) PRIMARY KEY REFERENCES agent_config_release(release_id),
    actor varchar(120) NOT NULL,
    reason varchar(1000) NOT NULL,
    approved_at timestamptz NOT NULL
);

CREATE FUNCTION reject_agent_config_release_approval_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'agent_config_release_approval is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER agent_config_release_approval_no_update
    BEFORE UPDATE OR DELETE ON agent_config_release_approval
    FOR EACH ROW EXECUTE FUNCTION reject_agent_config_release_approval_mutation();
