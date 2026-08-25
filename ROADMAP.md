# Build Roadmap

Four weekends, plus an optional fifth. Each weekend ends with something that runs.

Before Weekend 1, complete the environment setup: JDK 21, Node.js 22 LTS, WSL2, Docker Desktop,
VS Code Java and Spring Boot extension packs, Claude Code.

---

## Weekend 0 — Half a day: learn enough Spring Boot

Do not skip this. Going into Weekend 1 without it means Claude Code writes code you cannot read.

1. Spring Boot quickstart at https://spring.io/quickstart — one hour.
2. Build a throwaway CRUD API by hand: one entity, one repository, one controller, one
   `@Transactional` service method. Postgres in Docker. Do not use Claude Code for this.
3. Read enough to answer: what does `@SpringBootApplication` actually enable, what is a bean,
   what does constructor injection do that field injection does not, when does `@Transactional`
   silently not apply (self-invocation).

Delete the throwaway project afterwards.

---

## Weekend 1 — Scheduler service and persistence

**Generate the projects.** At https://start.spring.io:

- Project: Maven, Language: Java, Spring Boot 4.x, Java 21 (3.x is no longer offered by
  Initializr — see `PROJECT_SPEC.md` §1)
- Group `com.rishab`, Artifact `scheduler-service`
- Dependencies: Spring Web, Spring Data JPA, PostgreSQL Driver, Flyway Migration,
  Spring Boot Actuator, Validation, Testcontainers, Spring Boot DevTools, **Prometheus**,
  **Spring Data Redis** (the reaper and `GET /api/v1/workers` both live in `scheduler-service`
  and need Redis; Actuator alone does not expose `/actuator/prometheus` without the Prometheus
  registry dependency)

Repeat for `worker-service` with the same dependency set.

**Build order:**

1. Monorepo layout: `/scheduler-service`, `/worker-service`, `/dashboard`, `/docker-compose.yml`
2. `docker-compose.yml` with Postgres 16 and Redis 7 — Claude Code
3. Flyway migration `V1__initial_schema.sql` from spec §3 — Claude Code, then read it line by line
4. JPA entities and repositories — Claude Code
5. `POST /api/v1/jobs`, `GET /api/v1/jobs/{id}`, `GET /api/v1/jobs` — Claude Code
6. Idempotency-key handling on submit: unique constraint plus catching the violation and
   returning the existing job — **write this yourself**, it is the first correctness decision
7. Actuator and Prometheus endpoint — Claude Code

**Checkpoint:** you can submit a job via curl, see it in Postgres as `PENDING`, and submitting
the same idempotency key twice yields one row.

---

## Weekend 2 — Worker service, claim, and retry

The most important weekend. Most of this is on your hands.

1. Worker bootstrap, config, `workerId` generation — Claude Code
2. `JobHandler` interface and registry — Claude Code
3. Three handler implementations — Claude Code
4. **The claim query** — spec §4. Write it yourself. Then, in `psql`, run `EXPLAIN ANALYZE`
   on it and confirm the partial index is used.
5. **The claim transaction** — batch size, isolation level, what happens if the worker dies
   between claim and execution. Write it yourself.
6. **`BackoffPolicy`** — spec §6, full jitter. Write it yourself. Unit test it.
7. Failure path: increment attempts, reschedule with backoff, or dead-letter — write yourself
8. `job_executions` audit rows — Claude Code

**Checkpoint:** run two worker containers against 100 submitted jobs. Every job executes exactly
once. Force a handler to fail and watch backoff and dead-lettering work.

**Verify concurrency properly.** Add a temporary `UNIQUE` constraint on `(job_id, attempt)` in
`job_executions` — it is already in the schema — and confirm no constraint violations under load.
That constraint is your proof that claim is actually atomic.

---

## Weekend 3 — Failure detection and the test suite

This is the weekend that makes the project defensible. It is your MangoDesk skill applied to
your own system.

1. Redis heartbeat write and refresh loop — **write yourself**
2. `lease_expires_at` extension for long-running jobs — **write yourself**
3. **The reaper** — spec §5. Write yourself. Make it concurrent-safe with `SKIP LOCKED`.
4. `GET /api/v1/workers` reading live heartbeats — Claude Code
5. Testcontainers base class and fixtures — Claude Code
6. **The seven scenario tests** from spec §11 — write yourself, all of them

For test 2 (worker crash), do not mock it. Start a real worker container, `docker kill` it
mid-execution, and assert recovery. That is the test you will describe in interviews.

**Checkpoint:** `docker kill` a worker holding 20 jobs. Within the lease TTL, another worker
picks them up and finishes them. `jobs_reclaimed_total` increments by 20.

---

## Weekend 4 — Dashboard, CI/CD, deploy

Almost entirely delegable.

1. `npx create-next-app@latest dashboard --typescript --tailwind --app` — then hand to Claude Code
2. Four views from spec §9 — Claude Code
3. Multi-stage Dockerfiles for both services — Claude Code
4. GitHub Actions: `mvn verify` with Testcontainers, build multi-arch images, push to GitHub
   Container Registry (`ghcr.io`) — Claude Code
5. Deploy scheduler, workers, Postgres, and Redis via the same `docker-compose.yml` on an
   Oracle Cloud Always Free ARM VM (see `PROJECT_SPEC.md` §12 for why, and the GCP `e2-micro`
   fallback); Vercel for dashboard — Claude Code writes the Actions deploy step, you run the
   Oracle signup and SSH key setup yourself
6. **README** — write yourself. Architecture diagram, the correctness model, the claim query
   explained, known races, why not Kafka, measured cost.

The README is a resume artifact in its own right. Six separate job descriptions in your
application list flagged "written technical communication" as a gap. This closes it.

**Checkpoint:** public URL, green CI badge, README that a stranger can read and understand.

---

## Weekend 5 (optional) — Job dependency DAG

Only if Weekends 1–4 are genuinely complete.

1. `job_dependencies` table and `dependsOn[]` on the submit API — Claude Code
2. **Topological sort with cycle detection** — Kahn's algorithm. Write yourself.
   Reject submissions that would create a cycle, with a clear error naming the cycle.
3. Claim query gains a predicate: a job is only claimable when all dependencies are `SUCCEEDED`
   — **write yourself**, this changes the index strategy
4. Dashboard DAG visualization — Claude Code

This is stronger interview material than adding a message broker would be, and it overlaps
directly with your DSA preparation.

---

## Guardrail

At the end of each weekend, close the laptop and whiteboard on paper what you built. If you
cannot draw the claim query's locking behaviour or the reaper's race conditions from memory,
go back before moving on.

The project's value is entirely in what you can explain under questioning. A working system
you cannot defend is a liability on your resume, not an asset.
