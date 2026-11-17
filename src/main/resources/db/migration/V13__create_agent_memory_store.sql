CREATE TABLE agent_memory (
    memory_id uuid PRIMARY KEY,
    namespace varchar(120) NOT NULL,
    memory_key varchar(64),
    kind varchar(20) NOT NULL CHECK (kind IN ('PREFERENCE', 'FACT')),
    content text,
    version bigint NOT NULL CHECK (version > 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'DELETED')),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((status = 'ACTIVE' AND memory_key IS NOT NULL AND content IS NOT NULL)
        OR (status = 'DELETED' AND memory_key IS NULL AND content IS NULL))
);

CREATE UNIQUE INDEX agent_memory_active_key_uq
    ON agent_memory(namespace, memory_key) WHERE status = 'ACTIVE';
CREATE INDEX agent_memory_namespace_status_idx
    ON agent_memory(namespace, status);

CREATE TABLE agent_memory_event (
    event_id bigserial PRIMARY KEY,
    memory_id uuid NOT NULL,
    operation varchar(16) NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE')),
    version bigint NOT NULL CHECK (version > 0),
    occurred_at timestamptz NOT NULL,
    source varchar(32) NOT NULL CHECK (source = 'EXPLICIT_CLI')
);
CREATE INDEX agent_memory_event_memory_id_idx ON agent_memory_event(memory_id, event_id);

CREATE FUNCTION reject_agent_memory_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'agent_memory_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER agent_memory_event_no_update
    BEFORE UPDATE OR DELETE ON agent_memory_event
    FOR EACH ROW EXECUTE FUNCTION reject_agent_memory_event_mutation();
