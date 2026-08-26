package com.rishab.scheduler.workers.handlers;

import com.rishab.scheduler.workers.jobs.JobContext;
import com.rishab.scheduler.workers.jobs.JobExecutionException;
import com.rishab.scheduler.workers.jobs.JobHandler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ReportGenerationJobHandler implements JobHandler {

	private static final long DEFAULT_DURATION_MILLIS = 3000;

	@Override
	public String jobType() {
		return "report-generation";
	}

	@Override
	public void execute(JobContext ctx) throws JobExecutionException {
		JsonNode durationNode = ctx.payload().get("durationMillis");
		long durationMillis = durationNode != null ? durationNode.asLong() : DEFAULT_DURATION_MILLIS;

		MessageDigest digest = sha256();
		byte[] state = ("report-" + ctx.jobId()).getBytes();
		long deadline = System.currentTimeMillis() + durationMillis;
		while (System.currentTimeMillis() < deadline) {
			for (int i = 0; i < 10_000; i++) {
				state = digest.digest(state);
				digest.reset();
			}
		}
	}

	private static MessageDigest sha256() throws JobExecutionException {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new JobExecutionException("SHA-256 unavailable", e);
		}
	}
}
