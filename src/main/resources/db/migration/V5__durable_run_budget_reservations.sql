CREATE TABLE run_budget_reservation (
    run_id           varchar(80) NOT NULL REFERENCES agent_run(run_id) ON DELETE CASCADE,
    reservation_id   varchar(80) NOT NULL,
    reservation_kind varchar(32) NOT NULL,
    amount           integer NOT NULL CHECK (amount > 0),
    reserved_at      timestamptz NOT NULL,
    PRIMARY KEY (run_id, reservation_id),
    CONSTRAINT run_budget_reservation_kind_check CHECK (reservation_kind IN ('MODEL_CALL', 'TOOL_CALL'))
);
