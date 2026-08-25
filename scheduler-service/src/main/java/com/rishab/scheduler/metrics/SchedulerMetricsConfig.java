package com.rishab.scheduler.metrics;

import com.rishab.scheduler.workers.WorkerHeartbeatService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

// Spec §10's two gauges. Registered against live suppliers (a DB count, a Redis key count) rather
// than pushed values, so each scrape reflects current state instead of whatever was last written.
@Configuration
public class SchedulerMetricsConfig {

	public SchedulerMetricsConfig(MeterRegistry registry, JdbcTemplate jdbcTemplate, WorkerHeartbeatService workerHeartbeatService) {
		Gauge.builder("jobs_pending_count", jdbcTemplate, SchedulerMetricsConfig::countPending)
				.description("Number of jobs currently in PENDING state")
				.register(registry);

		Gauge.builder("workers_alive_count", workerHeartbeatService, WorkerHeartbeatService::aliveCount)
				.description("Number of workers with a live Redis heartbeat")
				.register(registry);
	}

	private static double countPending(JdbcTemplate jdbcTemplate) {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jobs WHERE state = 'PENDING'", Long.class);
		return count != null ? count : 0;
	}
}
