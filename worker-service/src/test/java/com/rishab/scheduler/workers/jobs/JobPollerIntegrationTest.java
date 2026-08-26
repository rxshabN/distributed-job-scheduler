package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.TestcontainersConfiguration;
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
@TestPropertySource(properties = {"worker.poll-interval-ms=3600000", "spring.flyway.enabled=true"})
class JobPollerIntegrationTest {

	@Autowired
	private JobPoller jobPoller;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@Transactional
	void successfulJobIsMarkedSucceededWithAnAuditRow() {
		Long jobId = insertJob(5, Map.of("sleepMillis", 0, "failureProbability", 0.0));

		jobPoller.pollAndExecute();

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT state::text FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("SUCCEEDED");
		assertThat(auditRowCount(jobId)).isEqualTo(1);
		assertThat(auditSuccess(jobId)).isTrue();
	}

	@Test
	@Transactional
	void failedJobWithAttemptsRemainingIsRescheduledWithBackoffNotDeadLettered() {
		Long jobId = insertJob(5, Map.of("sleepMillis", 0, "failureProbability", 1.0));

		jobPoller.pollAndExecute();

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT state::text, next_run_at FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("PENDING");
		assertThat(row.get("next_run_at")).isNotNull();
		assertThat(auditRowCount(jobId)).isEqualTo(1);
		assertThat(auditSuccess(jobId)).isFalse();
	}

	@Test
	@Transactional
	void failedJobAtMaxAttemptsIsDeadLettered() {
		Long jobId = insertJob(1, Map.of("sleepMillis", 0, "failureProbability", 1.0));

		jobPoller.pollAndExecute();

		Map<String, Object> row = jdbcTemplate.queryForMap("SELECT state::text FROM jobs WHERE id = ?", jobId);
		assertThat(row.get("state")).isEqualTo("DEAD_LETTER");
		assertThat(auditRowCount(jobId)).isEqualTo(1);
	}

	private int auditRowCount(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM job_executions WHERE job_id = ?", Integer.class, jobId);
	}

	private boolean auditSuccess(Long jobId) {
		return jdbcTemplate.queryForObject("SELECT success FROM job_executions WHERE job_id = ?", Boolean.class, jobId);
	}

	private Long insertJob(int maxAttempts, Map<String, ?> payload) {
		String idempotencyKey = "poller-test-" + UUID.randomUUID();
		return jdbcTemplate.queryForObject("""
				INSERT INTO jobs (idempotency_key, job_type, payload, max_attempts, next_run_at, created_at, updated_at)
				VALUES (?, 'email-simulation', CAST(? AS jsonb), ?, ?, now(), now())
				RETURNING id
				""",
				Long.class,
				idempotencyKey,
				objectMapper.writeValueAsString(payload),
				maxAttempts,
				java.sql.Timestamp.from(Instant.now().minusSeconds(5)));
	}
}
