ALTER TABLE child_task
    ADD COLUMN task_lease_owner varchar(120),
    ADD COLUMN task_lease_expires_at timestamptz,
    ADD COLUMN task_fencing_token bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT child_task_lease_pair_check
        CHECK ((task_lease_owner IS NULL) = (task_lease_expires_at IS NULL));

ALTER TABLE child_task
    ADD CONSTRAINT child_task_status_check
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'INDETERMINATE'));

CREATE INDEX child_task_claim_idx ON child_task (run_id, status, task_lease_expires_at);
