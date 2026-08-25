package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.rishab.scheduler.workers.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
class JobCompletionRepositoryIntegrationTest {

	@Autowired
	private JobCompletionRepository jobCompletionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// Matches the claimed_by written by insertRunningJob() below -- i.e. this is the worker that
	// owns the fixture job, which every method here requires since JobCompletionRepository's
	// completion methods are guarded on claimed_by matching.
	private static final String OWNER = "worker-1";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@Transactional
	void markSucceededClearsClaimAndSetsSucceeded() {
		Long jobId = insertRunningJob();

		assertThat(jobCompletionRepository.markSucceeded(jobId, OWNER)).isEqualTo(1);

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT state::text, claimed_by, lease_expires_at FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("SUCCEEDED");
		assertThat(row.get("claimed_by")).isNull();
		assertThat(row.get("lease_expires_at")).isNull();
	}

	@Test
	@Transactional
	void rescheduleWithBackoffReturnsJobToPendingWithNewNextRunAt() {
		Long jobId = insertRunningJob();
		Instant nextRunAt = Instant.now().plusSeconds(60);

		assertThat(jobCompletionRepository.rescheduleWithBackoff(jobId, OWNER, nextRunAt)).isEqualTo(1);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT state::text, claimed_by, lease_expires_at, next_run_at FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("PENDING");
		assertThat(row.get("claimed_by")).isNull();
		assertThat(row.get("lease_expires_at")).isNull();
		assertThat(((Timestamp) row.get("next_run_at")).toInstant()).isCloseTo(nextRunAt, within(Duration.ofSeconds(1)));
	}

	@Test
	@Transactional
	void markDeadLetteredClearsClaimAndSetsDeadLetter() {
		Long jobId = insertRunningJob();

		assertThat(jobCompletionRepository.markDeadLettered(jobId, OWNER)).isEqualTo(1);

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT state::text, claimed_by, lease_expires_at FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("DEAD_LETTER");
		assertThat(row.get("claimed_by")).isNull();
		assertThat(row.get("lease_expires_at")).isNull();
	}

	private Long insertRunningJob() {
		String idempotencyKey = "completion-test-" + UUID.randomUUID();
		Long jobId = jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, state, priority, attempt_count, max_attempts, claimed_by, lease_expires_at, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), 'PENDING', 0, 0, 5, NULL, NULL, now(), now(), now())
				RETURNING id
				""",
				Long.class, idempotencyKey, objectMapper.createObjectNode().toString());
		jdbcTemplate.update("UPDATE jobs SET state = CAST('RUNNING' AS job_state), claimed_by = 'worker-1', lease_expires_at = now() + INTERVAL '30 seconds' WHERE id = ?", jobId);
		return jobId;
	}
}
