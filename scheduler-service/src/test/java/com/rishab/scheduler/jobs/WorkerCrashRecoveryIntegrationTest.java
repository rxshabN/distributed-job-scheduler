package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

// Spec §11 test 2: "kill a worker after claim but before completion; assert the reaper returns
// the job to PENDING and another worker completes it." This is the end-to-end recovery story
// -- JobReaperIntegrationTest only proves the reaper's guard condition in isolation.
//
// Two deliberate substitutions, both stated rather than hidden:
//
// 1. The "kill" is modelled as deleting the crashed worker's Redis heartbeat key and letting its
//    lease lapse -- which is *exactly* what a `docker kill` looks like from this service's
//    perspective, and is the entire signal the reaper acts on. A container kill would additionally
//    prove the JVM doesn't get a chance to clean up after itself, but nothing in the reclaim path
//    depends on that: there is no shutdown hook releasing the claim, by design (a worker that can
//    run a shutdown hook was never the failure mode worth protecting against).
// 2. "Another worker completes it" is driven with the claim query's SQL rather than by importing
//    worker-service, which is a separate Maven module and not on this service's classpath. The
//    predicate is kept identical to JobClaimRepository's; if that query changes, this copy has to
//    change with it. The alternative -- a cross-module test fixture -- would couple the two
//    services' build graphs for one test, which the two-service split exists to avoid.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WorkerCrashRecoveryIntegrationTest {

	private static final String KEY_PREFIX = "crash-recovery-test-";
	private static final String HEARTBEAT_KEY_PREFIX = "worker:heartbeat:";

	@Autowired
	private JobReaper jobReaper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
	}

	@Test
	void aCrashedWorkersInFlightJobIsReclaimedAndCompletedByAnotherWorker() {
		String crashedWorker = "crashed-worker-" + UUID.randomUUID();
		String survivingWorker = "surviving-worker-" + UUID.randomUUID();
		Long jobId = insertDuePendingJob();

		// --- worker A claims and starts executing, heartbeating normally ---
		claimAs(crashedWorker, Duration.ofSeconds(30));
		redisTemplate.opsForValue().set(HEARTBEAT_KEY_PREFIX + crashedWorker, Instant.now().toString(), Duration.ofSeconds(15));
		recordStartedAttempt(jobId, 1, crashedWorker);
		assertThat(stateOf(jobId)).isEqualTo("RUNNING");

		// While A is alive the job must NOT be reclaimed even once its lease lapses -- otherwise
		// every slow job would be reclaimed out from under a perfectly healthy worker.
		expireLease(jobId);
		jobReaper.reclaimOrphans();
		assertThat(stateOf(jobId)).as("a live heartbeat protects a lapsed lease").isEqualTo("RUNNING");

		// --- worker A is killed: its heartbeat key vanishes when the TTL is not refreshed ---
		redisTemplate.delete(HEARTBEAT_KEY_PREFIX + crashedWorker);

		jobReaper.reclaimOrphans();

		assertThat(stateOf(jobId)).isEqualTo("PENDING");
		assertThat(claimedByOf(jobId)).isNull();
		assertThat(leaseExpiresAtOf(jobId)).isNull();
		// The interrupted attempt is not re-counted (spec §5) -- reclaiming returns an attempt to
		// the queue, it does not consume a fresh one.
		assertThat(attemptCountOf(jobId)).isEqualTo(1);

		// --- worker B picks the reclaimed job up and finishes it ---
		List<Long> reclaimedForB = claimAs(survivingWorker, Duration.ofSeconds(30));
		assertThat(reclaimedForB).contains(jobId);
		assertThat(claimedByOf(jobId)).isEqualTo(survivingWorker);
		assertThat(attemptCountOf(jobId)).isEqualTo(2);

		recordStartedAttempt(jobId, 2, survivingWorker);
		completeAttempt(jobId, 2);
		markSucceeded(jobId);

		assertThat(stateOf(jobId)).isEqualTo("SUCCEEDED");

		// The audit trail is what makes the recovery legible after the fact: attempt 1 abandoned
		// by the crashed worker with no finished_at, attempt 2 completed by the survivor. This is
		// also the honest picture of at-least-once -- the work was started twice, and the system
		// never claimed otherwise.
		List<Map<String, Object>> executions = jdbcTemplate.queryForList(
				"SELECT attempt, worker_id, finished_at, success FROM job_executions WHERE job_id = ? ORDER BY attempt", jobId);
		assertThat(executions).hasSize(2);
		assertThat(executions.get(0).get("worker_id")).isEqualTo(crashedWorker);
		assertThat(executions.get(0).get("finished_at")).as("the crashed worker never finished its attempt").isNull();
		assertThat(executions.get(1).get("worker_id")).isEqualTo(survivingWorker);
		assertThat(executions.get(1).get("success")).isEqualTo(true);
	}

	@Test
	void aReclaimedJobIsImmediatelyClaimableAgainWithoutWaitingOutBackoff() {
		String crashedWorker = "crashed-worker-" + UUID.randomUUID();
		Long jobId = insertDuePendingJob();
		claimAs(crashedWorker, Duration.ofSeconds(30));
		expireLease(jobId);

		jobReaper.reclaimOrphans();

		// The reaper leaves next_run_at alone -- unlike a handler failure, a crash is not evidence
		// the job itself is bad, so making the survivor wait out an exponential backoff would add
		// latency for no correctness gain. next_run_at is still the original past timestamp, so
		// the job is due the moment any worker next polls.
		assertThat(stateOf(jobId)).isEqualTo("PENDING");
		assertThat(nextRunAtOf(jobId)).isBefore(Instant.now());
		assertThat(claimAs("next-worker-" + UUID.randomUUID(), Duration.ofSeconds(30))).contains(jobId);
	}

	// Mirrors worker-service's JobClaimRepository.claim() -- see the class comment above for why
	// this is a copy rather than a call.
	private List<Long> claimAs(String workerId, Duration lease) {
		return jdbcTemplate.queryForList("""
				WITH claimed AS (
				    SELECT id
				    FROM jobs j
				    WHERE state = 'PENDING'
				      AND next_run_at <= now()
				      AND idempotency_key LIKE ?
				      AND NOT EXISTS (
				          SELECT 1
				          FROM job_dependencies jd
				          JOIN jobs dep ON dep.id = jd.depends_on_id
				          WHERE jd.job_id = j.id AND dep.state <> 'SUCCEEDED'
				      )
				    ORDER BY priority DESC, next_run_at ASC
				    LIMIT 5
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE jobs j
				SET state            = 'RUNNING',
				    claimed_by       = ?,
				    lease_expires_at = now() + (? * INTERVAL '1 second'),
				    attempt_count    = attempt_count + 1,
				    updated_at       = now()
				FROM claimed c
				WHERE j.id = c.id
				RETURNING j.id
				""", Long.class, KEY_PREFIX + "%", workerId, lease.toSeconds());
	}

	private void expireLease(Long jobId) {
		jdbcTemplate.update("UPDATE jobs SET lease_expires_at = ? WHERE id = ?",
				Timestamp.from(Instant.now().minusSeconds(5)), jobId);
	}

	private void recordStartedAttempt(Long jobId, int attempt, String workerId) {
		jdbcTemplate.update("""
				INSERT INTO job_executions (job_id, attempt, worker_id, started_at)
				VALUES (?, ?, ?, ?)
				""", jobId, attempt, workerId, Timestamp.from(Instant.now()));
	}

	private void completeAttempt(Long jobId, int attempt) {
		jdbcTemplate.update("""
				UPDATE job_executions SET finished_at = ?, success = true
				WHERE job_id = ? AND attempt = ?
				""", Timestamp.from(Instant.now()), jobId, attempt);
	}

	private void markSucceeded(Long jobId) {
		jdbcTemplate.update("""
				UPDATE jobs SET state = 'SUCCEEDED', claimed_by = NULL, lease_expires_at = NULL WHERE id = ?
				""", jobId);
	}

	private String stateOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, jobId);
	}

	private String claimedByOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT claimed_by FROM jobs WHERE id = ?", String.class, jobId);
	}

	private Integer attemptCountOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT attempt_count FROM jobs WHERE id = ?", Integer.class, jobId);
	}

	private Instant leaseExpiresAtOf(Long jobId) {
		Timestamp lease = jdbcTemplate.queryForObject("SELECT lease_expires_at FROM jobs WHERE id = ?", Timestamp.class, jobId);
		return lease != null ? lease.toInstant() : null;
	}

	private Instant nextRunAtOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT next_run_at FROM jobs WHERE id = ?", Timestamp.class, jobId).toInstant();
	}

	private Long insertDuePendingJob() {
		return jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, attempt_count, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, 0, 5, ?, now(), now())
				RETURNING id
				""",
				Long.class,
				KEY_PREFIX + UUID.randomUUID(),
				objectMapper.createObjectNode().toString(),
				Timestamp.from(Instant.now().minusSeconds(5)));
	}
}
