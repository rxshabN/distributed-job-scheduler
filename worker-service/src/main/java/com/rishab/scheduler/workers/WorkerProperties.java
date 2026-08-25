package com.rishab.scheduler.workers;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "worker")
public record WorkerProperties(
		@DefaultValue("5") int batchSize,
		@DefaultValue("30") int leaseSeconds,
		@DefaultValue("1000") long pollIntervalMs,
		@DefaultValue("1000") long baseBackoffDelayMillis,
		@DefaultValue("300000") long maxBackoffDelayMillis,
		// Spec §5: "a scheduled task on each worker refreshes the key every ttl / 3 seconds" --
		// kept as two independent properties rather than one deriving the other via SpEL, so the
		// ttl/3 relationship is a documented choice here, not implicit config magic.
		@DefaultValue("15") long heartbeatTtlSeconds,
		@DefaultValue("5000") long heartbeatRefreshIntervalMs) {
}
