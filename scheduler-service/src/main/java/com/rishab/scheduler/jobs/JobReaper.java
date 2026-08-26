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
		for (OrphanCandidate candidate : candidates) {
			boolean heartbeatAlive = Boolean.TRUE.equals(
					redisTemplate.hasKey(HEARTBEAT_KEY_PREFIX + candidate.claimedBy()));
			if (!heartbeatAlive) {
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
