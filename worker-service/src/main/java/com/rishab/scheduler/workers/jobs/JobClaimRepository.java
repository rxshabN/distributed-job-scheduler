package com.rishab.scheduler.workers.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JobClaimRepository {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public JobClaimRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public List<ClaimedJob> claim(String workerId, int batchSize, int leaseSeconds) {
		String sql = """
				WITH claimed AS (
				    SELECT id
				    FROM jobs j
				    WHERE state = 'PENDING'
				      AND next_run_at <= now()
				      AND NOT EXISTS (
				          SELECT 1
				          FROM job_dependencies jd
				          JOIN jobs dep ON dep.id = jd.depends_on_id
				          WHERE jd.job_id = j.id AND dep.state <> 'SUCCEEDED'
				      )
				    ORDER BY priority DESC, next_run_at ASC
				    LIMIT ?
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE jobs j
				SET state            = 'RUNNING',
				    claimed_by       = ?,
				    lease_expires_at = now() + (? * INTERVAL '1 second'),
				    attempt_count    = attempt_count + 1,
				    updated_at       = now()
				FROM claimed c
				WHERE j.id = c.id
				RETURNING j.id, j.job_type, j.payload, j.attempt_count, j.max_attempts, j.priority, j.next_run_at
				""";
		return jdbcTemplate.query(sql, rowMapper(), batchSize, workerId, leaseSeconds);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void extendLease(Long jobId, String workerId, int leaseSeconds) {
		jdbcTemplate.update("""
				UPDATE jobs
				SET lease_expires_at = now() + (? * INTERVAL '1 second')
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", leaseSeconds, jobId, workerId);
	}

	private RowMapper<ClaimedJob> rowMapper() {
		return (ResultSet rs, int rowNum) -> mapRow(rs);
	}

	private ClaimedJob mapRow(ResultSet rs) throws SQLException {
		return new ClaimedJob(
				rs.getLong("id"),
				rs.getString("job_type"),
				objectMapper.readTree(rs.getString("payload")),
				rs.getInt("attempt_count"),
				rs.getInt("max_attempts"),
				rs.getInt("priority"),
				rs.getTimestamp("next_run_at").toInstant());
	}
}
