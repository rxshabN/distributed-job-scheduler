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
		@DefaultValue("15") long heartbeatTtlSeconds,
		@DefaultValue("5000") long heartbeatRefreshIntervalMs) {
}
