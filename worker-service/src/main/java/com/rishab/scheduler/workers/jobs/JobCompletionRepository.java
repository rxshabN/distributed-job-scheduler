package com.rishab.scheduler.workers.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// The other half of "what happens between claim and execution finishing" (spec §4/§6 taken
// together): native SQL again, for the same reason as the claim query -- these are single-row
// UPDATEs against columns the claim query and reaper also touch natively, and going through the
// (deliberately setter-less, see scheduler-service's Job.java) JPA entity here would mean two
// different mutation paths for the same columns. claimed_by/lease_expires_at are cleared on every
// terminal-or-requeued outcome: once a job leaves RUNNING it's no longer meaningfully "claimed" by
// anyone, and the reaper (Weekend 3) already clears them on its own RUNNING -> PENDING reclaim, so
// this keeps that invariant true for the worker's own success/failure paths too. updated_at isn't
// set explicitly -- trg_jobs_state_transition (V1__initial_schema.sql) refreshes it on every
// UPDATE unconditionally, so doing it again here would just be redundant.
//
// ---------------------------------------------------------------------------------------------
// Every method here is guarded by "WHERE ... state = 'RUNNING' AND claimed_by = ?", and that
// predicate is the whole point of this class, not defensive boilerplate.
//
// Completing by id alone is a lost-update bug with a real trigger. The sequence: this worker
// claims job 7 and starts executing; its heartbeat lapses (a long GC pause, a Redis blip, a
// network partition -- spec §5 documents this as an accepted race); the reaper sees an expired
// lease and no heartbeat and correctly requeues job 7; worker B claims it and starts executing.
// Now this worker's handler returns and it writes the outcome. Unguarded, that write lands on a
// row worker B owns:
//
//   - rescheduleWithBackoff was the damaging one: it would set a job worker B is actively
//     executing back to PENDING with claimed_by = NULL, making it immediately claimable by a
//     *third* worker. That is not merely a duplicate execution -- it manufactures one, and it
//     does so behind the reaper's back, because no lease expired and no heartbeat was missing.
//   - markSucceeded would report success for work worker B has not finished, so a job that then
//     fails in B is recorded as SUCCEEDED.
//   - markDeadLettered would burn B's attempt on this worker's failure.
//
// The guard turns all three into no-ops. That is the correct outcome: this worker stopped being
// the owner the moment the reaper requeued the job, and a non-owner has no standing to say how the
// job ended. The attempt itself is still recorded in job_executions by JobPoller either way -- the
// work genuinely happened, and the audit trail should say so even though the outcome was
// discarded.
//
// Each method returns the affected row count so the caller can tell "recorded" from "lost the
// race". Returning void here is what made this invisible in the first place.
@Repository
public class JobCompletionRepository {

	private final JdbcTemplate jdbcTemplate;

	public JobCompletionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @return 1 if this worker still owned the job and the transition was applied, 0 if ownership
	 *         had already been lost (reaped and re-claimed elsewhere) and the write was discarded.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int markSucceeded(Long jobId, String workerId) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'SUCCEEDED', claimed_by = NULL, lease_expires_at = NULL
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", jobId, workerId);
	}

	/**
	 * @return 1 if this worker still owned the job and it was requeued, 0 if the write was
	 *         discarded because ownership had already been lost.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int rescheduleWithBackoff(Long jobId, String workerId, Instant nextRunAt) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'PENDING', next_run_at = ?, claimed_by = NULL, lease_expires_at = NULL
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", Timestamp.from(nextRunAt), jobId, workerId);
	}

	/**
	 * @return 1 if this worker still owned the job and it was dead-lettered, 0 if the write was
	 *         discarded because ownership had already been lost.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int markDeadLettered(Long jobId, String workerId) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'DEAD_LETTER', claimed_by = NULL, lease_expires_at = NULL
				WHERE id = ? AND state = 'RUNNING' AND claimed_by = ?
				""", jobId, workerId);
	}
}
