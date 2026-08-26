package com.rishab.scheduler.workers.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobExecutionAuditRepository {

	private final JdbcTemplate jdbcTemplate;

	public JobExecutionAuditRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void recordAttempt(Long jobId, int attempt, String workerId, Instant startedAt, Instant finishedAt,
			boolean success, String errorMessage) {
		jdbcTemplate.update("""
				INSERT INTO job_executions (job_id, attempt, worker_id, started_at, finished_at, success, error_message)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
				jobId, attempt, workerId, Timestamp.from(startedAt), Timestamp.from(finishedAt), success, errorMessage);
	}
}
