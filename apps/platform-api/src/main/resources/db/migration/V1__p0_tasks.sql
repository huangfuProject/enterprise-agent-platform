CREATE TABLE p0_task (
    task_id varchar(80) PRIMARY KEY,
    tenant_id varchar(80) NOT NULL DEFAULT 'p0-development',
    revision integer NOT NULL DEFAULT 1 CHECK (revision = 1),
    idempotency_key varchar(100) NOT NULL UNIQUE,
    payload_hash varchar(64) NOT NULL,
    input jsonb NOT NULL,
    limits jsonb NOT NULL,
    trace_id varchar(80) NOT NULL,
    state varchar(30) NOT NULL CHECK (state IN ('QUEUED','CLAIMED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','CANCELLED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE p0_run (
    execution_id varchar(80) PRIMARY KEY,
    task_id varchar(80) NOT NULL UNIQUE REFERENCES p0_task(task_id),
    checkpoint_thread_id varchar(160) NOT NULL UNIQUE,
    request_id varchar(80) NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'CREATED',
    lease_owner varchar(80),
    lease_expires_at timestamptz,
    heartbeat_at timestamptz,
    fence bigint NOT NULL DEFAULT 0,
    started_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    result jsonb,
    checkpoint_id varchar(100),
    error_code varchar(80),
    error_message varchar(300),
    runtime_event_cursor bigint NOT NULL DEFAULT 0
);
CREATE TABLE p0_event (
    task_id varchar(80) NOT NULL REFERENCES p0_task(task_id),
    sequence bigint NOT NULL,
    execution_id varchar(80) NOT NULL REFERENCES p0_run(execution_id),
    event_type varchar(80) NOT NULL,
    timestamp timestamptz NOT NULL DEFAULT clock_timestamp(),
    payload jsonb NOT NULL DEFAULT '{}',
    PRIMARY KEY(task_id, sequence)
);
CREATE INDEX p0_task_queue ON p0_task(state, created_at);
CREATE INDEX p0_run_leases ON p0_run(lease_expires_at);
