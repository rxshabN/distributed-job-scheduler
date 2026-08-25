package com.rishab.scheduler.workers.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rishab.scheduler.workers.jobs.JobContext;
import com.rishab.scheduler.workers.jobs.JobExecutionException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EmailSimulationJobHandlerTest {

	private final EmailSimulationJobHandler handler = new EmailSimulationJobHandler();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void succeedsWhenFailureProbabilityIsZero() {
		JobContext ctx = context(Map.of("sleepMillis", 0, "failureProbability", 0.0));

		assertThatNoException(ctx);
	}

	@Test
	void alwaysFailsWhenFailureProbabilityIsOne() {
		JobContext ctx = context(Map.of("sleepMillis", 0, "failureProbability", 1.0));

		assertThatThrownBy(() -> handler.execute(ctx)).isInstanceOf(JobExecutionException.class);
	}

	@Test
	void sleepsForApproximatelyTheConfiguredDuration() throws JobExecutionException {
		JobContext ctx = context(Map.of("sleepMillis", 100, "failureProbability", 0.0));

		long start = System.currentTimeMillis();
		handler.execute(ctx);
		long elapsed = System.currentTimeMillis() - start;

		assertThat(elapsed).isGreaterThanOrEqualTo(100);
	}

	@Test
	void defaultsToNeverFailingWhenPayloadOmitsFailureProbability() {
		JobContext ctx = context(Map.of("sleepMillis", 0));

		assertThatNoException(ctx);
	}

	private void assertThatNoException(JobContext ctx) {
		try {
			handler.execute(ctx);
		} catch (JobExecutionException e) {
			throw new AssertionError("expected no exception", e);
		}
	}

	private JobContext context(Map<String, ?> payload) {
		return new JobContext(1L, "email-simulation", objectMapper.valueToTree(payload), 1, "test-worker");
	}
}
