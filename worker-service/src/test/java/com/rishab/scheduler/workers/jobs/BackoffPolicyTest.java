package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

// Plain unit test, no Spring context -- BackoffPolicy is pure math (spec §6). This is deliberately
// narrower than spec §11 test 5 ("Backoff timing": assert next_run_at grows and stays within
// jitter bounds across real repeated attempts against a live job row), which stays reserved.
class BackoffPolicyTest {

	@Test
	void rejectsNonPositiveBaseDelay() {
		assertThatThrownBy(() -> new BackoffPolicy(0, 1000))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMaxDelayBelowBaseDelay() {
		assertThatThrownBy(() -> new BackoffPolicy(2000, 1000))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAttemptBelowOne() {
		BackoffPolicy policy = new BackoffPolicy(1000, 300_000);
		assertThatThrownBy(() -> policy.delayMillis(0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void delayIsWithinZeroToCappedExponentialBoundForEachAttempt() {
		BackoffPolicy policy = new BackoffPolicy(1000, 300_000);

		for (int attempt = 1; attempt <= 10; attempt++) {
			long expectedCap = Math.min((1L << attempt) * 1000, 300_000);
			for (int sample = 0; sample < 50; sample++) {
				long delay = policy.delayMillis(attempt);
				assertThat(delay).isBetween(0L, expectedCap);
			}
		}
	}

	@Test
	void delayNeverExceedsMaxDelayEvenForLargeAttempts() {
		BackoffPolicy policy = new BackoffPolicy(1000, 300_000);

		for (int sample = 0; sample < 50; sample++) {
			assertThat(policy.delayMillis(30)).isBetween(0L, 300_000L);
		}
	}

	@Test
	void nextRunAtAddsDelayToGivenInstant() {
		BackoffPolicy policy = new BackoffPolicy(1000, 1000);
		Instant now = Instant.parse("2026-01-01T00:00:00Z");

		// base delay == max delay == 1000ms, so every attempt is capped at exactly 1000ms
		// regardless of the exponential term -- delay is deterministically in [0, 1000].
		Instant nextRunAt = policy.nextRunAt(1, now);

		assertThat(nextRunAt).isBetween(now, now.plusMillis(1000));
	}
}
