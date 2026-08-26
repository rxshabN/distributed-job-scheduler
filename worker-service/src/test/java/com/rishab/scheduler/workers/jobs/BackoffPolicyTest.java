package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

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

		Instant nextRunAt = policy.nextRunAt(1, now);

		assertThat(nextRunAt).isBetween(now, now.plusMillis(1000));
	}
}
