package com.rishab.scheduler.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rishab.scheduler.TestcontainersConfiguration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// The trg_jobs_state_transition trigger (V1__initial_schema.sql) is exercised with raw SQL via
// JdbcTemplate, not the Job entity/JobRepository -- Job deliberately has no setter for state
// (see Job.java), and the whole point of the trigger is that it guards the persistence layer
// regardless of which code path issues the UPDATE (JPA, the claim query, the reaper, ...).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobStateTransitionTriggerIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JobRepository jobRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@Transactional
	void allowsPendingToRunningAndRefreshesUpdatedAt() {
		Job job = insertPendingJob("idem-trigger-legal-1");
		Instant updatedAtBeforeClaim = job.getUpdatedAt();

		jdbcTemplate.update("UPDATE jobs SET state = CAST('RUNNING' AS job_state) WHERE id = ?", job.getId());

		Instant updatedAtAfterClaim = jdbcTemplate.queryForObject(
				"SELECT updated_at FROM jobs WHERE id = ?", Instant.class, job.getId());
		String state = jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, job.getId());

		assertThat(state).isEqualTo("RUNNING");
		assertThat(updatedAtAfterClaim).isAfter(updatedAtBeforeClaim);
	}

	@Test
	@Transactional
	void allowsSameStateUpdateForLeaseExtension() {
		Job job = insertPendingJob("idem-trigger-legal-2");
		jdbcTemplate.update("UPDATE jobs SET state = CAST('RUNNING' AS job_state) WHERE id = ?", job.getId());

		jdbcTemplate.update(
				"UPDATE jobs SET lease_expires_at = now() + INTERVAL '30 seconds' WHERE id = ?", job.getId());

		String state = jdbcTemplate.queryForObject("SELECT state::text FROM jobs WHERE id = ?", String.class, job.getId());
		assertThat(state).isEqualTo("RUNNING");
	}

	@Test
	@Transactional
	void rejectsPendingDirectlyToSucceeded() {
		Job job = insertPendingJob("idem-trigger-illegal-1");

		assertThatThrownBy(() ->
				jdbcTemplate.update("UPDATE jobs SET state = CAST('SUCCEEDED' AS job_state) WHERE id = ?", job.getId()))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("illegal job state transition");
	}

	@Test
	@Transactional
	void rejectsSucceededGoingBackToPending() {
		Job job = insertPendingJob("idem-trigger-illegal-2");
		jdbcTemplate.update("UPDATE jobs SET state = CAST('RUNNING' AS job_state) WHERE id = ?", job.getId());
		jdbcTemplate.update("UPDATE jobs SET state = CAST('SUCCEEDED' AS job_state) WHERE id = ?", job.getId());

		assertThatThrownBy(() ->
				jdbcTemplate.update("UPDATE jobs SET state = CAST('PENDING' AS job_state) WHERE id = ?", job.getId()))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("illegal job state transition");
	}

	private Job insertPendingJob(String idempotencyKey) {
		Job job = new Job(idempotencyKey, "http-callback", objectMapper.createObjectNode(), 0, 5, Instant.now());
		return jobRepository.saveAndFlush(job);
	}
}
