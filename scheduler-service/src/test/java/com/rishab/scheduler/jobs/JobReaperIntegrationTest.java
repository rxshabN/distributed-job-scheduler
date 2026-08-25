package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Tests JobReaper's own reclaim logic directly against real Postgres + Redis, proving the two
// halves of its guard condition (lease expired, heartbeat absent) independently -- each of the
// three tests below holds one half fixed and varies the other.
//
// The end-to-end story those halves add up to is spec §11 test 2, in
// WorkerCrashRecoveryIntegrationTest: crash a worker mid-execution, watch the reaper requeue the
// job, and watch a second worker finish it. Spec §11 test 7 (concurrent reapers not
// double-requeueing) is in ReaperConcurrencyIntegrationTest.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobReaperIntegrationTest {

	@Autowired
	private JobReaper jobReaper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@Transactional
	void reclaimsRunningJobWithExpiredLeaseAndNoHeartbeat() {
		Long jobId = insertRunningJob("dead-worker-1", Instant.now().minusSeconds(5));
		// deliberately no heartbeat key written for "dead-worker-1"

		jobReaper.reclaimOrphans();

		assertThat(stateOf(jobId)).isEqualTo("PENDING");
		assertThat(claimedByOf(jobId)).isNull();
	}

	@Test
	@Transactional
	void doesNotReclaimRunningJobWhoseWorkerIsStillHeartbeating() {
		Long jobId = insertRunningJob("alive-worker-1", Instant.now().minusSeconds(5));
		redisTemplate.opsForValue().set("worker:heartbeat:alive-worker-1", Instant.now().toString(), Duration.ofSeconds(15));

		jobReaper.reclaimOrphans();

		assertThat(stateOf(jobId)).isEqualTo("RUNNING");
	}

	@Test
	@Transactional
	void doesNotReclaimRunningJobWhoseLeaseHasNotExpiredYet() {
		Long jobId = insertRunningJob("dead-worker-2", Instant.now().plusSeconds(60));

		jobReaper.reclaimOrphans();

		assertThat(stateOf(jobId)).isEqualTo("RUNNING");
	}

	private String stateOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, jobId);
	}

	private String claimedByOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT claimed_by FROM jobs WHERE id = ?", String.class, jobId);
	}

	private Long insertRunningJob(String claimedBy, Instant leaseExpiresAt) {
		String idempotencyKey = "reaper-test-" + UUID.randomUUID();
		Long jobId = jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, attempt_count, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, 0, 5, now(), now(), now())
				RETURNING id
				""",
				Long.class, idempotencyKey, objectMapper.createObjectNode().toString());
		jdbcTemplate.update("""
				UPDATE jobs SET state = CAST('RUNNING' AS job_state), claimed_by = ?, lease_expires_at = ?
				WHERE id = ?
				""", claimedBy, java.sql.Timestamp.from(leaseExpiresAt), jobId);
		return jobId;
	}
}
