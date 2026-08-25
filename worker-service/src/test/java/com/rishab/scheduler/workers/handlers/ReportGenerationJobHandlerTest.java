package com.rishab.scheduler.workers.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import com.rishab.scheduler.workers.jobs.JobContext;
import com.rishab.scheduler.workers.jobs.JobExecutionException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ReportGenerationJobHandlerTest {

	private final ReportGenerationJobHandler handler = new ReportGenerationJobHandler();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void occupiesTheCpuForApproximatelyTheConfiguredDuration() throws JobExecutionException {
		JobContext ctx = new JobContext(1L, "report-generation", objectMapper.valueToTree(Map.of("durationMillis", 100)),
				1, "test-worker");

		long start = System.currentTimeMillis();
		handler.execute(ctx);
		long elapsed = System.currentTimeMillis() - start;

		assertThat(elapsed).isGreaterThanOrEqualTo(100);
	}
}
