# Distributed Job Scheduler — Project Specification

A fault-tolerant distributed job execution system. Clients submit jobs over REST; a pool
of workers claims and executes them; the system survives worker crashes without losing or
duplicating work.

Owner: Rishab Nagwani
Purpose: portfolio project demonstrating Java, Spring Boot, distributed coordination,
concurrency, PostgreSQL transactions, Redis, testing, CI/CD, and cloud deployment.

---

## 1. System overview

Three deployable units plus two datastores.

| Component | Language / Framework | Responsibility |
|---|---|---|
| `scheduler-service` | Java 21, Spring Boot 4 | REST API, job persistence, DAG resolution, orphan reclamation, metrics |
| `worker-service` | Java 21, Spring Boot 4 | Claims due jobs, executes handlers, heartbeats, reports results |
| `dashboard` | Next.js 16, TypeScript, Tailwind | Job submission UI, live job state, worker health, retry inspection |
| PostgreSQL 16 | — | Source of truth: jobs, attempts, dead letters |
| Redis 7 | — | Worker heartbeat leases, ephemeral coordination state |

Workers are horizontally scalable: running N worker instances must not change correctness.

### Data flow

1. Client POSTs a job to `scheduler-service`. Job is written to Postgres with state `PENDING`
   and a `next_run_at` timestamp.
2. Worker instances poll Postgres for due jobs using `SELECT ... FOR UPDATE SKIP LOCKED`,
   atomically transitioning claimed rows to `RUNNING` and stamping `claimed_by` and `lease_expires_at`.
3. Each worker registers a heartbeat key in Redis with a TTL **at process startup**, refreshed on
   a fixed interval independent of job activity — not "on claim". Heartbeats starting only on
   claim would leave idle workers invisible to `GET /api/v1/workers` and `workers_alive_count`.
   The per-job lease (`lease_expires_at`) remains a separate, coarser-grained mechanism set at
   claim time.
4. On success the worker writes `SUCCEEDED`. On failure it computes a backoff and either
   reschedules with `PENDING` and a future `next_run_at`, or transitions the job to the
   `DEAD_LETTER` state once `attempt_count >= max_attempts`. (There is no separate dead-letter
   table — `DEAD_LETTER` is a state on the `jobs` row, same as any other state.)
5. A reaper loop in `scheduler-service` finds `RUNNING` jobs whose `lease_expires_at` has passed
   and whose worker heartbeat is absent from Redis, and returns them to `PENDING`.

---

## 2. Correctness model

State this explicitly in the README. It is the single most important thing to be able to
explain in an interview.

- **Delivery guarantee: at-least-once.** A job may execute more than once if a worker completes
  execution and dies before committing its result. This is unavoidable without distributed
  transactions across the job store and the side effect.
- **Idempotency is the mitigation.** Every job carries a client-supplied `idempotency_key`.
  Handlers are required to be idempotent with respect to that key. The `job_executions` table
  records `(idempotency_key, attempt)` so duplicate side effects are detectable.
- **Exactly-once is not claimed anywhere.** Do not write "exactly-once" in code comments,
  the README, or the resume bullet. It is not what this system provides.

### Job state machine

```
PENDING ──claim──────────────────────> RUNNING ──success──────────────────> SUCCEEDED
   │  ^                                    │
   │  └──lease expired, no heartbeat       ├──failure, attempts < max──────> PENDING (with backoff)
   │     (reaper)───────────────────────── │
   │                                       └──failure, attempts >= max────> DEAD_LETTER
   │
   └──cancel──> CANCELLED
```

Illegal transitions must be rejected at the persistence layer, not just in application code.
Enforced via a `BEFORE UPDATE` trigger on `jobs` comparing `OLD.state`/`NEW.state` against the
allowed-transition set drawn above; the same trigger refreshes `updated_at` on every transition
(its `DEFAULT now()` in §3 only fires on insert, nothing currently refreshes it on update). This
trigger is left as a `TODO(rishab)` stub in the `V1__initial_schema.sql` migration per
`CLAUDE.md`'s boundary — it is core correctness logic, not scaffolding.

---

## 3. Data model (PostgreSQL)

```sql
CREATE TYPE job_state AS ENUM ('PENDING','RUNNING','SUCCEEDED','DEAD_LETTER','CANCELLED');

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

-- The index that makes the claim query fast. Partial: only PENDING rows are ever scanned.
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

-- Optional, Weekend 5: job dependency edges for DAG execution.
CREATE TABLE job_dependencies (
    job_id        BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    depends_on_id BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    PRIMARY KEY (job_id, depends_on_id)
);
```

**Hibernate mapping for `state` and `payload`:**

```java
@Enumerated(EnumType.STRING)
@JdbcTypeCode(SqlTypes.NAMED_ENUM)
@Column(name = "state", nullable = false)
private JobState state;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "payload", nullable = false)
private JsonNode payload;
```

- Native queries that *bind* a `state` parameter must cast explicitly:
  `WHERE state = CAST(:state AS job_state)`. The §4 claim query uses a literal `'PENDING'` so it
  is unaffected, but the reaper and stats queries will need this.
- `payload` is Jackson `JsonNode`, not `Map<String,Object>`, so handlers can navigate it and it
  round-trips without key-ordering surprises.
- **Unverified against this project's actual stack:** the mapping above targets Hibernate 6's
  JDBC type descriptors. Spring Boot 4 ships Hibernate 7, which may have changed enough here to
  need adjustment — confirm this once JPA entities are written, rather than trusting it as-is.
  If `ddl-auto: validate` objects to the enum column, the escape hatch is `TEXT` + a `CHECK`
  constraint — functionally identical, costs nothing but the native type.

---

## 4. The claim query

This is the heart of the system. It must be written by hand and understood completely.

```sql
WITH claimed AS (
    SELECT id
    FROM jobs
    WHERE state = 'PENDING'
      AND next_run_at <= now()
    ORDER BY priority DESC, next_run_at ASC
    LIMIT :batchSize
    FOR UPDATE SKIP LOCKED
)
UPDATE jobs j
SET state            = 'RUNNING',
    claimed_by       = :workerId,
    lease_expires_at = now() + (:leaseSeconds * INTERVAL '1 second'),
    attempt_count    = attempt_count + 1,
    updated_at       = now()
FROM claimed c
WHERE j.id = c.id
RETURNING j.*;
```

Things to be able to explain without notes:

- Why `SKIP LOCKED` and not plain `FOR UPDATE`: without it, N workers serialize on the same
  rows and throughput collapses to single-worker throughput.
- Why the CTE: the `LIMIT` must apply to the row-locking select, not the update.
- What happens under `READ COMMITTED` vs `REPEATABLE READ` isolation, and why the default
  `READ COMMITTED` is correct here.
- Why this scales to thousands of jobs/minute but not millions, and what you would use instead
  at that point.

---

## 5. Lease and heartbeat protocol (Redis)

- On claim, the worker writes `worker:heartbeat:{workerId}` with `SET ... EX <ttl>`.
- A scheduled task on each worker refreshes the key every `ttl / 3` seconds.
- `lease_expires_at` on the job row is set to `now() + leaseSeconds` and extended alongside
  the heartbeat for long-running jobs.
- The reaper in `scheduler-service` runs on an interval:

```
for each job where state = 'RUNNING' and lease_expires_at < now():
    if redis.exists("worker:heartbeat:" + job.claimed_by) is false:
        transition job back to PENDING, clear claimed_by and lease_expires_at
        (do not increment attempt_count — the attempt was already counted at claim time)
```

The reaper must be safe to run concurrently on multiple scheduler instances. Use the same
`FOR UPDATE SKIP LOCKED` pattern.

**Known races to document, not hide:**
- A worker can be alive but partitioned from Redis, causing its heartbeat to expire and its job
  to be reclaimed while it is still executing. This is why handlers must be idempotent.
- A worker process can stay alive and heartbeating while the specific thread executing a job
  dies or hangs. `lease_expires_at` passes, but `redis.exists("worker:heartbeat:" + claimed_by)`
  is still true, so the reaper's guard condition never fires and the job is stuck in `RUNNING`
  forever. Heartbeats prove the *process* is alive, not that *that specific job* is progressing —
  there is no per-job liveness signal, only per-worker. Both races belong in the README.

---

## 6. Retry policy

Exponential backoff with full jitter:

```
base   = 2^attempt * baseDelayMillis
capped = min(base, maxDelayMillis)
delay  = random(0, capped)
```

Full jitter, not fixed backoff, and not exponential-without-jitter. Be able to explain the
thundering-herd problem it prevents: without jitter, N jobs that fail at the same moment retry
at the same moment, forever.

Defaults: `baseDelayMillis = 1000`, `maxDelayMillis = 300000`, `maxAttempts = 5`.

---

## 7. REST API (`scheduler-service`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/jobs` | Submit a job. Body: `jobType`, `payload`, `idempotencyKey`, optional `priority`, `maxAttempts`, `runAt`, `dependsOn[]` |
| `GET` | `/api/v1/jobs/{id}` | Job detail including full attempt history |
| `GET` | `/api/v1/jobs?state=&page=&size=` | Paged listing, filterable by state and jobType |
| `POST` | `/api/v1/jobs/{id}/cancel` | Cancel a `PENDING` job |
| `POST` | `/api/v1/jobs/{id}/retry` | Manually requeue a `DEAD_LETTER` job |
| `GET` | `/api/v1/workers` | Live worker list from Redis heartbeats |
| `GET` | `/api/v1/stats` | Counts by state, throughput, p50/p95 execution latency |
| `GET` | `/actuator/prometheus` | Micrometer metrics |

Submitting a job with an existing `idempotency_key` returns `200` with the existing job rather
than creating a duplicate or erroring. This is the API-level half of the idempotency story.

`/api/v1/stats`'s latency percentiles (p50/p95) are read from the injected Micrometer
`MeterRegistry`, not recomputed with `percentile_cont` over `job_executions` — Micrometer is
in-memory and O(1); the SQL equivalent scans and degrades as history grows. This means latency
figures are "recent" (a rolling window) and reset on process restart, not all-time — document
that caveat next to the endpoint. Counts by state stay a plain SQL query: they must be exact,
and they're cheap against the existing indexes.

---

## 8. Job handlers

Workers resolve `job_type` to a handler via a registry. Ship three:

1. `http-callback` — POSTs the payload to a URL. Naturally retryable, demonstrates real I/O failure.
2. `email-simulation` — sleeps a configurable duration, fails with configurable probability.
   Used for load testing and demos.
3. `report-generation` — CPU-bound work with a deliberate slow path, for showing lease extension
   on long-running jobs.

```java
public interface JobHandler {
    String jobType();
    void execute(JobContext ctx) throws JobExecutionException;
}
```

Handlers are discovered via Spring's `ApplicationContext` and registered in a map at startup.

---

## 9. Dashboard (Next.js)

Single-page app, polls `/api/v1/stats` and `/api/v1/jobs` on an interval. No WebSockets —
polling is honest for this use case and simpler to defend.

Views:
- **Submit** — form to enqueue a job, choose type, set priority and max attempts
- **Jobs table** — live state, attempt count, next run time, claimed-by worker; click for
  attempt history and error messages
- **Workers** — heartbeat status, currently held jobs, last-seen timestamp
- **Metrics** — jobs by state over time, retry rate, dead-letter count

Deliberately kept thin. The engineering story is in the backend; the dashboard exists to make
the system demoable and to satisfy full-stack requirements.

---

## 10. Observability

- Micrometer counters: `jobs_submitted_total`, `jobs_succeeded_total`, `jobs_failed_total`,
  `jobs_dead_lettered_total`, `jobs_reclaimed_total`
- Timers: `job_execution_duration`, `job_queue_wait_duration`
- Gauges: `jobs_pending_count`, `workers_alive_count`
- Structured JSON logging with `jobId`, `workerId`, `attempt` on every execution log line

`jobs_reclaimed_total` is the interesting metric — it is the count of times the system caught
a worker failure and recovered work that would otherwise have been lost.

---

## 11. Testing requirements

Integration tests use Testcontainers with real Postgres and Redis. No mocking of the datastores.

Required test scenarios:

1. **Concurrent claim** — 10 threads claim from a pool of 100 jobs; assert every job claimed
   exactly once and no thread blocks.
2. **Worker crash mid-execution** — kill a worker after claim but before completion; assert the
   reaper returns the job to `PENDING` and another worker completes it.
3. **Idempotency** — submit the same `idempotency_key` twice; assert one job row exists.
4. **Retry exhaustion** — a handler that always fails; assert exactly `maxAttempts` executions
   then `DEAD_LETTER`.
5. **Backoff timing** — assert `next_run_at` grows and stays within the jitter bounds.
6. **Lease extension** — a job running longer than the base lease is not reclaimed while its
   worker heartbeats.
7. **Reaper idempotency** — two scheduler instances reaping simultaneously do not double-requeue.

Target: 40+ tests total across unit and integration. Count them honestly for the resume bullet.

---

## 12. Deployment

**Hard constraint: ₹0, indefinitely.** No paid tier, no trial that converts to billing. This is
an architectural constraint, not just a pricing preference, and it rules out the obvious choice.

**Why Cloud Run (or any metered serverless free tier) doesn't work here:** `worker-service` is a
continuous polling loop, and every "free" serverless tier meters exactly what a poller does
constantly.
- Cloud Run allocates CPU only during request processing and scales to zero; a background poller
  gets throttled to near-nothing, and fixing that (`--no-cpu-throttling` + `--min-instances=1`)
  is explicitly a paid configuration.
- GCP Cloud SQL and Memorystore have no free tier at all (~$9–10/mo and ~$35+/mo minimums).
- Neon's free Postgres gives 100 compute-hours/month and scales to zero after 5 min idle — but
  continuous polling means the DB never idles, burning through 100 hours in about 4 days.
- Upstash's free Redis allows 500K commands/month — a 5-second heartbeat from 2 workers alone is
  ~1.04M commands/month, over the limit in about two weeks, before counting reaper checks.

**Architecture — permanently free:**

| Layer | Where | Cost |
|---|---|---|
| `scheduler-service` + 2 `worker-service` replicas + Postgres 16 + Redis 7 | Oracle Cloud Always Free ARM VM (2 OCPU / 12 GB RAM), running the same `docker-compose.yml` as local | $0, no expiry |
| `dashboard` | Vercel Hobby | $0 |
| CI (build + full Testcontainers suite) | GitHub Actions — unlimited minutes on public repos | $0 |
| image registry | GitHub Container Registry (`ghcr.io`) — free for public images | $0 |
| deploy | GitHub Actions step SSHes to the VM: `docker compose pull && docker compose up -d` | $0 |

This works because it's a real always-on VM: the polling loop runs exactly as designed, no CPU
throttling, no scale-to-zero. Self-hosting Postgres and Redis on the same box sidesteps every
metered limit above — no compute-hours, no command counts, nothing to run out of. Production then
runs the literal same compose file as local, which is what makes the §13 "clean machine"
checkpoint and the deployed system the same artifact rather than two things that can drift apart.

**Known risks, stated honestly:**
- Oracle can and does change Always Free terms without much notice (they halved the ARM tier
  from 4 OCPU/24 GB to 2 OCPU/12 GB in June 2026). 12 GB is still ample for this stack — don't
  architect to the ceiling.
- Ampere A1 capacity is frequently unavailable at signup in popular regions ("out of host
  capacity") — try a less busy region.
- Oracle requires a card for identity verification (not charged on Always Free) and has been
  known to reclaim idle Always Free resources.
- ARM64: build multi-arch images in CI. Temurin, `postgres:16`, and `redis:7` all publish arm64
  images, and the JVM makes the services themselves architecture-agnostic.

**Fallback if Oracle A1 capacity is unobtainable in every region tried:** GCP's `e2-micro`
always-free VM (`us-central1` / `us-west1` / `us-east1`, 30 GB **Standard** persistent disk — not
Balanced/SSD, those bill). Permanently free and still available, but only 1 GB RAM, so both JVMs
need `-Xmx256m` and it will be tight. Use only if Oracle falls through.

**Cross-origin note:** the dashboard (Vercel) and API (Oracle VM) are cross-origin by
definition, and Spring Security is out of scope for this project — a `WebMvcConfigurer` CORS
mapping allow-listing the Vercel origin is required in `scheduler-service`. Handle this before
deploy day so it isn't discovered as a browser console error.

Document actual monthly cost ($0) and the reasoning above in the README — "why not Cloud Run" is
as much a portfolio talking point as the claim query.

---

## 13. What "done" looks like

- [ ] `docker compose up` brings the whole system up on a clean machine
- [ ] Submitting 1000 jobs across 3 workers completes with zero lost jobs
- [ ] Killing a worker mid-run visibly reclaims and completes its jobs
- [ ] Full test suite green in CI
- [ ] Deployed and publicly reachable
- [ ] README explains the correctness model, the claim query, and the known races
- [ ] You can whiteboard the claim query and the reaper from memory
