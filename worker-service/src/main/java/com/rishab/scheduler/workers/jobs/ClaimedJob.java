package com.rishab.scheduler.workers.jobs;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record ClaimedJob(
		Long id,
		String jobType,
		JsonNode payload,
		int attemptCount,
		int maxAttempts,
		int priority,
		Instant nextRunAt) {
}
