package com.rishab.scheduler.workers.jobs;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class BackoffPolicy {

	private final long baseDelayMillis;
	private final long maxDelayMillis;

	public BackoffPolicy(long baseDelayMillis, long maxDelayMillis) {
		if (baseDelayMillis <= 0) {
			throw new IllegalArgumentException("baseDelayMillis must be positive, was " + baseDelayMillis);
		}
		if (maxDelayMillis < baseDelayMillis) {
			throw new IllegalArgumentException("maxDelayMillis (%d) must be >= baseDelayMillis (%d)"
					.formatted(maxDelayMillis, baseDelayMillis));
		}
		this.baseDelayMillis = baseDelayMillis;
		this.maxDelayMillis = maxDelayMillis;
	}

	public Instant nextRunAt(int attempt, Instant now) {
		return now.plusMillis(delayMillis(attempt));
	}

	long delayMillis(int attempt) {
		if (attempt < 1) {
			throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
		}
		long base = (1L << Math.min(attempt, 62)) * baseDelayMillis;
		long capped = (base <= 0 || base > maxDelayMillis) ? maxDelayMillis : base;
		return ThreadLocalRandom.current().nextLong(capped + 1);
	}
}
