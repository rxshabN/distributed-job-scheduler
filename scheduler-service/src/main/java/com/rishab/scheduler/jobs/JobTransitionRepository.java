package com.rishab.scheduler.jobs;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// Native SQL, like every other state mutation on jobs (Job has no setters by design -- see
// Job.java). The WHERE id = ? AND state = 'X' pattern is a single atomic conditional update
// rather than a separate SELECT-then-UPDATE: JobService never loads the entity via JPA before
// calling these, specifically to avoid the classic native-query-vs-first-level-cache trap --
// EntityManager.find() (which Spring Data's findById delegates to) checks the persistence
// context cache before hitting the DB, so if the entity had already been loaded this transaction,
// a post-update findById would silently return the stale pre-update instance.
@Repository
public class JobTransitionRepository {

	private final JdbcTemplate jdbcTemplate;

	public JobTransitionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int cancel(Long id) {
		return jdbcTemplate.update("UPDATE jobs SET state = 'CANCELLED' WHERE id = ? AND state = 'PENDING'", id);
	}

	// Resets attempt_count to 0 rather than leaving it at max_attempts: a manual retry is an
	// operator saying "try this again from scratch" (e.g. the downstream dependency that caused
	// every prior failure is fixed now), not "give it exactly one more attempt before permanently
	// dead-lettering again". next_run_at = now() makes it immediately claimable rather than
	// waiting on whatever backoff schedule produced the original dead-letter.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public int retry(Long id) {
		return jdbcTemplate.update("""
				UPDATE jobs
				SET state = 'PENDING', attempt_count = 0, next_run_at = now()
				WHERE id = ? AND state = 'DEAD_LETTER'
				""", id);
	}

	public Optional<JobState> findState(Long id) {
		List<String> results = jdbcTemplate.queryForList("SELECT state::text FROM jobs WHERE id = ?", String.class, id);
		return results.isEmpty() ? Optional.empty() : Optional.of(JobState.valueOf(results.get(0)));
	}
}
