package com.rishab.scheduler.workers;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.jobs.ClaimedJob;
import com.rishab.scheduler.workers.jobs.JobClaimRepository;
import com.rishab.scheduler.workers.jobs.LeaseManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
		"worker.poll-interval-ms=3600000",
		"worker.heartbeat-refresh-interval-ms=3600000",
		"worker.lease-seconds=2",
		"worker.heartbeat-ttl-seconds=15",
		"spring.flyway.enabled=true"})
class LeaseExtensionIntegrationTest {

	private static final String KEY_PREFIX = "lease-extension-test-";

	@Autowired
	private JobClaimRepository jobClaimRepository;

	@Autowired
	private HeartbeatService heartbeatService;

	@Autowired
	private LeaseManager leaseManager;

	@Autowired
	private WorkerIdentity workerIdentity;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clearPreviousRun() {
		jdbcTemplate.update("DELETE FROM jobs WHERE idempotency_key LIKE ?", KEY_PREFIX + "%");
		leaseManager.stopTracking();
	}

	@Test
	void heartbeatRefreshExtendsTheLeaseOfTheInFlightJobPastItsOriginalExpiry() {
		Long jobId = claimFreshJobAsThisWorker();
		Instant lapsedLease = expireLease(jobId);
		assertThat(lapsedLease).isBefore(Instant.now());

		trackAsInFlight(jobId);
		heartbeatService.refresh();

		Instant extendedLease = leaseExpiresAtOf(jobId);
		assertThat(extendedLease).isAfter(lapsedLease);
		assertThat(extendedLease).isAfter(Instant.now());
		assertThat(stateOf(jobId)).isEqualTo("RUNNING");
	}

	@Test
	void aStillHeartbeatingWorkersLongRunningJobIsNotAReaperCandidate() {
		Long jobId = claimFreshJobAsThisWorker();
		expireLease(jobId);

		trackAsInFlight(jobId);
		heartbeatService.refresh();

		assertThat(reaperCandidateIdsWithExpiredLease()).doesNotContain(jobId);
		assertThat(redisTemplate.hasKey("worker:heartbeat:" + workerIdentity.workerId())).isTrue();
	}

	@Test
	void extendingIsSkippedForAJobThatIsNoLongerRunning() {
		Long jobId = claimFreshJobAsThisWorker();
		jdbcTemplate.update("UPDATE jobs SET state = CAST('SUCCEEDED' AS job_state) WHERE id = ?", jobId);
		Instant leaseBeforeRefresh = leaseExpiresAtOf(jobId);

		trackAsInFlight(jobId);
		heartbeatService.refresh();

		assertThat(leaseExpiresAtOf(jobId)).isEqualTo(leaseBeforeRefresh);
		assertThat(stateOf(jobId)).isEqualTo("SUCCEEDED");
	}

	private Long claimFreshJobAsThisWorker() {
		for (int attempt = 0; attempt < 20; attempt++) {
			Long jobId = insertDuePendingJob();
			List<ClaimedJob> claimed = jobClaimRepository.claim(workerIdentity.workerId(), 5, 2);
			if (claimed.stream().anyMatch(job -> job.id().equals(jobId))) {
				return jobId;
			}
			jdbcTemplate.update("DELETE FROM jobs WHERE id = ?", jobId);
		}
		throw new AssertionError("could not claim a fixture job as this worker after 20 attempts");
	}

	private void trackAsInFlight(Long jobId) {
		leaseManager.startTracking(jobId);
		assertThat(leaseManager.inFlightJobId()).contains(jobId);
	}

	private Instant expireLease(Long jobId) {
		Instant lapsed = Instant.now().minusSeconds(5);
		jdbcTemplate.update("UPDATE jobs SET lease_expires_at = ? WHERE id = ?", Timestamp.from(lapsed), jobId);
		return leaseExpiresAtOf(jobId);
	}

	private List<Long> reaperCandidateIdsWithExpiredLease() {
		return jdbcTemplate.queryForList(
				"SELECT id FROM jobs WHERE state = 'RUNNING' AND lease_expires_at < now()", Long.class);
	}

	private Instant leaseExpiresAtOf(Long jobId) {
		Timestamp lease = jdbcTemplate.queryForObject(
				"SELECT lease_expires_at FROM jobs WHERE id = ?", Timestamp.class, jobId);
		return lease != null ? lease.toInstant() : null;
	}

	private String stateOf(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, jobId);
	}

	private Long insertDuePendingJob() {
		return jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'report-generation', CAST(? AS jsonb), 'PENDING', 0, 5, ?, now(), now())
				RETURNING id
				""",
				Long.class,
				KEY_PREFIX + UUID.randomUUID(),
				objectMapper.createObjectNode().put("durationMillis", 0).toString(),
				Timestamp.from(Instant.now().minusSeconds(5)));
	}
}
