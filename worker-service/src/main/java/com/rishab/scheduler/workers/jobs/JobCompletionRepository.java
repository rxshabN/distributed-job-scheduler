package com.rishab.scheduler.workers.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JobCompletionRepository {

	private final JdbcTemplate jdbcTemplate;

	public JobCompletionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int markSucceeded(Long jobId, String workerId) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'SUCCEEDED', claimed_by = NULL, lease_expires_at = NULL
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", jobId, workerId);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int rescheduleWithBackoff(Long jobId, String workerId, Instant nextRunAt) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'PENDING', next_run_at = ?, claimed_by = NULL, lease_expires_at = NULL
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", Timestamp.from(nextRunAt), jobId, workerId);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int markDeadLettered(Long jobId, String workerId) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'DEAD_LETTER', claimed_by = NULL, lease_expires_at = NULL
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", jobId, workerId);
	}
}
