package com.rishab.scheduler.workers.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// job_executions rows are the audit trail spec §7's GET /api/v1/jobs/{id} reads back out. The
// table's existing UNIQUE (job_id, attempt) constraint (V1__initial_schema.sql) is what the
// roadmap's Weekend 2 load-test checkpoint uses as proof the claim query is actually atomic: if
// two workers ever claimed the same job for the same attempt, this insert would collide.
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
