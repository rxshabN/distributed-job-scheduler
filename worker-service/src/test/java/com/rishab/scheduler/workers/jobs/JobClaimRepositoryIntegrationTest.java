package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=true")
class JobClaimRepositoryIntegrationTest {

	@Autowired
	private JobClaimRepository jobClaimRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@Transactional
	void claimsDuePendingJobAndSetsRunningStateAndLease() {
		Long jobId = insertJob("PENDING", 0, Instant.now().minusSeconds(5), 5);

		List<ClaimedJob> claimed = jobClaimRepository.claim("worker-1", 5, 30);

		assertThat(claimed).extracting(ClaimedJob::id).containsExactly(jobId);
		ClaimedJob job = claimed.get(0);
		assertThat(job.attemptCount()).isEqualTo(1);
		assertThat(job.maxAttempts()).isEqualTo(5);

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT state::text, claimed_by, lease_expires_at FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("RUNNING");
		assertThat(row.get("claimed_by")).isEqualTo("worker-1");
		assertThat(row.get("lease_expires_at")).isNotNull();
	}

	@Test
	@Transactional
	void doesNotClaimJobsNotYetDue() {
		insertJob("PENDING", 0, Instant.now().plusSeconds(3600), 5);

		List<ClaimedJob> claimed = jobClaimRepository.claim("worker-1", 5, 30);

		assertThat(claimed).isEmpty();
	}

	@Test
	@Transactional
	void doesNotClaimAlreadyRunningJobs() {
		insertJob("RUNNING", 0, Instant.now().minusSeconds(5), 5);

		List<ClaimedJob> claimed = jobClaimRepository.claim("worker-1", 5, 30);

		assertThat(claimed).isEmpty();
	}

	@Test
	@Transactional
	void respectsBatchSizeAndPriorityOrdering() {
		Long lowPriority = insertJob("PENDING", 0, Instant.now().minusSeconds(5), 5);
		Long highPriority = insertJob("PENDING", 10, Instant.now().minusSeconds(5), 5);
		insertJob("PENDING", 0, Instant.now().minusSeconds(5), 5);

		List<ClaimedJob> claimed = jobClaimRepository.claim("worker-1", 2, 30);

		assertThat(claimed).hasSize(2);
		assertThat(claimed.get(0).id()).isEqualTo(highPriority);
	}

	@Test
	@Transactional
	void doesNotClaimAJobWhoseDependencyHasNotSucceededYet() {
		Long dependency = insertJob("PENDING", 0, Instant.now().minusSeconds(5), 5);
		Long dependent = insertJob("PENDING", 0, Instant.now().minusSeconds(5), 5);
		jdbcTemplate.update("INSERT INTO job_dependencies (job_id, depends_on_id) VALUES (?, ?)", dependent, dependency);

		List<ClaimedJob> claimed = jobClaimRepository.claim("worker-1", 5, 30);

		assertThat(claimed).extracting(ClaimedJob::id).containsExactly(dependency);
	}

	@Test
	@Transactional
	void claimsADependentJobOnceItsDependencyHasSucceeded() {
		Long dependency = insertJob("SUCCEEDED", 0, Instant.now().minusSeconds(5), 5);
		Long dependent = insertJob("PENDING", 0, Instant.now().minusSeconds(5), 5);
		jdbcTemplate.update("INSERT INTO job_dependencies (job_id, depends_on_id) VALUES (?, ?)", dependent, dependency);

		List<ClaimedJob> claimed = jobClaimRepository.claim("worker-1", 5, 30);

		assertThat(claimed).extracting(ClaimedJob::id).containsExactly(dependent);
	}

	private Long insertJob(String state, int priority, Instant nextRunAt, int maxAttempts) {
		String idempotencyKey = "claim-test-" + UUID.randomUUID();
		return jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), CAST(? AS job_state), ?, ?, ?, now(), now())
				RETURNING id
				""",
				Long.class,
				idempotencyKey,
				objectMapper.createObjectNode().toString(),
				state,
				priority,
				maxAttempts,
				Timestamp.from(nextRunAt));
	}
}
