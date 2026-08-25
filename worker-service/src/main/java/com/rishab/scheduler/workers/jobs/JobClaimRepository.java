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

// The heart of the system (spec §4) -- hand-written native SQL, not a Spring Data derived query,
// because the locking behaviour is the entire point and has to be visible and exact:
//
// - FOR UPDATE SKIP LOCKED, not plain FOR UPDATE: without SKIP LOCKED, N concurrent workers would
//   serialize on the same candidate rows and throughput would collapse to single-worker
//   throughput. SKIP LOCKED lets each worker just take the next unlocked row instead of queueing
//   behind whichever worker got there first.
// - The CTE: LIMIT has to apply to the row-locking SELECT (pick which rows to claim), not to the
//   UPDATE (which must touch exactly the rows the SELECT already chose and locked) -- an UPDATE
//   with its own LIMIT isn't valid SQL, and re-selecting inside the UPDATE's WHERE clause would
//   reopen the race the locking was there to close.
// - READ COMMITTED (Postgres's default, made explicit below rather than left implicit per
//   CLAUDE.md's persistence convention) is correct here specifically because SKIP LOCKED already
//   does the work REPEATABLE READ's stricter snapshot would otherwise be needed for: each worker
//   only ever sees and locks rows nobody else has locked yet, so there's no phantom-read or
//   lost-update hazard left for a stronger isolation level to close.
// - Scales to thousands of jobs/minute, not millions: every claim still does a row-locking SELECT
//   plus an UPDATE against one Postgres primary, and idx_jobs_claimable only bounds the SELECT
//   side, not Postgres's per-row lock/WAL overhead. Past that point the fix isn't a bigger index,
//   it's sharding the queue or moving to a log-structured broker where "claim" isn't a row lock
//   at all.
// - Weekend 5's dependency predicate (NOT EXISTS ... dep.state <> 'SUCCEEDED') deliberately does
//   NOT get its own index. idx_jobs_claimable still narrows the SELECT to PENDING/due rows first;
//   the anti-join then only runs once per already-narrowed candidate, and job_dependencies' PK
//   (job_id, depends_on_id) already indexes "find this job's deps" via its leading column. A
//   dependency graph large enough to need more than that -- e.g. a materialized "ready" flag kept
//   in sync by a trigger -- is the "changes the index strategy" ROADMAP.md flags, but this
//   project's scale (spec §4's own "thousands of jobs/minute, not millions" ceiling) never gets
//   there. Most jobs have zero job_dependencies rows, so NOT EXISTS is nearly free for them.
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

	// Spec §5: lease extension for long-running jobs, run alongside the heartbeat refresh
	// (HeartbeatService).
	//
	// Guarded on BOTH state and ownership, because the two guards close different holes and the
	// state guard alone is not enough:
	//
	// - "AND state = 'RUNNING'" stops a job that finished between LeaseManager.startTracking and
	//   this call from having its lease resurrected.
	// - "AND claimed_by = ?" stops this worker from extending a lease it no longer holds. That
	//   sounds impossible while the job is still executing here, but it is exactly what happens
	//   after a false reclaim: this worker's heartbeat lapses (a GC pause, a Redis blip), the
	//   reaper requeues the job, another worker claims it -- and the job is RUNNING again, so the
	//   state guard passes. Without the ownership check this worker would then keep pushing the
	//   *new* owner's lease forward on every heartbeat tick, which is worse than doing nothing:
	//   if that new owner subsequently dies, the reaper cannot reclaim its work, because a
	//   stranger's heartbeat keeps renewing the lease of a job nobody is running.
	//
	// The update is a no-op rather than an error when the guard fails: losing the race is a
	// legitimate outcome, not a fault. JobPoller is where the lost race becomes visible (see
	// jobs_stale_completions_total there); a lost lease extension needs no such signal because the
	// job's real owner is extending it anyway.
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
				// next_run_at is untouched by the UPDATE above, so RETURNING gives back the value
				// the row already had -- i.e. the moment this job became due. That is what
				// job_queue_wait_duration (spec §10) measures against, and it is only available
				// here: once the claim commits, "when was this due before I claimed it" is gone
				// for a retried job, because the next failure overwrites next_run_at with a fresh
				// backoff.
				rs.getTimestamp("next_run_at").toInstant());
	}
}
