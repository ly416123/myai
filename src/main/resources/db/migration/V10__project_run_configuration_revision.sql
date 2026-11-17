ALTER TABLE agent_run ADD COLUMN config_release_id varchar(120);
ALTER TABLE agent_run ADD COLUMN config_hash char(64);

ALTER TABLE agent_run ADD CONSTRAINT agent_run_config_hash_check
    CHECK (config_hash IS NULL OR config_hash ~ '^[a-f0-9]{64}$');
ALTER TABLE agent_run ADD CONSTRAINT agent_run_config_pair_check
    CHECK ((config_release_id IS NULL) = (config_hash IS NULL));

CREATE INDEX agent_run_config_release_idx ON agent_run(config_release_id) WHERE config_release_id IS NOT NULL;
CREATE INDEX agent_run_config_hash_idx ON agent_run(config_hash) WHERE config_hash IS NOT NULL;
