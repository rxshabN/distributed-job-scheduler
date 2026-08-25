package com.rishab.scheduler.jobs;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatsService {

	private static final String LATENCY_NOTE =
			"p50/p95 execution latency is not aggregated here: job_execution_duration is recorded "
					+ "in worker-service's own Micrometer registry (a separate JVM), which this service "
					+ "cannot read in-memory. See each worker's /actuator/prometheus for that timer, or "
					+ "scrape both services from Prometheus/Grafana for a unified view.";

	private final JdbcTemplate jdbcTemplate;

	public StatsService(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public StatsResponse getStats() {
		Map<String, Long> countsByState = new LinkedHashMap<>();
		for (JobState state : JobState.values()) {
			countsByState.put(state.name(), 0L);
		}
		jdbcTemplate.query("SELECT state::text AS state, COUNT(*) AS count FROM jobs GROUP BY state",
				rs -> {
					countsByState.put(rs.getString("state"), rs.getLong("count"));
				});

		Long completed = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM jobs
				WHERE state IN ('SUCCEEDED', 'DEAD_LETTER')
				  AND updated_at >= now() - INTERVAL '5 minutes'
				""", Long.class);

		return new StatsResponse(countsByState, completed != null ? completed : 0L, LATENCY_NOTE);
	}
}
