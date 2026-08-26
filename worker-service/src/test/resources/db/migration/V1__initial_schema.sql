CREATE TYPE job_state AS ENUM ('PENDING', 'RUNNING', 'SUCCEEDED', 'DEAD_LETTER', 'CANCELLED');

CREATE TABLE jobs (
    id                BIGSERIAL PRIMARY KEY,
    idempotency_key   TEXT        NOT NULL UNIQUE,
    job_type          TEXT        NOT NULL,
    payload           JSONB       NOT NULL,
    state             job_state   NOT NULL DEFAULT 'PENDING',
    priority          INT         NOT NULL DEFAULT 0,
    attempt_count     INT         NOT NULL DEFAULT 0,
    max_attempts      INT         NOT NULL DEFAULT 5,
    next_run_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_by        TEXT,
    lease_expires_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_claimable
    ON jobs (priority DESC, next_run_at ASC)
    WHERE state = 'PENDING';

CREATE INDEX idx_jobs_lease ON jobs (lease_expires_at) WHERE state = 'RUNNING';

CREATE TABLE job_executions (
    id            BIGSERIAL PRIMARY KEY,
    job_id        BIGINT      NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt       INT         NOT NULL,
    worker_id     TEXT        NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    success       BOOLEAN,
    error_message TEXT,
    UNIQUE (job_id, attempt)
);

CREATE TABLE job_dependencies (
    job_id        BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    depends_on_id BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    PRIMARY KEY (job_id, depends_on_id)
);

CREATE OR REPLACE FUNCTION enforce_job_state_transition() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.state IS DISTINCT FROM OLD.state THEN
        IF NOT (
            (OLD.state = 'PENDING'     AND NEW.state = 'RUNNING') OR
            (OLD.state = 'PENDING'     AND NEW.state = 'CANCELLED') OR
            (OLD.state = 'RUNNING'     AND NEW.state = 'SUCCEEDED') OR
            (OLD.state = 'RUNNING'     AND NEW.state = 'PENDING') OR
            (OLD.state = 'RUNNING'     AND NEW.state = 'DEAD_LETTER') OR
            (OLD.state = 'DEAD_LETTER' AND NEW.state = 'PENDING')
        ) THEN
            RAISE EXCEPTION 'illegal job state transition: % -> %', OLD.state, NEW.state;
        END IF;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_jobs_state_transition
    BEFORE UPDATE ON jobs
    FOR EACH ROW
    EXECUTE FUNCTION enforce_job_state_transition();
