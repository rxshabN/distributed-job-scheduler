package com.rishab.scheduler.workers.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class JobHandlerRegistryTest {

	@Test
	void findsRegisteredHandlerByJobType() {
		JobHandler handler = fakeHandler("email-simulation");
		JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler));

		assertThat(registry.find("email-simulation")).contains(handler);
	}

	@Test
	void returnsEmptyForUnregisteredJobType() {
		JobHandlerRegistry registry = new JobHandlerRegistry(List.of(fakeHandler("email-simulation")));

		assertThat(registry.find("does-not-exist")).isEmpty();
	}

	@Test
	void rejectsTwoHandlersForTheSameJobTypeAtConstruction() {
		List<JobHandler> handlers = List.of(fakeHandler("http-callback"), fakeHandler("http-callback"));

		assertThatThrownBy(() -> new JobHandlerRegistry(handlers))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("http-callback");
	}

	private static JobHandler fakeHandler(String jobType) {
		return new JobHandler() {
			@Override
			public String jobType() {
				return jobType;
			}

			@Override
			public void execute(JobContext ctx) {
			}
		};
	}
}
