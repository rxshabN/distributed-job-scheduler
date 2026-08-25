package com.rishab.scheduler.jobs;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// Spec §5: finds RUNNING jobs whose lease has expired and whose worker is no longer heartbeating,
// and returns them to PENDING. Lives in scheduler-service, not worker-service (spec §1's table:
// "orphan reclamation" is scheduler-service's job) -- a worker that's actually dead can't reclaim
// its own orphaned work, so this has to run somewhere else entirely.
//
// FOR UPDATE SKIP LOCKED on the candidate SELECT (same pattern as the claim query, spec §4) is
// what makes this "safe to run concurrently on multiple scheduler instances" per spec §5: if two
// scheduler replicas both wake up at once, each only ever sees and locks the candidates the other
// hasn't already grabbed, so there's no window where both reclaim the same row.
//
// The Redis check can't happen inside the SQL -- Postgres has no way to query Redis -- so this is
// necessarily a hybrid: lock candidates in SQL, check each one's heartbeat in application code,
// then update only the confirmed orphans. The rows whose worker is still alive stay locked until
// this transaction commits (a few milliseconds at this scale), which is an acceptable cost for
// keeping the whole loop atomic per invocation.
@Component
public class JobReaper {

	private static final Logger log = LoggerFactory.getLogger(JobReaper.class);
	private static final String HEARTBEAT_KEY_PREFIX = "worker:heartbeat:";

	private final JdbcTemplate jdbcTemplate;
	private final StringRedisTemplate redisTemplate;
	private final MeterRegistry meterRegistry;
	private final int batchSize;

	public JobReaper(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate, MeterRegistry meterRegistry,
			@Value("${reaper.batch-size:200}") int batchSize) {
		this.jdbcTemplate = jdbcTemplate;
		this.redisTemplate = redisTemplate;
		this.meterRegistry = meterRegistry;
		this.batchSize = batchSize;
	}

	// Bounded per sweep, for the same reason the claim query bounds its batch: every candidate row
	// stays row-locked for the whole transaction, and this transaction is slower than most because
	// it makes a Redis round trip per candidate between the SELECT and the UPDATE. Unbounded, a
	// mass failure -- a worker fleet restarting, or a Redis outage that expires every heartbeat at
	// once -- would lock every RUNNING row in the table in a single transaction and hold them
	// across N network calls, blocking the claim query's own FOR UPDATE on those rows and stalling
	// the very workers that are trying to recover.
	//
	// Draining a backlog over several sweeps instead of one is the deliberate trade: at a 10s
	// interval, 200 orphans per sweep clears thousands within a minute, and reclamation was never
	// latency-critical -- these jobs are already stalled by definition. Concurrent scheduler
	// replicas make this strictly better rather than worse: SKIP LOCKED means two reapers take
	// disjoint batches, so the fleet's total drain rate scales with replica count.
	@Scheduled(fixedDelayString = "${reaper.interval-ms:10000}")
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void reclaimOrphans() {
		List<OrphanCandidate> candidates = jdbcTemplate.query("""
				SELECT id, claimed_by
				FROM jobs
				WHERE state = 'RUNNING' AND lease_expires_at < now()
				ORDER BY lease_expires_at ASC
				LIMIT ?
				FOR UPDATE SKIP LOCKED
				""", candidateRowMapper(), batchSize);

		// Oldest expiry first (the ORDER BY above): when the backlog exceeds one batch, the jobs
		// that have been stalled longest are recovered first, so no job can be starved by a steady
		// stream of fresher orphans arriving ahead of it.
		for (OrphanCandidate candidate : candidates) {
			boolean heartbeatAlive = Boolean.TRUE.equals(
					redisTemplate.hasKey(HEARTBEAT_KEY_PREFIX + candidate.claimedBy()));
			if (!heartbeatAlive) {
				// Do not increment attempt_count -- the attempt was already counted at claim
				// time (spec §5), so reclaiming isn't a new attempt, just returning an
				// interrupted one to the queue.
				jdbcTemplate.update("""
						UPDATE jobs
						SET state = 'PENDING', claimed_by = NULL, lease_expires_at = NULL
						WHERE id = ?
						""", candidate.id());
				meterRegistry.counter("jobs_reclaimed_total").increment();
				log.warn("reclaimed orphaned job {} (worker '{}' has no live heartbeat)", candidate.id(), candidate.claimedBy());
			}
		}
	}

	private static RowMapper<OrphanCandidate> candidateRowMapper() {
		return (ResultSet rs, int rowNum) -> mapRow(rs);
	}

	private static OrphanCandidate mapRow(ResultSet rs) throws SQLException {
		return new OrphanCandidate(rs.getLong("id"), rs.getString("claimed_by"));
	}

	private record OrphanCandidate(Long id, String claimedBy) {
	}
}
