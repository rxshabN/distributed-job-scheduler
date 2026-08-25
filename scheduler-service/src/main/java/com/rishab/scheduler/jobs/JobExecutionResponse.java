package com.rishab.scheduler.jobs;

import java.time.Instant;

public record JobExecutionResponse(
		Long id,
		int attempt,
		String workerId,
		Instant startedAt,
		Instant finishedAt,
		Boolean success,
		String errorMessage) {
}
