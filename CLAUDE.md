# CLAUDE.md — Working agreement for this repository

Read `PROJECT_SPEC.md` before making changes. It is the source of truth for architecture,
data model, and correctness requirements.

---

## Context

This is a portfolio project. Its purpose is to demonstrate the author's understanding of
distributed job execution in a technical interview. Code that the author cannot explain is
worse than no code at all.

That constraint shapes everything below.

---

## The boundary: what you write, what the author writes

### Do NOT write these. Leave a `// TODO(rishab):` stub with a description of the intended behaviour.

- The claim query and its surrounding transaction (`JobClaimRepository`)
- Lease expiry and heartbeat logic (`HeartbeatService`, `LeaseManager`)
- The orphan reaper loop (`JobReaper`)
- Retry backoff calculation (`BackoffPolicy`)
- The in-memory delay queue / priority heap, if one is added
- Topological sort for job dependencies, if Weekend 5 is attempted
- The fault-injection integration tests listed in spec §11

If the author asks to implement any of the above, respond by explaining the approach and the trade-offs,
then proceed to implement.

### Do write these freely

- JPA entities, repositories (except the claim query), DTOs, mappers
- REST controllers, request validation, exception handlers
- Spring configuration, `application.yml`, profiles
- Flyway migrations from the schema in the spec
- Dockerfiles, `docker-compose.yml`, GitHub Actions workflows
- The entire Next.js dashboard
- Micrometer metric registration and structured logging setup
- Job handler implementations (`http-callback`, `email-simulation`, `report-generation`)
- Test scaffolding, Testcontainers setup, fixtures, builders — but not the seven scenario
  tests in spec §11

---

## Conventions

**Java**

- Java 21. Use records for DTOs, sealed interfaces where a closed hierarchy is natural,
  pattern matching for switch. Do not use `var` for non-obvious types.
- Constructor injection only. No `@Autowired` on fields.
- No Lombok. Records and explicit code are more legible to an interviewer reading the repo.
- Package by feature, not by layer: `com.rishab.scheduler.jobs`, `.workers`, `.metrics`,
  not `.controller`, `.service`, `.repository`.
- Every public method that can fail declares a checked or documented unchecked exception.
  No swallowed exceptions, no bare `catch (Exception e)`.

**Persistence**

- Flyway for migrations. Never `ddl-auto: update`; set `ddl-auto: validate`.
- Native SQL for the claim query and reaper query. JPQL or Spring Data derived queries elsewhere.
- Every write path that touches more than one row is explicitly `@Transactional` with a stated
  isolation level.

**Testing**

- JUnit 5 and AssertJ. No Mockito for datastore behaviour — use Testcontainers.
- Test class naming: `JobClaimIntegrationTest`, `BackoffPolicyTest`.
- Every test name states the behaviour: `claimsEachJobExactlyOnceUnderConcurrency`, not `test1`.

**Next.js dashboard**

- App Router, TypeScript strict mode, Tailwind. Server components where possible.
- No state management library. `useState` plus a polling hook is sufficient.
- No component library beyond Tailwind. Hand-rolled tables and forms.

**Commits**

- Conventional Commits: `feat:`, `fix:`, `test:`, `chore:`, `docs:`.
- One logical change per commit. This repo's history will be read.

---

## Explaining, not just implementing

When you write non-trivial code, add a comment explaining the _decision_, not the mechanics.

Bad:

```java
// Loop through the jobs and update each one
```

Good:

```java
// Batch size is bounded to avoid holding row locks longer than the lease interval;
// a worker that claims 1000 jobs and dies leaves all 1000 stuck until the reaper runs.
```

The author will be asked about these decisions. Comments that explain "why" are study notes.
Comments that restate the code are noise — do not write them.

---

## Things that are explicitly out of scope

Do not add these, even if they seem like improvements:

- Kafka, RabbitMQ, or any external message broker. The Postgres-based queue is a deliberate
  choice; the spec explains the throughput ceiling it accepts.
- Spring Cloud, service discovery, config server, API gateway.
- Kubernetes manifests. Docker Compose locally and Cloud Run in production.
- Spring Security, OAuth, JWT. Authentication is out of scope; note it as a known gap in the README.
- Any claim of exactly-once delivery, anywhere in code, comments, or docs.
- Additional microservices. Two backend services is the correct number.

If you believe one of these is genuinely necessary, say so and explain why rather than adding it.

---

## Definition of done for any task

1. Code compiles and `./mvnw verify` passes
2. New behaviour has a test
3. No `TODO` left unexplained
4. Anything in the "do not write" list above is stubbed, not implemented
