package com.rishab.scheduler.jobs;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

// dependsOn[] (spec §7, Weekend 5): job IDs that must reach SUCCEEDED before this job is
// claimable. Validated and inserted in JobService.submit() -- existence-checked against jobs,
// cycle-checked via DependencyCycleDetector, and enforced at claim time by worker-service's claim
// query predicate, not just accepted and ignored.
public record JobSubmissionRequest(
		@NotBlank String jobType,
		@NotNull JsonNode payload,
		@NotBlank String idempotencyKey,
		Integer priority,
		@Min(1) Integer maxAttempts,
		Instant runAt,
		List<Long> dependsOn) {

	private static final int DEFAULT_PRIORITY = 0;
	private static final int DEFAULT_MAX_ATTEMPTS = 5;

	int priorityOrDefault() {
		return priority != null ? priority : DEFAULT_PRIORITY;
	}

	int maxAttemptsOrDefault() {
		return maxAttempts != null ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
	}

	Instant runAtOrDefault() {
		return runAt != null ? runAt : Instant.now();
	}

	Set<Long> dependsOnOrEmpty() {
		return dependsOn != null ? Set.copyOf(dependsOn) : Set.of();
	}
}
