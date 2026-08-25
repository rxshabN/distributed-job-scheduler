-- TEST-ONLY COPY. scheduler-service/src/main/resources/db/migration/V1__initial_schema.sql is the
-- real, owned copy -- scheduler-service is the only service that runs migrations against the
-- actual deployed database (application.yml sets spring.flyway.enabled: false for worker-service
-- everywhere except its own tests, see worker-service's application.yml). worker-service's
-- Testcontainers tests need *some* schema to run their native SQL against, though, so this copy
-- exists purely so worker-service's own test suite has a Postgres to run against without
-- depending on scheduler-service's module. If you change the real migration, mirror the change
-- here too -- there is currently no automated check that these two files stay in sync.
--
-- Schema for jobs, job_executions, and (optional, Weekend 5) job_dependencies.
-- See PROJECT_SPEC.md §2 for the state machine and §3 for the annotated data model.

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

-- Partial index: only PENDING rows are ever scanned by the claim query (spec §4).
CREATE INDEX idx_jobs_claimable
    ON jobs (priority DESC, next_run_at ASC)
    WHERE state = 'PENDING';

-- Partial index for the reaper's sweep over RUNNING jobs (spec §5).
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

-- Optional, Weekend 5: job dependency edges for DAG execution.
CREATE TABLE job_dependencies (
    job_id        BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    depends_on_id BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    PRIMARY KEY (job_id, depends_on_id)
);

-- BEFORE UPDATE trigger enforcing the state machine (spec §2) and refreshing updated_at on
-- every UPDATE -- the DEFAULT now() above only fires on INSERT. Only enumerated OLD.state ->
-- NEW.state pairs are ever legal; same-state updates (e.g. extending lease_expires_at on a
-- still-RUNNING job for a long-running handler, spec §5) always pass through since the state
-- itself hasn't changed. A CHECK constraint can't see the previous row value, which is why this
-- has to be a trigger, and it fires regardless of whether the caller went through JPA or native
-- SQL -- the claim query and reaper both bypass the Job entity entirely (spec §2: "rejected at
-- the persistence layer, not just in application code").
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
