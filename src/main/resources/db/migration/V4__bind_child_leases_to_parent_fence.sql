ALTER TABLE child_task
    ADD COLUMN parent_fencing_token bigint NOT NULL DEFAULT 0;
