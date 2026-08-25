package com.rishab.scheduler.workers.handlers;

import com.rishab.scheduler.workers.jobs.JobContext;
import com.rishab.scheduler.workers.jobs.JobExecutionException;
import com.rishab.scheduler.workers.jobs.JobHandler;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

// Spec §8 handler 2: sleeps a configurable duration, fails with a configurable probability. Used
// for load testing and demos -- not real email, so the "failure" is an explicit dial rather than
// something that depends on an actual flaky dependency being available. Payload shape:
// {"sleepMillis": 200, "failureProbability": 0.1} -- both optional, default to 200ms / never-fail.
@Component
public class EmailSimulationJobHandler implements JobHandler {

	private static final long DEFAULT_SLEEP_MILLIS = 200;
	private static final double DEFAULT_FAILURE_PROBABILITY = 0.0;

	@Override
	public String jobType() {
		return "email-simulation";
	}

	@Override
	public void execute(JobContext ctx) throws JobExecutionException {
		JsonNode payload = ctx.payload();
		long sleepMillis = longField(payload, "sleepMillis", DEFAULT_SLEEP_MILLIS);
		double failureProbability = doubleField(payload, "failureProbability", DEFAULT_FAILURE_PROBABILITY);

		try {
			Thread.sleep(sleepMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new JobExecutionException("email-simulation interrupted while sleeping", e);
		}

		if (ThreadLocalRandom.current().nextDouble() < failureProbability) {
			throw new JobExecutionException(
					"email-simulation failed (simulated, failureProbability=%.2f)".formatted(failureProbability));
		}
	}

	private static long longField(JsonNode payload, String field, long defaultValue) {
		JsonNode node = payload.get(field);
		return node != null ? node.asLong() : defaultValue;
	}

	private static double doubleField(JsonNode payload, String field, double defaultValue) {
		JsonNode node = payload.get(field);
		return node != null ? node.asDouble() : defaultValue;
	}
}
