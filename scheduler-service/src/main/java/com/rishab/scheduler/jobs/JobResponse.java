package com.rishab.scheduler.jobs;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record JobResponse(
		Long id,
		String idempotencyKey,
		String jobType,
		JsonNode payload,
		JobState state,
		int priority,
		int attemptCount,
		int maxAttempts,
		Instant nextRunAt,
		String claimedBy,
		Instant leaseExpiresAt,
		Instant createdAt,
		Instant updatedAt) {
}
