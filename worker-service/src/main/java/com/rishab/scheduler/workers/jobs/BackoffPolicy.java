package com.rishab.scheduler.workers.jobs;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

// Full jitter (spec §6), not fixed backoff and not "equal jitter"/exponential-without-jitter: a
// fixed or equal-jitter delay still clusters retries into a narrow band, so N jobs that failed at
// the same moment keep retrying in near-lockstep against the same downstream dependency forever
// -- the thundering-herd problem. Full jitter picks uniformly across the *entire* [0, capped]
// window each time, so a batch of simultaneous failures spreads out across retries instead of
// staying synchronized.
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
		// 2^attempt overflows a long for any realistic maxAttempts long before it would matter --
		// clamping the shift keeps it from wrapping negative and producing a bogus tiny delay
		// instead of the intended "already well past the cap" result.
		long base = (1L << Math.min(attempt, 62)) * baseDelayMillis;
		long capped = (base <= 0 || base > maxDelayMillis) ? maxDelayMillis : base;
		return ThreadLocalRandom.current().nextLong(capped + 1);
	}
}
