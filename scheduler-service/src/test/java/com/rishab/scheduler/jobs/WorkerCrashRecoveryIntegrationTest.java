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

		claimAs(crashedWorker, Duration.ofSeconds(30));
		redisTemplate.opsForValue().set(HEARTBEAT_KEY_PREFIX + crashedWorker, Instant.now().toString(), Duration.ofSeconds(15));
		recordStartedAttempt(jobId, 1, crashedWorker);
		assertThat(stateOf(jobId)).isEqualTo("RUNNING");

		expireLease(jobId);
		jobReaper.reclaimOrphans();
		assertThat(stateOf(jobId)).as("a live heartbeat protects a lapsed lease").isEqualTo("RUNNING");

		redisTemplate.delete(HEARTBEAT_KEY_PREFIX + crashedWorker);

		jobReaper.reclaimOrphans();

		assertThat(stateOf(jobId)).isEqualTo("PENDING");
		assertThat(claimedByOf(jobId)).isNull();
		assertThat(leaseExpiresAtOf(jobId)).isNull();
		assertThat(attemptCountOf(jobId)).isEqualTo(1);

		List<Long> reclaimedForB = claimAs(survivingWorker, Duration.ofSeconds(30));
		assertThat(reclaimedForB).contains(jobId);
		assertThat(claimedByOf(jobId)).isEqualTo(survivingWorker);
		assertThat(attemptCountOf(jobId)).isEqualTo(2);

		recordStartedAttempt(jobId, 2, survivingWorker);
		completeAttempt(jobId, 2);
		markSucceeded(jobId);

		assertThat(stateOf(jobId)).isEqualTo("SUCCEEDED");

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

		assertThat(stateOf(jobId)).isEqualTo("PENDING");
		assertThat(nextRunAtOf(jobId)).isBefore(Instant.now());
		assertThat(claimAs("next-worker-" + UUID.randomUUID(), Duration.ofSeconds(30))).contains(jobId);
	}

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
