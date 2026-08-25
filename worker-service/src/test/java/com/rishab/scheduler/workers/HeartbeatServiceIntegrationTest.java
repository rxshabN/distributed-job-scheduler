package com.rishab.scheduler.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.jobs.JobClaimRepository;
import com.rishab.scheduler.workers.jobs.LeaseManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {"worker.poll-interval-ms=3600000", "spring.flyway.enabled=true"})
class HeartbeatServiceIntegrationTest {

	@Autowired
	private HeartbeatService heartbeatService;

	@Autowired
	private WorkerIdentity workerIdentity;

	@Autowired
	private LeaseManager leaseManager;

	@Autowired
	private JobClaimRepository jobClaimRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// Matches the claimed_by written by insertRunningJob() below.
	private static final String FIXTURE_OWNER = "test-worker";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void writesHeartbeatKeyAtStartup() {
		// @PostConstruct already ran once when the context started -- just verify the key exists.
		String key = "worker:heartbeat:" + workerIdentity.workerId();
		assertThat(redisTemplate.hasKey(key)).isTrue();
	}

	@Test
	void refreshRewritesTheHeartbeatKey() {
		String key = "worker:heartbeat:" + workerIdentity.workerId();
		redisTemplate.delete(key);

		heartbeatService.refresh();

		assertThat(redisTemplate.hasKey(key)).isTrue();
	}

	@Test
	@Transactional
	void refreshExtendsLeaseOfTrackedJob() {
		Long jobId = insertRunningJob(Instant.now().plusSeconds(2));
		leaseManager.startTracking(jobId);
		try {
			jobClaimRepository.extendLease(jobId, FIXTURE_OWNER, 300);

			Instant leaseExpiresAt = jdbcTemplate.queryForObject(
					"SELECT lease_expires_at FROM jobs WHERE id = ?", Timestamp.class, jobId).toInstant();
			assertThat(leaseExpiresAt).isAfter(Instant.now().plusSeconds(200));
		} finally {
			leaseManager.stopTracking();
		}
	}

	@Test
	@Transactional
	void extendLeaseDoesNothingForAJobThatIsNoLongerRunning() {
		Long jobId = insertRunningJob(Instant.now().plusSeconds(30));
		jdbcTemplate.update("UPDATE jobs SET state = CAST('SUCCEEDED' AS job_state), claimed_by = NULL, lease_expires_at = NULL WHERE id = ?", jobId);

		jobClaimRepository.extendLease(jobId, FIXTURE_OWNER, 300);

		Timestamp lease = jdbcTemplate.queryForObject("SELECT lease_expires_at FROM jobs WHERE id = ?", Timestamp.class, jobId);
		assertThat(lease).isNull();
	}

	private Long insertRunningJob(Instant leaseExpiresAt) {
		String idempotencyKey = "heartbeat-test-" + UUID.randomUUID();
		Long jobId = jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, attempt_count, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, 0, 5, now(), now(), now())
				RETURNING id
				""",
				Long.class, idempotencyKey, objectMapper.createObjectNode().toString());
		jdbcTemplate.update("""
				UPDATE jobs SET state = CAST('RUNNING' AS job_state), claimed_by = 'test-worker', lease_expires_at = ?
				WHERE id = ?
				""", Timestamp.from(leaseExpiresAt), jobId);
		return jobId;
	}
}
