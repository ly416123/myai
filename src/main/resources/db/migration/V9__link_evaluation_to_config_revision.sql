ALTER TABLE evaluation_run
    ADD COLUMN configuration_hash char(64),
    ADD CONSTRAINT evaluation_run_configuration_hash_check
        CHECK (configuration_hash IS NULL OR configuration_hash ~ '^[a-f0-9]{64}$');

CREATE INDEX evaluation_run_configuration_hash_idx
    ON evaluation_run(configuration_hash, created_at DESC)
    WHERE configuration_hash IS NOT NULL;
